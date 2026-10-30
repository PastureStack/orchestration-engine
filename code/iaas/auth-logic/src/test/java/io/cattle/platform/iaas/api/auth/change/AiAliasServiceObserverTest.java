package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.*;
import io.cattle.platform.iaas.api.auth.apikey.*;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.junit.After;
import org.junit.Test;

public class AiAliasServiceObserverTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void nativeSetLinksAndRealServiceRemovalProjectOnlyTheSameBoundAliasOperationBeforeAcknowledgement() throws Exception {
        for (String operation : List.of("update_targets", "remove")) try (Fixture f = new Fixture(operation)) {
            ProcessRecord child = f.child();
            assertEquals(operation.equals("update_targets"), ProcessAuthorization.executionBound(child));
            if (operation.equals("remove")) assertFalse(ProcessAuthorization.verifiedChild(child).references().isEmpty());
            f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.store.failure(f.id));
            assertFalse(Boolean.TRUE.equals(f.store.load(f.id).get("dispatchAcknowledged")));
            Map<String, Object> result = new AiResourceChangePlanService().output(f.store.load(f.id));
            assertEquals("failed", result.get("status")); assertEquals(false, result.get("verifiedResult"));
            assertEquals(f.id, result.get("operationId")); assertNull(result.get("dispatchAcknowledged"));
            assertFalse(result.toString().contains("PRIVATE_SENTINEL"));
        }
    }

    @Test public void foreignMapReplacementParentKindStackOrPayloadCannotPoisonTheAliasReceipt() throws Exception {
        for (String invalid : List.of("map", "target", "parent", "kind", "stack-uuid", "map-uuid", "cancel", "proof"))
            try (Fixture f = new Fixture(invalid.equals("proof") ? "remove" : "update_targets")) {
                ProcessRecord child = f.child();
                switch (invalid) {
                case "map" -> f.map.setServiceId(99L);
                case "target" -> f.target.setUuid("replacement");
                case "parent" -> f.target.setStackId(8L);
                case "kind" -> f.alias.setKind("service");
                case "stack-uuid" -> f.targetStack.setUuid("replacement-stack");
                case "map-uuid" -> f.map.setUuid("replacement-map");
                case "proof" -> {
                    Map<String, Object> proof = new LinkedHashMap<>((Map<String, Object>) child.getData().get(ProcessAuthorization.VERIFIED_CHILD_KEY));
                    proof.put("references", Map.of("aliasMapUuid", "fake")); child.getData().put(ProcessAuthorization.VERIFIED_CHILD_KEY, proof);
                }
                default -> { }
                }
                f.observer.afterExecution(child, ProcessAuthorization.metadata(child),
                        invalid.equals("cancel") ? ExitReason.CANCELED : ExitReason.AUTHORIZATION_DENIED);
                assertNull(invalid, f.store.failure(f.id)); assertEquals("dispatching", f.store.load(f.id).get("status"));
            }
    }

    @Test public void nativeRemovalDeniedBeforeReferenceBindingStillObservesOnlyTheOriginalPinnedMapAndStack() throws Exception {
        for (String mutation : List.of("none", "map", "stack")) try (Fixture f = new Fixture("remove")) {
            ProcessRecord child = f.child(false);
            assertTrue(ProcessAuthorization.verifiedChild(child).references().isEmpty());
            if (mutation.equals("map")) f.map.setUuid("replacement");
            if (mutation.equals("stack")) {
                StackRecord stack = (StackRecord) f.objects.loadResource(Stack.class, 8L); stack.setUuid("replacement");
            }
            f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            if (mutation.equals("none")) {
                Map<String, Object> result = new AiResourceChangePlanService().output(f.store.load(f.id));
                assertEquals("failed", result.get("status")); assertEquals(f.id, result.get("operationId"));
                assertEquals(false, result.get("verifiedResult")); assertFalse(result.containsKey("referenceSnapshot"));
            } else assertNull(mutation, f.store.failure(f.id));
        }
    }

    private static class Fixture implements AutoCloseable {
        final String id = "aichange-" + UUID.randomUUID(); final Connection connection;
        final JacksonJsonMapper json = new JacksonJsonMapper(); final AiResourceChangePlanStore store = new AiResourceChangePlanStore();
        final AiResourceChangeProcessObserver observer = new AiResourceChangeProcessObserver();
        final ApiKeyTargetResolver targets = new ApiKeyTargetResolver(); final ApiKeyProcessAuthorization hook = new ApiKeyProcessAuthorization();
        final ObjectManager objects = mock(ObjectManager.class); final Policy owner = mock(Policy.class);
        final ServiceRecord alias = new ServiceRecord(), target = new ServiceRecord(); final ServiceConsumeMapRecord map = new ServiceConsumeMapRecord();
        final StackRecord targetStack = new StackRecord(); final ApiRequest request = new ApiRequest(null, null);
        final String operation;
        Fixture(String operation) throws Exception {
            this.operation = operation;
            connection = DriverManager.getConnection("jdbc:h2:mem:alias-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            store.json = json; store.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
            observer.store = store; observer.objects = objects; observer.targets = targets;
            SchemaFactory schemas = mock(SchemaFactory.class); when(schemas.getBaseType("dnsService")).thenReturn("service");
            var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("service", "s", "serviceConsumeMap", "scm", "stack", "st", "credential", "c"));
            inject(targets, "objectManager", objects); inject(targets, "idFormatter", formatter); inject(hook, "targets", targets);
            when(owner.getAccountId()).thenReturn(5L); when(owner.getAuthenticatedAsAccountId()).thenReturn(10L);
            alias.setId(2L); alias.setUuid("alias"); alias.setAccountId(5L); alias.setStackId(8L); alias.setKind("dnsService"); alias.setState("inactive");
            target.setId(9L); target.setUuid("target"); target.setAccountId(5L); target.setStackId(9L); target.setKind("service"); target.setState("active");
            map.setId(7L); map.setUuid("map"); map.setAccountId(5L); map.setServiceId(2L); map.setConsumedServiceId(9L);
            map.setState(operation.equals("remove") ? "active" : "requested");
            var stack = new StackRecord(); stack.setId(8L); stack.setUuid("stack"); stack.setAccountId(5L); stack.setState("active");
            targetStack.setId(9L); targetStack.setUuid("target-stack"); targetStack.setAccountId(5L); targetStack.setState("active");
            when(objects.loadResource(Service.class, 2L)).thenReturn(alias); when(objects.loadResource(Service.class, 9L)).thenReturn(target);
            when(objects.loadResource("service", 2L)).thenReturn(alias); when(objects.loadResource("service", "2")).thenReturn(alias);
            when(objects.loadResource(Stack.class, 8L)).thenReturn(stack); when(objects.loadResource(Stack.class, 9L)).thenReturn(targetStack);
            when(objects.loadResource(io.cattle.platform.core.model.ServiceConsumeMap.class, 7L)).thenReturn(map);
            when(objects.loadResource("serviceConsumeMap", "7")).thenReturn(map);
            Map<String, Object> ref = Map.of("id", 9L, "uuid", "target", "accountId", 5L, "stackId", 9L,
                    "stackUuid", "target-stack", "kind", "service");
            Map<String, Object> edge = Map.of("id", 7L, "uuid", "map", "accountId", 5L, "serviceId", 2L,
                    "consumedServiceId", 9L, "consumedUuid", "target");
            Map<String, Object> plan = new LinkedHashMap<>(Map.of("id", id, "actorId", 10L, "accountId", 5L,
                    "credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted", "resourceType", "dnsService",
                    "operation", operation, "status", "preview"));
            plan.put("internalResourceId", 2L); plan.put("resourceId", "1s2"); plan.put("expiresAt", "2030-01-01T00:00:00Z");
            plan.put("confirmationState", "approved"); plan.put("parameters", operation.equals("remove") ? Map.of() : Map.of("serviceIds", List.of("1s9")));
            plan.put("snapshot", Map.of("aliasLinks", operation.equals("remove") ? List.of(edge) : List.of()));
            plan.put("referenceSnapshot", Map.of("before", operation.equals("remove") ? List.of(ref) : List.of(),
                    "after", operation.equals("remove") ? List.of() : List.of(ref)));
            store.insert(plan); plan.put("status", "dispatching"); plan.put("operationId", id); assertTrue(store.claim(plan));
            request.setType("dnsService"); request.setId("2"); request.setMethod(operation.equals("remove") ? "DELETE" : "POST");
            request.setAction(operation.equals("remove") ? null : "setservicelinks");
            ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setApiRequest(request); ApiContext.getContext().setIdFormatter(formatter);
            var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of());
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 10, "apiKeyRestricted", 3, policy));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.keyId", "1c7");
            request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.operation", operation.equals("remove") ? "delete" : "update");
        }
        ProcessRecord child() throws Exception { return child(true); }
        ProcessRecord child(boolean bind) throws Exception {
            var nativeMap = new LaunchConfiguration("serviceconsumemap." + (operation.equals("remove") ? "remove" : "create"),
                    "serviceConsumeMap", "7", 5L, 0, Map.of("detail", "PRIVATE_SENTINEL"));
            ProcessRecord record;
            if (operation.equals("remove")) {
                var root = new LaunchConfiguration("service.remove", "service", "2", 5L, 0, Map.of());
                observer.dispatch(request, id, () -> { ProcessAuthorization.prepare(root, List.of(hook, observer)); return null; });
                var engine = EngineContext.getEngineContext(); var metadata = ProcessAuthorization.metadata(root);
                engine.pushAuthorization(metadata); var frame = engine.pushVerifiedExecution("service", "2", metadata);
                try {
                    ProcessAuthorization.prepare(nativeMap, List.of());
                    record = new ProcessRecord(nativeMap, 102L, "server");
                    if (bind) ProcessAuthorization.bindVerifiedChildReferences(record, targets.aliasSchedulingReferences(record,
                            ProcessAuthorization.metadata(record), owner));
                } finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
                map.setRemoved(new Date(1)); map.setState("removed"); alias.setRemoved(new Date(1)); alias.setState("removed");
            } else {
                observer.dispatch(request, id, () -> { ProcessAuthorization.prepare(nativeMap, List.of(hook, observer)); return null; });
                record = new ProcessRecord(nativeMap, 102L, "server");
            }
            record.setData(json.readValue(json.writeValueAsString(record.getData())));
            record.setEndTime(new Date()); return record;
        }
        @Override public void close() throws Exception { connection.close(); }
        private static void inject(Object target, String name, Object value) throws Exception {
            var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
        }
    }
}
