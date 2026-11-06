package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.ProcessInstance;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ProcessInstanceRecord;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.ExitReason;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
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

public class AiContainerStartFailureObserverTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void exactCanceledRootReportsFixedFailureWithoutInferringItFromStoppedState() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals("stopped", f.instance.getState());
            assertNull(f.store.failure(f.id));
            f.bind();
            assertEquals(101L, ((Number) f.store.execution(f.id).get("rootProcessId")).longValue());
            f.record.setEndTime(new Date());
            f.observer.afterExecution(f.record, f.metadata(), ExitReason.CANCELED);
            assertEquals(AiResourceChangePlanStore.PROCESS_FAILED, f.store.failure(f.id));
            Map<String, Object> receipt = f.reader().get(f.id, f.receipt());
            assertEquals("failed", receipt.get("status")); assertEquals(false, receipt.get("verifiedResult"));
            assertEquals(AiResourceChangePlanStore.PROCESS_FAILED, receipt.get("messageCode"));
            assertEquals(f.id, receipt.get("operationId"));
            assertFalse(receipt.containsKey(AiResourceChangePlanStore.PRIVATE_FAILURE));
            assertFalse(receipt.toString().contains("private-native-error"));
            f.observer.afterExecution(f.record, f.metadata(), ExitReason.CANCELED);
            assertEquals(AiResourceChangePlanStore.PROCESS_FAILED, f.store.failure(f.id));
        }
    }

    @Test public void retriesInflightAndChildStopCancellationNeverBecomeStartFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            f.bind();
            for (ExitReason reason : List.of(ExitReason.RETRY_EXCEPTION, ExitReason.UNKNOWN_EXCEPTION,
                    ExitReason.TIMEOUT, ExitReason.DELAY, ExitReason.STATE_CHANGED, ExitReason.RESOURCE_BUSY,
                    ExitReason.AUDIT_PENDING, ExitReason.PROCESS_ALREADY_IN_PROGRESS, ExitReason.SCHEDULED,
                    ExitReason.DONE, ExitReason.CHAIN, ExitReason.ALREADY_DONE)) {
                f.observer.afterExecution(f.record, f.metadata(), reason);
                assertNull(reason.name(), f.store.failure(f.id));
            }
            LaunchConfiguration child = new LaunchConfiguration("instance.stop", "instance", "10", 77L, 0,
                    Map.of(ProcessAuthorization.DATA_KEY, f.metadata()));
            ProcessRecord stop = new ProcessRecord(child, 102L, "server"); stop.setEndTime(new Date());
            f.observer.afterExecution(stop, ProcessAuthorization.metadata(stop), ExitReason.CANCELED);
            assertNull(f.store.failure(f.id));
        }
        try (Fixture f = new Fixture()) {
            f.record.setEndTime(new Date()); // A terminal row without the privately attested root is insufficient.
            f.observer.afterExecution(f.record, f.metadata(), ExitReason.CANCELED);
            assertNull(f.store.failure(f.id));
        }
    }

    @Test public void foreignIdentityMetadataAndDuplicateRootCannotPoisonTheReceipt() throws Exception {
        for (String mutation : List.of("uuid", "kind", "project", "persisted-target", "revision", "key", "actor")) {
            try (Fixture f = new Fixture()) {
                if (mutation.equals("uuid")) f.instance.setUuid("different-instance");
                if (mutation.equals("kind")) f.instance.setKind("service");
                if (mutation.equals("project")) f.instance.setAccountId(99L);
                if (mutation.equals("persisted-target")) f.persisted.setResourceId("99");
                if (Set.of("revision", "key", "actor").contains(mutation)) {
                    Map<String, Object> altered = new LinkedHashMap<>(f.metadata());
                    altered.put(mutation.equals("revision") ? "policyRevision" : mutation.equals("key") ? "keyId" : "principalAccountId",
                            mutation.equals("key") ? "1c99" : 99L);
                    f.record.getData().put(ProcessAuthorization.DATA_KEY, altered);
                    f.persisted.setData(f.record.getData());
                }
                assertThrows(mutation, IllegalStateException.class,
                        () -> f.observer.beforeExecution(f.record, f.metadata()));
                assertFalse(f.store.execution(f.id).containsKey("rootProcessId"));
                f.record.setEndTime(new Date());
                f.observer.afterExecution(f.record, f.metadata(), ExitReason.CANCELED);
                assertNull(mutation, f.store.failure(f.id));
            }
        }
        try (Fixture f = new Fixture()) {
            f.bind();
            ProcessRecord duplicate = new ProcessRecord(new LaunchConfiguration("instance.start", "instance", "10", 77L, 0,
                    f.record.getData()), 103L, "server");
            ProcessInstanceRecord other = f.persisted(103L); when(f.objects.loadResource(ProcessInstance.class, 103L)).thenReturn(other);
            assertThrows(IllegalStateException.class, () -> f.observer.beforeExecution(duplicate, ProcessAuthorization.metadata(duplicate)));
            duplicate.setEndTime(new Date()); f.observer.afterExecution(duplicate, ProcessAuthorization.metadata(duplicate), ExitReason.CANCELED);
            assertEquals(101L, ((Number) f.store.execution(f.id).get("rootProcessId")).longValue());
            assertNull(f.store.failure(f.id));
        }
    }

    @Test public void fixedFailureWinsLateSuccessSaveAndAuthorizationDenialRemainsMoreSpecific() throws Exception {
        try (Fixture f = new Fixture()) {
            f.bind(); f.record.setEndTime(new Date());
            f.observer.afterExecution(f.record, f.metadata(), ExitReason.CANCELED);
            Map<String, Object> late = new LinkedHashMap<>(f.plan); late.put("status", "succeeded"); late.put("verifiedResult", true);
            f.store.save(late); assertEquals("failed", late.get("status")); assertEquals(false, late.get("verifiedResult"));
            f.observer.afterExecution(f.record, f.metadata(), ExitReason.AUTHORIZATION_DENIED);
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.store.failure(f.id));
            f.observer.afterExecution(f.record, f.metadata(), ExitReason.CANCELED);
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.store.failure(f.id));
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.reader().get(f.id, f.receipt()).get("messageCode"));
        }
        try (Fixture f = new Fixture()) {
            f.bind(); f.record.setEndTime(new Date());
            f.observer.afterExecution(f.record, f.metadata(), ExitReason.AUTHORIZATION_DENIED);
            f.observer.afterExecution(f.record, f.metadata(), ExitReason.CANCELED);
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.store.failure(f.id));
        }
    }

    @Test public void successStillRequiresOriginalFreshReadbackAndOrdinaryNativeStartIsUnchanged() throws Exception {
        try (Fixture f = new Fixture()) {
            f.bind(); f.observer.afterExecution(f.record, f.metadata(), ExitReason.DONE);
            assertNull(f.store.failure(f.id)); assertEquals("running", f.reader().get(f.id, f.receipt()).get("status"));
            f.instance.setState("running");
            Map<String, Object> result = f.reader().get(f.id, f.receipt());
            assertEquals("succeeded", result.get("status")); assertEquals(true, result.get("verifiedResult"));
            Map<String, Object> ordinary = new LinkedHashMap<>(f.metadata()); ordinary.put("requestId", UUID.randomUUID().toString());
            f.record.getData().put(ProcessAuthorization.DATA_KEY, ordinary);
            f.observer.beforeExecution(f.record, ordinary);
        }
    }

    private static class Fixture implements AutoCloseable {
        final String id = "aichange-" + UUID.randomUUID();
        final Connection connection;
        final JacksonJsonMapper json = new JacksonJsonMapper();
        final AiResourceChangePlanStore store = new AiResourceChangePlanStore();
        final AiResourceChangeProcessObserver observer = new AiResourceChangeProcessObserver();
        final ObjectManager objects = mock(ObjectManager.class);
        final ApiKeyTargetResolver targets = new ApiKeyTargetResolver();
        final Policy owner = mock(Policy.class);
        final InstanceRecord instance = new InstanceRecord();
        final Map<String, Object> plan = new LinkedHashMap<>();
        final ApiRequest request = new ApiRequest(null, null);
        final ProcessRecord record;
        final ProcessInstanceRecord persisted;

        Fixture() throws Exception {
            connection = DriverManager.getConnection("jdbc:h2:mem:container-start-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            store.json = json; store.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
            observer.store = store; observer.objects = objects; observer.targets = targets;
            SchemaFactory schemas = mock(SchemaFactory.class); when(schemas.getBaseType("container")).thenReturn("instance");
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas); formatter.setTypeMappings(Map.of());
            var field = targets.getClass().getDeclaredField("idFormatter"); field.setAccessible(true); field.set(targets, formatter);
            when(owner.getAccountId()).thenReturn(77L); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L);
            instance.setId(10L); instance.setAccountId(77L); instance.setKind("container");
            instance.setUuid("same-instance-uuid"); instance.setState("stopped");
            when(objects.loadResource("instance", 10L)).thenReturn(instance); when(objects.loadResource("instance", "10")).thenReturn(instance);
            when(objects.loadResource(Instance.class, 10L)).thenReturn(instance);
            plan.putAll(Map.of("id", id, "actorId", 42L, "accountId", 77L, "credentialId", 7L, "credentialRevision", 3L,
                    "credentialKind", "apiKeyRestricted", "resourceType", "container", "operation", "start", "status", "preview"));
            plan.put("internalResourceId", 10L); plan.put("resourceId", "1i10"); plan.put("expiresAt", "2030-01-01T00:00:00Z");
            plan.put("parameters", Map.of()); plan.put("snapshot", Map.of()); plan.put("confirmationState", "approved");
            store.insert(plan); plan.put("status", "dispatching"); plan.put("operationId", id); plan.put("acceptedAt", 0L);
            assertTrue(store.claim(plan));
            var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.DENY, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
            request.setType("container"); request.setMethod("POST"); request.setId("10"); request.setAction("start");
            ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setApiRequest(request); ApiContext.getContext().setIdFormatter(formatter);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, policy));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.keyId", "1c7"); request.setAttribute("apiKey.audit.operation", "update");
            var capture = new ApiKeyProcessAuthorization();
            LaunchConfiguration config = new LaunchConfiguration("instance.start", "instance", "10", 77L, 0, Map.of("diagnostic", "private-native-error"));
            observer.dispatch(request, id, () -> { ProcessAuthorization.prepare(config, List.of(capture, observer)); return null; });
            record = new ProcessRecord(config, 101L, "server"); record.setData(json.readValue(json.writeValueAsString(record.getData())));
            persisted = persisted(101L); when(objects.loadResource(ProcessInstance.class, 101L)).thenReturn(persisted);
            plan.put("dispatchAcknowledged", true); plan.put("status", "accepted"); store.save(plan);
        }
        ProcessInstanceRecord persisted(long processId) {
            var row = new ProcessInstanceRecord(); row.setId(processId); row.setAccountId(77L); row.setResourceType("instance");
            row.setResourceId("10"); row.setProcessName("instance.start"); row.setData(record.getData()); return row;
        }
        Map<String, Object> metadata() { return ProcessAuthorization.metadata(record); }
        void bind() { observer.beforeExecution(record, metadata()); }
        ApiRequest receipt() {
            var value = new ApiRequest(request); value.setType("aiResourceChangePlan"); value.setId(id); value.setMethod("GET"); value.setAction(null);
            ApiKeyCredentialContext.attach(value, ApiKeyCredentialContext.get(request)); return value;
        }
        AiResourceChangePlanService reader() {
            var value = new AiResourceChangePlanService(); value.store = store; value.domain = mock(AiResourceChangeDomain.class);
            when(value.domain.actor(any())).thenReturn(owner); when(value.domain.receiptTarget(any(), any(), anyMap())).thenReturn(instance);
            when(value.domain.complete(any(), any(), anyMap(), anyMap())).thenAnswer(call -> "running".equals(instance.getState()));
            value.locks = mock(LockManager.class);
            when(value.locks.lock(any(), any())).thenAnswer(call -> ((LockCallback<?>) call.getArgument(1)).doWithLock());
            value.auditSinks = List.of(mock(ApiKeyAuditSink.class)); value.clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC); return value;
        }
        @Override public void close() throws Exception { connection.close(); }
    }
}
