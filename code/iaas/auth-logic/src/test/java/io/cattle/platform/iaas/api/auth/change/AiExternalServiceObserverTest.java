package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
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
import io.cattle.platform.object.util.DataAccessor;
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

/** The endpoint owner is a Service, but its managed map has no Instance. */
public class AiExternalServiceObserverTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void externalCreationAttestsTheNativeServiceAndRejectsAnOrdinaryServiceOfTheSameId() throws Exception {
        try (Fixture f = new Fixture("create")) {
            f.service.setState("requested"); f.service.setKind("service");
            f.capture(); assertNull(f.store.execution(f.id).get("rootUuid"));
            f.service.setKind("externalService"); f.capture();
            assertEquals("service-uuid", f.store.execution(f.id).get("rootUuid"));
            assertEquals(7L, ((Number) f.store.execution(f.id).get("rootId")).longValue());
        }
    }

    @Test public void retiredEndpointDenialWinsOverALateSuccessfulReceiptWithoutRedispatch() throws Exception {
        for (String operation : List.of("stop", "update_targets", "remove")) try (Fixture f = new Fixture(operation)) {
            f.capture(); ProcessRecord child = f.child("serviceexposemap.remove");
            f.map.setRemoved(new Date(1)); f.map.setState("removed");
            if ("remove".equals(operation)) { f.service.setRemoved(new Date(1)); f.service.setState("removed"); }
            f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            assertEquals(operation, AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.store.failure(f.id));
            f.acknowledge(); var reader = f.reader(); Map<String, Object> result = reader.get(f.id, f.receipt());
            assertEquals("failed", result.get("status")); assertEquals(false, result.get("verifiedResult"));
            assertEquals(f.id, result.get("operationId")); assertFalse(result.containsKey("credentialId"));
            assertFalse(result.toString().contains("db.test"));
            Map<String, Object> stale = new LinkedHashMap<>(f.plan); stale.put("status", "succeeded"); stale.put("verifiedResult", true);
            f.store.save(stale); assertEquals("failed", stale.get("status")); assertFalse(f.store.claim(stale));
            verify(reader.domain, never()).complete(any(), any(), anyMap(), anyMap());
        }
    }

    @Test public void newlyCreatedEndpointDenialRequiresTheActualCurrentHostnameOrIp() throws Exception {
        for (String endpoint : List.of("db.test", "192.0.2.9")) try (Fixture f = new Fixture("start")) {
            when(f.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of());
            if (endpoint.contains(":" ) || Character.isDigit(endpoint.charAt(0))) {
                f.map.setHostName(null); f.map.setIpAddress(endpoint);
                DataAccessor.fields(f.service).withKey("hostname").set(null);
                DataAccessor.fields(f.service).withKey("externalIpAddresses").set(List.of(endpoint));
            }
            f.capture(); ProcessRecord child = f.child("serviceexposemap.create");
            f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.store.failure(f.id));
        }
    }

    @Test public void crossMapReplacedUuidWrongKindEndpointInstanceOrRevisionCannotPoisonThePlan() throws Exception {
        for (String mutation : List.of("uuid", "owner", "service", "stack", "kind", "endpoint", "instance",
                "unmanaged", "upgrade", "key", "revision", "proof")) {
            try (Fixture f = new Fixture("stop")) {
                f.capture(); ProcessRecord child = f.child("serviceexposemap.remove");
                switch (mutation) {
                case "uuid" -> f.map.setUuid("replacement");
                case "owner" -> f.map.setAccountId(99L);
                case "service" -> f.map.setServiceId(99L);
                case "stack" -> f.service.setStackId(99L);
                case "kind" -> f.service.setKind("service");
                case "endpoint" -> f.map.setHostName("different.test");
                case "instance" -> f.map.setInstanceId(99L);
                case "unmanaged" -> f.map.setManaged(false);
                case "upgrade" -> f.map.setUpgrade(true);
                case "proof" -> child.getData().remove(ProcessAuthorization.VERIFIED_CHILD_KEY);
                default -> {
                    Map<String, Object> metadata = new LinkedHashMap<>(ProcessAuthorization.metadata(child));
                    metadata.put(mutation.equals("key") ? "keyId" : "policyRevision", mutation.equals("key") ? "1c99" : 99);
                    child.getData().put(ProcessAuthorization.DATA_KEY, metadata);
                }
                }
                f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
                assertNull(mutation, f.store.failure(f.id));
            }
        }
        try (Fixture f = new Fixture("start")) {
            f.capture(); f.map.setHostName("different.test"); ProcessRecord child = f.child("serviceexposemap.create");
            f.observer.afterExecution(child, ProcessAuthorization.metadata(child), ExitReason.AUTHORIZATION_DENIED);
            assertNull(f.store.failure(f.id));
        }
    }

    private static class Fixture implements AutoCloseable {
        final String id = "aichange-" + UUID.randomUUID(); final Connection connection;
        final JacksonJsonMapper json = new JacksonJsonMapper(); final AiResourceChangePlanStore store = new AiResourceChangePlanStore();
        final ObjectManager objects = mock(ObjectManager.class); final AiResourceChangeProcessObserver observer = new AiResourceChangeProcessObserver();
        final ApiKeyProcessAuthorization captureAuthorization = new ApiKeyProcessAuthorization();
        final ServiceRecord service = new ServiceRecord(); final ServiceExposeMapRecord map = new ServiceExposeMapRecord();
        final ApiRequest request = new ApiRequest(null, null); final Policy owner = mock(Policy.class);
        final Map<String, Object> plan = new LinkedHashMap<>(); final String operation; LaunchConfiguration nativeRoot;

        Fixture(String operation) throws Exception {
            this.operation = operation;
            connection = DriverManager.getConnection("jdbc:h2:mem:external-outcome-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            store.json = json; store.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
            observer.store = store; observer.objects = objects; observer.targets = new ApiKeyTargetResolver();
            var schemas = mock(SchemaFactory.class); when(schemas.getBaseType("externalService")).thenReturn("service");
            var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            var field = ApiKeyTargetResolver.class.getDeclaredField("idFormatter"); field.setAccessible(true); field.set(observer.targets, formatter);
            var captureTargets = ApiKeyProcessAuthorization.class.getDeclaredField("targets");
            captureTargets.setAccessible(true); captureTargets.set(captureAuthorization, observer.targets);
            when(owner.getAccountId()).thenReturn(77L); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L);
            service.setId(7L); service.setUuid("service-uuid"); service.setKind("externalService"); service.setAccountId(77L);
            service.setStackId(3L); service.setState("active"); DataAccessor.fields(service).withKey("hostname").set("db.test");
            map.setId(2L); map.setUuid("endpoint-uuid"); map.setAccountId(77L); map.setServiceId(7L);
            map.setManaged(true); map.setUpgrade(false); map.setState("active"); map.setHostName("db.test");
            when(objects.loadResource("service", 7L)).thenReturn(service); when(objects.loadResource("service", "7")).thenReturn(service);
            when(objects.loadResource(Service.class, 7L)).thenReturn(service);
            when(objects.loadResource("serviceExposeMap", "2")).thenReturn(map);
            when(objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(map));
            plan.putAll(Map.of("id", id, "actorId", 42L, "accountId", 77L, "credentialId", 7L, "credentialRevision", 3L,
                    "credentialKind", "apiKeyRestricted", "resourceType", "externalService", "operation", operation, "status", "preview"));
            plan.put("internalResourceId", operation.equals("create") ? null : 7L); plan.put("resourceId", "1s7");
            plan.put("expiresAt", "2030-01-01T00:00:00Z"); plan.put("parameters", Map.of()); plan.put("snapshot", Map.of());
            plan.put("confirmationState", "approved"); store.insert(plan); plan.put("status", "dispatching"); plan.put("operationId", id);
            plan.put("acceptedAt", 0L); assertTrue(store.claim(plan));
            var descriptor = AiResourceChangeDescriptor.require("externalService", operation);
            request.setType("externalService"); request.setMethod(descriptor.method()); request.setId(operation.equals("create") ? null : "7");
            request.setAction(descriptor.action()); ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setApiRequest(request);
            ApiContext.getContext().setIdFormatter(formatter);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3,
                    new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE)));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.keyId", "1c7"); request.setAttribute("apiKey.audit.operation", descriptor.keyOperation());
        }

        void capture() {
            String suffix = request.getAction() == null ? request.getMethod().equals("PUT") ? "update"
                    : request.getMethod().equals("DELETE") ? "remove" : "create" : request.getAction();
            nativeRoot = new LaunchConfiguration("service." + suffix, "service", "7", 77L, 0, Map.of());
            observer.dispatch(request, id, () -> {
                ProcessAuthorization.prepare(nativeRoot, List.of(captureAuthorization, observer)); return null;
            });
        }
        ProcessRecord child(String process) throws Exception {
            var engine = EngineContext.getEngineContext(); var binding = ProcessAuthorization.metadata(nativeRoot);
            engine.pushAuthorization(binding); var parent = engine.pushVerifiedExecution("service", "7", binding);
            var launch = new LaunchConfiguration(process, "serviceExposeMap", "2", 77L, 0, Map.of());
            try { ProcessAuthorization.prepare(launch, List.of()); }
            finally { engine.popVerifiedExecution(parent); engine.popAuthorization(); }
            var record = new ProcessRecord(launch, 102L, "server"); record.setEndTime(new Date());
            record.setData(json.readValue(json.writeValueAsString(record.getData()))); return record;
        }
        void acknowledge() { plan.put("dispatchAcknowledged", true); plan.put("status", "accepted"); store.save(plan); }
        ApiRequest receipt() { var result = new ApiRequest(request); result.setType("aiResourceChangePlan"); result.setId(id); return result; }
        AiResourceChangePlanService reader() {
            var result = new AiResourceChangePlanService(); result.store = store; result.domain = mock(AiResourceChangeDomain.class);
            when(result.domain.actor(any())).thenReturn(owner); when(result.domain.receiptTarget(any(), any(), anyMap())).thenReturn(service);
            when(result.domain.complete(any(), any(), anyMap(), anyMap())).thenReturn(true);
            result.auditSinks = List.of(mock(ApiKeyAuditSink.class)); result.clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
            result.locks = mock(LockManager.class);
            when(result.locks.lock(any(), any())).thenAnswer(call -> ((LockCallback<?>) call.getArgument(1)).doWithLock()); return result;
        }
        @Override public void close() throws Exception { connection.close(); }
    }
}
