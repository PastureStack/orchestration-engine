package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.ProcessInstance;
import io.cattle.platform.core.model.tables.records.ProcessInstanceRecord;
import io.cattle.platform.core.model.tables.records.StoragePoolRecord;
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.junit.Test;

public class AiRegistryLifecycleObserverTest {
    @Test public void serializedNativeStoragePoolExecutionPinsOneExactProcessForLegacyRevisionZero() throws Exception {
        try (Fixture f = new Fixture()) {
            ProcessRecord record = f.capture();
            assertFalse(((List<?>) f.store.execution(f.id).get("initial")).isEmpty());
            assertEquals(0, ((Number) ProcessAuthorization.metadata(record).get("policyRevision")).intValue());
            f.observer.beforeExecution(record, ProcessAuthorization.metadata(record));
            assertEquals(100L, ((Number) f.store.execution(f.id).get("rootProcessId")).longValue());
            f.observer.beforeExecution(record, ProcessAuthorization.metadata(record));
            var duplicate = new ProcessRecord(record, 101L, "server"); f.persist(duplicate);
            assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> f.observer.beforeExecution(duplicate, ProcessAuthorization.metadata(duplicate)));
        }
    }

    @Test public void changedUuidConfigurationKindOrPersistedProcessCannotBorrowTheApprovedRegistryExecution() throws Exception {
        for (String mutation : List.of("uuid", "config", "driver", "kind", "payload", "persisted")) try (Fixture f = new Fixture()) {
            ProcessRecord record = f.capture();
            switch (mutation) {
            case "uuid" -> f.root.setUuid("replacement");
            case "config" -> f.root.setData(Map.of("fields", Map.of("serverAddress", "changed.example")));
            case "driver" -> f.root.setStorageDriverId(8L);
            case "kind" -> f.root.setKind("local");
            case "payload" -> { var data = new LinkedHashMap<>(record.getData()); data.put("secretValue", "PRIVATE"); record.setData(data); f.persist(record); }
            case "persisted" -> when(f.objects.loadResource(ProcessInstance.class, record.getId())).thenReturn(null);
            default -> { }
            }
            assertThrows(mutation, ProcessAuthorizationDeniedException.class,
                    () -> f.observer.beforeExecution(record, ProcessAuthorization.metadata(record)));
        }
    }

    @Test public void nativeAuthorizationDenialProjectsOnlyToTheSameCapturedOperationWithoutPrivateConfiguration() throws Exception {
        try (Fixture f = new Fixture()) {
            ProcessRecord record = f.capture(); f.observer.beforeExecution(record, ProcessAuthorization.metadata(record));
            record.setEndTime(new Date());
            f.observer.afterExecution(record, ProcessAuthorization.metadata(record), ExitReason.AUTHORIZATION_DENIED);
            assertEquals(AiResourceChangePlanStore.AUTHORIZATION_DENIED, f.store.failure(f.id));
            var output = new AiResourceChangePlanService().output(f.store.load(f.id));
            assertEquals(false, output.get("verifiedResult")); assertFalse(output.toString().contains("PRIVATE"));
            assertFalse(output.containsKey("registryConfiguration")); assertFalse(output.containsKey("registryIdentity"));
        }
        try (Fixture f = new Fixture()) {
            ProcessRecord original = f.capture(); f.observer.beforeExecution(original, ProcessAuthorization.metadata(original));
            var other = new ProcessRecord(original, 101L, "server"); other.setEndTime(new Date());
            f.observer.afterExecution(other, ProcessAuthorization.metadata(other), ExitReason.AUTHORIZATION_DENIED);
            assertNull(f.store.failure(f.id));
        }
    }

    @Test public void removalWorkerAttestsTheExactNativeDeleteAndRejectsCredentialSetDrift() throws Exception {
        try (Fixture f = new Fixture("remove")) {
            ProcessRecord record = f.capture(); f.observer.beforeExecution(record, ProcessAuthorization.metadata(record));
            assertEquals(record.getId().longValue(), ((Number) f.store.execution(f.id).get("rootProcessId")).longValue());
            assertFalse(f.store.execution(f.id).toString().contains("PRIVATE"));
            var row = (ProcessInstanceRecord) f.objects.loadResource(ProcessInstance.class, record.getId());
            assertEquals("running", f.observer.registryRemovalOutcome(f.id));
            row.setEndTime(new Date()); row.setResult("SUCCESS"); assertEquals("succeeded", f.observer.registryRemovalOutcome(f.id));
            row.setResourceId("4"); assertEquals("unknown", f.observer.registryRemovalOutcome(f.id));
        }
        for (String drift : List.of("credential", "uuid", "account", "kind")) try (Fixture f = new Fixture("remove")) {
            ProcessRecord record = f.capture();
            switch (drift) {
            case "credential" -> f.connection.createStatement().execute("UPDATE credential SET uuid='replaced' WHERE id=11");
            case "uuid" -> f.root.setUuid("replacement");
            case "account" -> f.root.setAccountId(6L);
            case "kind" -> f.root.setKind("docker");
            default -> throw new AssertionError();
            }
            assertThrows(drift, ProcessAuthorizationDeniedException.class, () -> f.observer.beforeExecution(record, ProcessAuthorization.metadata(record)));
        }
    }

    @Test public void cookieRemovalUsesOnlyItsOwnAtomicNativeInsertWitnessAndNeverBorrowsAKeyGrantOrAnotherProcess() throws Exception {
        try (Fixture f = new Fixture("remove", false)) {
            assertTrue(AiResourceChangePlanStore.sessionLifecycle(f.store.execution(f.id)));
            assertEquals("unknown", f.observer.registryRemovalOutcome(f.id));
            var config = new LaunchConfiguration("storagepool.remove", "storagePool", "3", 5L, 0, Map.of());
            ProcessRecord record = f.observer.dispatch(f.request, f.id, () -> {
                ProcessAuthorization.prepare(config, List.of(f.capture, f.observer));
                return f.observer.createProcess(config, () -> new ProcessRecord(config, 101L, "server"));
            });
            assertTrue(ProcessAuthorization.metadata(record).isEmpty());
            assertEquals(101L, ((Number) f.store.execution(f.id).get("rootProcessId")).longValue());
            f.persist(record); assertEquals("running", f.observer.registryRemovalOutcome(f.id));
            var persisted = (ProcessInstanceRecord) f.objects.loadResource(ProcessInstance.class, 101L);
            persisted.setEndTime(new Date()); persisted.setResult("SUCCESS");
            assertEquals("succeeded", f.observer.registryRemovalOutcome(f.id));
            f.connection.createStatement().execute("UPDATE ai_resource_change_plan SET failure_code='ResourceChangeProcessFailed'");
            assertEquals("unknown", f.observer.registryRemovalOutcome(f.id));
            f.connection.createStatement().execute("UPDATE ai_resource_change_plan SET failure_code=NULL");
            persisted.setResourceId("4"); assertEquals("unknown", f.observer.registryRemovalOutcome(f.id));
            persisted.setResourceId("3"); persisted.setAccountId(6L); assertEquals("unknown", f.observer.registryRemovalOutcome(f.id));
            persisted.setAccountId(5L); persisted.setData(Map.of(ProcessAuthorization.DATA_KEY, Map.of("keyId", "1c7")));
            assertEquals("unknown", f.observer.registryRemovalOutcome(f.id));
        }
        try (Fixture f = new Fixture("remove", false)) {
            var config = new LaunchConfiguration("storagepool.remove", "storagePool", "4", 5L, 0, Map.of());
            assertThrows(IllegalStateException.class, () -> f.observer.dispatch(f.request, f.id, () -> {
                ProcessAuthorization.prepare(config, List.of(f.capture, f.observer));
                return f.observer.createProcess(config, () -> { fail("foreign root must not reach native insert"); return null; });
            }));
            assertFalse(f.store.execution(f.id).containsKey("rootProcessId"));
        }
    }

    private static class Fixture implements AutoCloseable {
        final String operation;
        final String id = "aichange-" + UUID.randomUUID(); final Connection connection;
        final JacksonJsonMapper json = new JacksonJsonMapper(); final AiResourceChangePlanStore store = new AiResourceChangePlanStore();
        final AiResourceChangeProcessObserver observer = new AiResourceChangeProcessObserver();
        final ObjectManager objects = mock(ObjectManager.class); final Policy owner = mock(Policy.class);
        final StoragePoolRecord root = AiRegistryLifecycleChangeTest.registry();
        final ApiKeyProcessAuthorization capture = new ApiKeyProcessAuthorization(); final ApiRequest request = new ApiRequest(null, null);
        Fixture() throws Exception { this("start"); }
        Fixture(String operation) throws Exception { this(operation, true); }
        Fixture(String operation, boolean key) throws Exception {
            this.operation = operation;
            connection = DriverManager.getConnection("jdbc:h2:mem:registry-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
            if ("remove".equals(operation)) { connection.createStatement().execute("CREATE SCHEMA cattle"); connection.setSchema("cattle"); }
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            store.json = json; store.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
            store.transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            if ("remove".equals(operation)) {
                connection.createStatement().execute("CREATE TABLE credential(id BIGINT PRIMARY KEY,uuid VARCHAR(64),account_id BIGINT,registry_id BIGINT,"
                        + "kind VARCHAR(64),state VARCHAR(64),removed TIMESTAMP,public_value VARCHAR(255),secret_value VARCHAR(255))");
                connection.createStatement().execute("INSERT INTO credential"
                        + " VALUES(11,'credential-one',5,3,'registryCredential','active',NULL,'PRIVATE_USER','PRIVATE_SECRET')");
            }
            observer.store = store; observer.objects = objects; observer.json = json;
            var targets = new ApiKeyTargetResolver(); observer.targets = targets;
            var schemas = mock(SchemaFactory.class); when(schemas.getBaseType(anyString())).thenAnswer(call ->
                    "registry".equals(call.getArgument(0)) ? "storagePool" : call.getArgument(0));
            var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("storagePool", "sp", "registry", "sp", "credential", "c"));
            for (var entry : Map.of("objectManager", objects, "idFormatter", formatter).entrySet()) {
                var field = ApiKeyTargetResolver.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(targets, entry.getValue());
            }
            var targetField = ApiKeyProcessAuthorization.class.getDeclaredField("targets"); targetField.setAccessible(true); targetField.set(capture, targets);
            when(owner.getAccountId()).thenReturn(5L); when(owner.getAuthenticatedAsAccountId()).thenReturn(10L);
            when(objects.loadResource("storagePool", 3L)).thenReturn(root); when(objects.loadResource("storagePool", "3")).thenReturn(root);
            var plan = new LinkedHashMap<String, Object>(Map.of("id", id, "actorId", 10L, "accountId", 5L, "credentialId", 7L,
                    "credentialRevision", 0L, "credentialKind", "apiKey", "resourceType", "registry", "operation", operation, "status", "preview"));
            if (!key) {
                for (String field : List.of("credentialId", "credentialRevision", "credentialKind")) plan.remove(field);
                plan.put("body", Map.of());
            }
            plan.put("internalResourceId", 3L); plan.put("resourceId", "1sp3"); plan.put("expiresAt", "2030-01-01T00:00:00Z");
            plan.put("parameters", Map.of()); plan.put("confirmationState", "approved");
            plan.put("snapshot", json.readValue(json.writeValueAsString(AiRegistryLifecycleChange.snapshot(root))));
            if ("remove".equals(operation)) ((Map<String, Object>) plan.get("snapshot")).put(AiRegistryRemovalChange.CREDENTIALS,
                    AiRegistryRemovalChange.credentials(org.jooq.impl.DSL.using(connection, SQLDialect.H2), 3, 5, true, List.of()));
            store.insert(plan); plan.put("status", "dispatching"); plan.put("operationId", id); assertTrue(store.claim(plan));
            request.setType("registry"); request.setId("3"); request.setMethod("remove".equals(operation) ? "DELETE" : "POST");
            request.setAction("remove".equals(operation) ? null : "activate"); request.setRequestObject(Map.of());
            ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setApiRequest(request); ApiContext.getContext().setIdFormatter(formatter);
            if (key) ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 10, "apiKey", 0, null));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.keyId", "1c7");
            request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.operation", "remove".equals(operation) ? "delete" : "update");
        }
        ProcessRecord capture() {
            var nativeRoot = new LaunchConfiguration("remove".equals(operation) ? "storagepool.remove" : "storagepool.activate",
                    "storagePool", "3", 5L, 0, Map.of());
            observer.dispatch(request, id, () -> { ProcessAuthorization.prepare(nativeRoot, List.of(capture, observer)); return null; });
            var record = new ProcessRecord(nativeRoot, 100L, "server");
            try { record.setData(json.readValue(json.writeValueAsString(record.getData()))); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
            persist(record); return record;
        }
        void persist(ProcessRecord record) {
            var row = new ProcessInstanceRecord(); row.setId(record.getId()); row.setAccountId((Long) record.getAccountId()); row.setData(record.getData());
            row.setProcessName(record.getProcessName()); row.setResourceType(record.getResourceType()); row.setResourceId(record.getResourceId());
            when(objects.loadResource(ProcessInstance.class, record.getId())).thenReturn(row);
        }
        @Override public void close() throws Exception { connection.close(); ApiContext.remove(); }
    }
}
