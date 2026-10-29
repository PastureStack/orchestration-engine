package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.*;
import io.cattle.platform.core.model.tables.records.*;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.*;
import io.cattle.platform.iaas.api.auth.apikey.*;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.junit.After;
import org.junit.Test;

public class AiResourceChangeProcessObserverTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void exactChildDenialBeforeApplySaveImmediatelyWinsAndKeepsSafeSameOperation() throws Exception {
        try (Fixture f = new Fixture()) {
            ProcessRecord child = f.child(); f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.store.failure(f.id));
            f.acknowledge(); assertEquals("failed", f.plan.get("status")); assertEquals(false, f.plan.get("verifiedResult"));
            var service = f.reader(true); Map<String, Object> result = service.get(f.id, f.receipt());
            assertEquals("failed", result.get("status")); assertEquals(false, result.get("verifiedResult"));
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, result.get("messageCode")); assertEquals(f.id, result.get("operationId"));
            assertFalse(result.containsKey(AiResourceChangePlanStore.PRIVATE_FAILURE));
            assertFalse(result.toString().contains("private payload")); assertFalse(result.containsKey("credentialId"));
            assertEquals(0, f.dispatches);
        }
    }

    @Test public void failureBetweenObservationAndLateSuccessWriteCannotReturnVerifiedSuccess() throws Exception {
        try (Fixture f = new Fixture()) {
            ProcessRecord child = f.child(); f.acknowledge(); var service = f.reader(true);
            when(service.domain.complete(any(), any(), anyMap(), anyMap())).thenAnswer(call -> {
                f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED); return true;
            });
            assertEquals("failed", service.get(f.id, f.receipt()).get("status"));
            assertEquals(false, service.get(f.id, f.receipt()).get("verifiedResult"));
            Map<String, Object> stale = new LinkedHashMap<>(f.plan); stale.put("status", "succeeded"); stale.put("verifiedResult", true);
            f.store.save(stale); assertEquals("failed", stale.get("status")); assertEquals(false, stale.get("verifiedResult"));
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.store.failure(f.id)); assertFalse(f.store.claim(stale));
            f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            f.connection.setAutoCommit(false);
            ApiRequest repeated = f.receipt(); repeated.setAction("apply"); repeated.setMethod("POST"); repeated.setRequestObject(Map.of());
            assertEquals(f.id, service.action(f.id, repeated).get("operationId")); assertEquals(0, f.dispatches); f.connection.rollback();
        }
    }

    @Test public void scalarCasRejectsAStaleSuccessSaveUsingAnOrderedBarrier() throws Exception {
        try (Fixture f = new Fixture()) {
            f.child(); f.acknowledge(); Map<String, Object> binding = f.store.execution(f.id);
            var barrier = new AiResourceChangePlanStore() {
                boolean first = true;
                @Override String failure(String id) {
                    String before = super.failure(id);
                    if (first) { first = false; assertTrue(f.store.recordAuthorizationDenied(id, binding)); }
                    return before;
                }
            };
            barrier.json = f.json; barrier.setConfiguration(new DefaultConfiguration().set(f.connection).set(SQLDialect.H2));
            Map<String, Object> stale = new LinkedHashMap<>(f.plan); stale.put("status", "succeeded"); stale.put("verifiedResult", true);
            barrier.save(stale); assertEquals("failed", stale.get("status")); assertEquals(false, stale.get("verifiedResult"));
            assertEquals("failed", f.store.load(f.id).get("status"));
        }
    }

    @Test public void failureAfterConditionalWriteIsReadBackBeforeReturningTheDto() throws Exception {
        try (Fixture f = new Fixture()) {
            f.child(); f.acknowledge(); Map<String, Object> binding = f.store.execution(f.id);
            var barrier = new AiResourceChangePlanStore() {
                int reads;
                @Override String failure(String id) {
                    if (++reads == 2) assertTrue(f.store.recordAuthorizationDenied(id, binding));
                    return super.failure(id);
                }
            };
            barrier.json = f.json; barrier.setConfiguration(new DefaultConfiguration().set(f.connection).set(SQLDialect.H2));
            Map<String, Object> stale = new LinkedHashMap<>(f.plan); stale.put("status", "succeeded"); stale.put("verifiedResult", true);
            barrier.save(stale); Map<String, Object> dto = f.reader(true).output(stale);
            assertEquals("failed", dto.get("status")); assertEquals(false, dto.get("verifiedResult")); assertEquals(f.id, dto.get("operationId"));
        }
    }

    @Test public void privateDispatchScopeRejectsDifferentActorProjectKeyOrNativeRequestWithoutSideEffects() throws Exception {
        for (String mutation : List.of("actor", "project", "key", "revision", "id", "type", "method", "trace", "plan")) {
            try (Fixture f = new Fixture()) {
                if (mutation.equals("actor")) when(f.owner.getAuthenticatedAsAccountId()).thenReturn(99L);
                if (mutation.equals("project")) when(f.owner.getAccountId()).thenReturn(88L);
                if (mutation.equals("key") || mutation.equals("revision")) {
                    ApiKeyCredentialContext original = ApiKeyCredentialContext.get(f.request);
                    ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(mutation.equals("key") ? 99 : 7,
                            42, "apiKeyRestricted", mutation.equals("revision") ? 4 : 3, original.policy()));
                    f.request.setAttribute("apiKey.audit.decision", "ALLOW");
                }
                if (mutation.equals("id")) f.request.setId("8");
                if (mutation.equals("type")) f.request.setType("stack");
                if (mutation.equals("method")) f.request.setMethod("PUT");
                if (mutation.equals("trace")) f.request.setAttribute("apiKey.audit.requestId", UUID.randomUUID().toString());
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                assertThrows(mutation, IllegalStateException.class, () -> f.observer.dispatch(f.request,
                        mutation.equals("plan") ? "aichange-00000000-0000-0000-0000-000000000000" : f.id, calls::incrementAndGet));
                assertEquals(mutation, 0, calls.get()); assertTrue(((List<?>) f.store.execution(f.id).get("initial")).isEmpty());
            }
        }
    }

    @Test public void completedRootAndTransientOrCanceledChildrenKeepTheirExistingMeaning() throws Exception {
        try (Fixture f = new Fixture()) {
            ProcessRecord child = f.child(); f.acknowledge();
            for (ExitReason reason : List.of(ExitReason.CANCELED, ExitReason.CHAIN, ExitReason.RETRY_EXCEPTION,
                    ExitReason.UNKNOWN_EXCEPTION, ExitReason.TIMEOUT, ExitReason.DELAY, ExitReason.DONE))
                f.observer.afterExecution(child, ProcessAuthorization.metadata(child), reason);
            assertNull(f.store.failure(f.id)); assertEquals("running", f.reader(false).get(f.id, f.receipt()).get("status"));
            assertEquals("succeeded", f.reader(true).get(f.id, f.receipt()).get("status"));
        }
    }

    @Test public void metadataOrProofMismatchCannotPoisonAnotherReceipt() throws Exception {
        try (Fixture f = new Fixture()) {
            ProcessRecord child = f.child(); var saved = f.json.writeValueAsString(child.getData());
            for (String field : List.of("requestId", "principalAccountId", "accountId", "keyId", "policyRevision", "targetId",
                    "targetType", "requestMethod", "requestAction", "operation", "childId", "parentId", "missingProof", "references")) {
                child.setData(f.json.readValue(saved));
                Map<String, Object> metadata = new LinkedHashMap<>(ProcessAuthorization.metadata(child));
                if (field.equals("missingProof")) child.getData().remove(ProcessAuthorization.VERIFIED_CHILD_KEY);
                else if (field.equals("references")) {
                    Map<String, Object> proof = map(child.getData().get(ProcessAuthorization.VERIFIED_CHILD_KEY));
                    proof.put("references", Map.of("volumeId", "99")); child.getData().put(ProcessAuthorization.VERIFIED_CHILD_KEY, proof);
                } else if (field.equals("parentId") || field.equals("childId")) {
                    Map<String, Object> proof = map(child.getData().get(ProcessAuthorization.VERIFIED_CHILD_KEY)); proof.put(field, "99");
                    child.getData().put(ProcessAuthorization.VERIFIED_CHILD_KEY, proof);
                } else {
                    Object original = metadata.get(field); metadata.put(field, original instanceof Number ? 99 : "unrelated");
                    child.getData().put(ProcessAuthorization.DATA_KEY, metadata);
                }
                f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
                assertNull(field, f.store.failure(f.id));
            }
        }
    }

    @Test public void replacedOrReassignedResourceAndUnfinishedProcessCannotPoisonReceipt() throws Exception {
        try (Fixture f = new Fixture()) {
            ProcessRecord child = f.child();
            child.setEndTime(null); f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            assertNull(f.store.failure(f.id)); child.setEndTime(new Date());
            f.service.setUuid("replacement"); f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            assertNull(f.store.failure(f.id)); f.service.setUuid("service-uuid");
            f.link.setServiceId(99L); f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            assertNull(f.store.failure(f.id));
        }
    }

    @Test public void bodyHeaderOrLookalikeAttributeDoesNotAttestAnExecution() throws Exception {
        try (Fixture f = new Fixture()) {
            var http = mock(jakarta.servlet.http.HttpServletRequest.class);
            when(http.getHeader(anyString())).thenReturn(f.id);
            f.request.setRequestServletContext(new io.github.ibuildthecloud.gdapi.server.model.ApiServletContext(http, null, null));
            f.request.setRequestObject(Map.of("apiKey.audit.requestId", f.id, AiResourceChangeProcessObserver.ATTRIBUTE, Map.of("id", f.id),
                    ProcessAuthorization.DATA_KEY, Map.of("requestId", f.id)));
            f.request.setAttribute(AiResourceChangeProcessObserver.ATTRIBUTE, Map.of("id", f.id));
            LaunchConfiguration config = new LaunchConfiguration("service.deactivate", "service", "7", 77L, 0,
                    Map.of(ProcessAuthorization.DATA_KEY, Map.of("requestId", f.id)));
            ProcessAuthorization.prepare(config, List.of(new ApiKeyProcessAuthorization(), f.observer));
            ProcessRecord record = new ProcessRecord(config, 55L, "server"); record.setEndTime(new Date());
            f.observer.afterExecution(record, ProcessAuthorization.metadata(record), ExitReason.AUTHORIZATION_DENIED);
            assertTrue(((List<?>) f.store.execution(f.id).get("initial")).isEmpty()); assertNull(f.store.failure(f.id));
        }
    }

    @Test public void completionReplayUsesOnlyTheFrameworksReservedTerminalReason() throws Exception {
        try (Fixture f = new Fixture()) {
            ProcessRecord child = f.child(); child.setEndTime(null);
            child.getData().put(ProcessAuthorization.COMPLETION_PENDING_KEY, "CHAIN");
            f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            assertNull(f.store.failure(f.id));
            child.getData().put(ProcessAuthorization.COMPLETION_PENDING_KEY, ExitReason.AUTHORIZATION_DENIED.name());
            f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            f.acknowledge(); assertEquals("failed", f.plan.get("status")); assertEquals(0, f.dispatches);
        }
    }

    @Test public void creationPinsOnlyTheAllocatedNativeRootInsideThePrivateScope() throws Exception {
        try (Fixture f = new Fixture("create")) {
            f.request.setId(null); f.request.setAction(null);
            f.observer.dispatch(f.request, f.id, () -> {
                ProcessAuthorization.prepare(new LaunchConfiguration("service.create", "service", "7", 77L, 0, Map.of()),
                        List.of(new ApiKeyProcessAuthorization(), f.observer)); return null;
            });
            assertNull(f.store.execution(f.id).get("rootId")); // Existing inactive resources cannot be borrowed.
            f.service.setState("requested");
            f.observer.dispatch(f.request, f.id, () -> {
                LaunchConfiguration nativeRoot = new LaunchConfiguration("service.create", "service", "7", 77L, 0, Map.of());
                ProcessAuthorization.prepare(nativeRoot, List.of(new ApiKeyProcessAuthorization(), f.observer)); return null;
            });
            assertEquals(7L, ((Number) f.store.execution(f.id).get("rootId")).longValue());
            assertEquals("service-uuid", f.store.execution(f.id).get("rootUuid"));
            assertNull(f.request.getAttribute(AiResourceChangeProcessObserver.ATTRIBUTE));
        }
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> values) values.forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    private static class Fixture implements AutoCloseable {
        final String id = "aichange-" + UUID.randomUUID(); final Connection connection;
        final JacksonJsonMapper json = new JacksonJsonMapper(); final AiResourceChangePlanStore store = new AiResourceChangePlanStore();
        final ObjectManager objects = mock(ObjectManager.class); final ApiKeyTargetResolver targets = new ApiKeyTargetResolver();
        final AiResourceChangeProcessObserver observer = new AiResourceChangeProcessObserver();
        final ServiceRecord service = new ServiceRecord(); final InstanceRecord instance = new InstanceRecord();
        final ServiceExposeMapRecord link = new ServiceExposeMapRecord(); final Policy owner = mock(Policy.class);
        final ApiRequest request = new ApiRequest(null, null); final Map<String, Object> plan = new LinkedHashMap<>(); int dispatches;
        Fixture() throws Exception { this("stop"); }
        Fixture(String operation) throws Exception {
            connection = DriverManager.getConnection("jdbc:h2:mem:outcome-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            store.json = json; store.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
            observer.store = store; observer.objects = objects; observer.targets = targets;
            SchemaFactory schemas = mock(SchemaFactory.class); when(schemas.getBaseType("project")).thenReturn("account");
            when(schemas.getBaseType("container")).thenReturn("instance"); DefaultIdFormatter formatter = new DefaultIdFormatter();
            formatter.setSchemaFactory(schemas); formatter.setTypeMappings(Map.of("stack", "st"));
            inject(targets, "objectManager", objects); inject(targets, "idFormatter", formatter);
            when(owner.getAccountId()).thenReturn(77L); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L);
            AccountRecord project = new AccountRecord(); project.setId(77L); project.setKind("project"); project.setUuid("project");
            AccountRecord actor = new AccountRecord(); actor.setId(42L); actor.setUuid("actor");
            StackRecord stack = new StackRecord(); stack.setId(3L); stack.setAccountId(77L); stack.setUuid("stack");
            service.setId(7L); service.setAccountId(77L); service.setStackId(3L); service.setKind("service"); service.setUuid("service-uuid");
            service.setState("inactive"); instance.setId(10L); instance.setAccountId(77L); instance.setUuid("instance"); instance.setState("stopping");
            link.setId(2L); link.setServiceId(7L); link.setInstanceId(10L); link.setAccountId(77L); link.setManaged(true);
            link.setUpgrade(false); link.setState("active"); link.setUuid("map");
            when(objects.loadResource(Account.class, 77L)).thenReturn(project); when(objects.loadResource(Account.class, 42L)).thenReturn(actor);
            when(objects.loadResource(io.cattle.platform.core.model.Stack.class, 3L)).thenReturn(stack);
            when(objects.loadResource("service", 7L)).thenReturn(service); when(objects.loadResource("service", "7")).thenReturn(service);
            when(objects.loadResource(Service.class, 7L)).thenReturn(service);
            when(objects.loadResource("instance", "10")).thenReturn(instance); when(objects.loadResource(Instance.class, 10L)).thenReturn(instance);
            when(objects.loadResource(ServiceExposeMap.class, 2L)).thenReturn(link);
            when(objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(link));
            when(objects.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 10L,
                    SERVICE_EXPOSE_MAP.SERVICE_ID, 7L, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(link));
            when(objects.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 10L, SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of(link));
            plan.putAll(Map.of("id", id, "actorId", 42L, "accountId", 77L, "credentialId", 7L, "credentialRevision", 3L,
                    "credentialKind", "apiKeyRestricted", "resourceType", "service", "operation", operation, "status", "preview"));
            plan.put("internalResourceId", operation.equals("create") ? null : 7L); plan.put("resourceId", "1s7");
            plan.put("expiresAt", "2030-01-01T00:00:00Z"); plan.put("parameters", Map.of()); plan.put("snapshot", Map.of());
            plan.put("confirmationState", "approved"); store.insert(plan); plan.put("status", "dispatching"); plan.put("operationId", id);
            plan.put("acceptedAt", 0L); assertTrue(store.claim(plan));
            var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                    List.of(new ApiKeyPolicy.Rule("project", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a4d"),
                            Set.of("read", "create", "update", "delete"))), ApiKeyPolicy.Purpose.AI_BRIDGE);
            request.setType("service"); request.setMethod("POST"); request.setId("7"); request.setAction("deactivate");
            ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setApiRequest(request); ApiContext.getContext().setIdFormatter(formatter);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, policy));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.keyId", "1c7"); request.setAttribute("apiKey.audit.operation", operation.equals("create") ? "create" : "update");
        }
        ProcessRecord child() throws Exception {
            final LaunchConfiguration root = new LaunchConfiguration("service.deactivate", "service", "7", 77L, 0, Map.of());
            observer.dispatch(request, id, () -> { ProcessAuthorization.prepare(root, List.of(new ApiKeyProcessAuthorization(), observer)); return null; });
            EngineContext engine = EngineContext.getEngineContext(); Map<String, Object> binding = ProcessAuthorization.metadata(root);
            engine.pushAuthorization(binding); var parent = engine.pushVerifiedExecution("service", "7", binding);
            LaunchConfiguration child = new LaunchConfiguration("instance.stop", "instance", "10", 77L, 0, Map.of("detail", "private payload"));
            try { ProcessAuthorization.prepare(child, List.of()); } finally { engine.popVerifiedExecution(parent); engine.popAuthorization(); }
            ProcessRecord record = new ProcessRecord(child, 102L, "server"); record.setData(json.readValue(json.writeValueAsString(record.getData())));
            record.setEndTime(new Date()); return record;
        }
        void acknowledge() { plan.put("dispatchAcknowledged", true); plan.put("status", "accepted"); store.save(plan); }
        ApiRequest receipt() {
            ApiRequest result = new ApiRequest(request); result.setId(id); result.setType("aiResourceChangePlan"); result.setMethod("GET");
            ApiKeyCredentialContext.attach(result, ApiKeyCredentialContext.get(request)); return result;
        }
        AiResourceChangePlanService reader(boolean complete) {
            var result = new AiResourceChangePlanService(); result.store = store; result.domain = mock(AiResourceChangeDomain.class);
            when(result.domain.actor(any())).thenReturn(owner); when(result.domain.receiptTarget(any(), any(), anyMap())).thenReturn(service);
            when(result.domain.complete(any(), any(), anyMap(), anyMap())).thenReturn(complete);
            result.locks = mock(LockManager.class);
            when(result.locks.lock(any(), any())).thenAnswer(call -> ((LockCallback<?>) call.getArgument(1)).doWithLock());
            result.auditSinks = List.of(mock(ApiKeyAuditSink.class)); result.clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC); return result;
        }
        @Override public void close() throws Exception { connection.close(); }
        private static void inject(Object target, String name, Object value) throws Exception {
            var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
        }
    }
}
