package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.ProcessInstance;
import io.cattle.platform.core.model.tables.records.ProcessInstanceRecord;
import io.cattle.platform.core.model.tables.records.HostRecord;
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

public class AiHostLifecycleObserverTest {
    @Test public void serializedNativeHostExecutionPinsOneExactProcessForLegacyRevisionZero() throws Exception {
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

    @Test public void changedUuidConfigurationKindOrPersistedProcessCannotBorrowTheApprovedHostExecution() throws Exception {
        for (String mutation : List.of("uuid", "config", "driver", "kind", "payload", "persisted",
                "operation", "process", "target", "project")) try (Fixture f = new Fixture()) {
            ProcessRecord record = f.capture();
            switch (mutation) {
            case "uuid" -> f.root.setUuid("replacement");
            case "config" -> f.root.setData(Map.of("fields", Map.of("serverAddress", "changed.example")));
            case "driver" -> f.root.setZoneId(8L);
            case "kind" -> f.root.setKind("other");
            case "payload" -> { var data = new LinkedHashMap<>(record.getData()); data.put("secretValue", "PRIVATE"); record.setData(data); f.persist(record); }
            case "persisted" -> when(f.objects.loadResource(ProcessInstance.class, record.getId())).thenReturn(null);
            case "operation" -> { var before = f.store.execution(f.id); var after = new LinkedHashMap<>(before);
                after.put("operation", "update_metadata"); assertTrue(f.store.attest(f.id, before, after)); }
            case "process" -> { record = new ProcessRecord(new LaunchConfiguration("host.evacuate", "host", "3", 5L, 0, record.getData()),
                    100L, "server"); f.persist(record); }
            case "target" -> f.root.setId(4L);
            case "project" -> f.project.setUuid("replacement");
            default -> { }
            }
            ProcessRecord selected = record;
            assertThrows(mutation, ProcessAuthorizationDeniedException.class,
                    () -> f.observer.beforeExecution(selected, ProcessAuthorization.metadata(selected)));
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
            assertFalse(output.containsKey("hostConfiguration")); assertFalse(output.containsKey("hostLifecycleIdentity"));
        }
        try (Fixture f = new Fixture()) {
            ProcessRecord original = f.capture(); f.observer.beforeExecution(original, ProcessAuthorization.metadata(original));
            var other = new ProcessRecord(original, 101L, "server"); other.setEndTime(new Date());
            f.observer.afterExecution(other, ProcessAuthorization.metadata(other), ExitReason.AUTHORIZATION_DENIED);
            assertNull(f.store.failure(f.id));
        }
    }

    @Test public void fullAndScopedUpdateStillUseLiveNativeRoleKeyRevisionAndExactTarget() throws Exception {
        for (String operation : List.of("start", "stop")) for (ApiKeyPolicy policy : List.of(full(), custom("1h3", "update"))) {
            try (Fixture f = new Fixture(operation, policy)) {
                var record = f.capture(); var metadata = ProcessAuthorization.metadata(record);
                f.capture.beforeExecution(record, metadata); f.observer.beforeExecution(record, metadata);
                f.key.setState("removed"); assertThrows(ProcessAuthorizationDeniedException.class, () -> f.capture.beforeExecution(record, metadata));
                f.key.setState("active"); f.key.setData(new ApiKeyPolicyCodec().store(f.key, policy, 4));
                assertThrows(ProcessAuthorizationDeniedException.class, () -> f.capture.beforeExecution(record, metadata));
                f.key.setData(new ApiKeyPolicyCodec().store(f.key, custom("1h3", "read"), 3));
                assertThrows(ProcessAuthorizationDeniedException.class, () -> f.capture.beforeExecution(record, metadata));
                f.key.setData(new ApiKeyPolicyCodec().store(f.key, custom("1h4", "update"), 3));
                assertThrows(ProcessAuthorizationDeniedException.class, () -> f.capture.beforeExecution(record, metadata));
                f.key.setData(new ApiKeyPolicyCodec().store(f.key, policy, 3)); when(f.schema.getResourceActions()).thenReturn(Map.of());
                assertThrows(ProcessAuthorizationDeniedException.class, () -> f.capture.beforeExecution(record, metadata));
            }
        }
    }

    private static ApiKeyPolicy full() {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
    }

    @Test public void hostOutcomeRequiresOneExactPostClaimProcessAndItsRealSuccessfulTerminalRow() throws Exception {
        for (String operation : List.of("start", "stop")) try (Fixture f = new Fixture(operation, full())) {
            var configuration = new DefaultConfiguration().set(f.connection).set(SQLDialect.H2);
            var sql = org.jooq.impl.DSL.using(configuration);
            var table = io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;
            sql.createSchemaIfNotExists("cattle").execute();
            sql.createTable(table).columns(java.util.Arrays.stream(table.fields()).map(field -> org.jooq.impl.DSL.field(
                    field.getUnqualifiedName(), field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            var domain = new AiResourceChangeDomain(); domain.setConfiguration(configuration);
            domain.objects = f.objects; domain.json = f.json; domain.targets = f.observer.targets;
            long baseline = domain.hostLifecycleProcessBaseline(f.root); assertEquals(0, baseline);
            var record = f.capture(); var plan = f.store.load(f.id); plan.put("processBaseline", baseline);
            sql.insertInto(table).set(table.ID, record.getId()).set(table.ACCOUNT_ID, 5L)
                    .set(table.PROCESS_NAME, record.getProcessName()).set(table.RESOURCE_TYPE, "host").set(table.RESOURCE_ID, "3")
                    .set(table.DATA, record.getData()).execute();
            assertEquals("running", domain.hostLifecycleProcessOutcome(plan, f.root));
            sql.update(table).set(table.END_TIME, new Date()).set(table.RESULT, "SUCCESS").set(table.PHASE, "DONE")
                    .set(table.EXIT_REASON, "DONE").where(table.ID.eq(100L)).execute();
            assertEquals("succeeded", domain.hostLifecycleProcessOutcome(plan, f.root));
            sql.update(table).set(table.RESULT, "CANCELED").set(table.EXIT_REASON, "CANCELED").where(table.ID.eq(100L)).execute();
            assertEquals("failed", domain.hostLifecycleProcessOutcome(plan, f.root));
            sql.insertInto(table).set(table.ID, 101L).set(table.ACCOUNT_ID, 5L).set(table.RESOURCE_TYPE, "host").set(table.RESOURCE_ID, "3")
                    .set(table.PROCESS_NAME, record.getProcessName()).set(table.DATA, record.getData()).execute();
            assertEquals("unknown", domain.hostLifecycleProcessOutcome(plan, f.root));
        }
    }
    private static ApiKeyPolicy custom(String id, String operation) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("exact-host", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("host", id),
                        java.util.Set.of(operation))), ApiKeyPolicy.Purpose.AI_BRIDGE);
    }
    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static class Fixture implements AutoCloseable {
        final String id = "aichange-" + UUID.randomUUID(); final Connection connection;
        final JacksonJsonMapper json = new JacksonJsonMapper(); final AiResourceChangePlanStore store = new AiResourceChangePlanStore();
        final AiResourceChangeProcessObserver observer = new AiResourceChangeProcessObserver();
        final ObjectManager objects = mock(ObjectManager.class); final Policy owner = mock(Policy.class);
        final HostRecord root = AiHostLifecycleChangeTest.host();
        final io.cattle.platform.core.model.tables.records.AccountRecord project = AiHostLifecycleChangeTest.project();
        final io.cattle.platform.core.model.tables.records.CredentialRecord key = new io.cattle.platform.core.model.tables.records.CredentialRecord();
        final io.github.ibuildthecloud.gdapi.model.Schema schema = mock(io.github.ibuildthecloud.gdapi.model.Schema.class);
        final ApiKeyProcessAuthorization capture = new ApiKeyProcessAuthorization(); final ApiRequest request = new ApiRequest(null, null);
        Fixture() throws Exception { this("start", null); }
        Fixture(String operation, ApiKeyPolicy policy) throws Exception {
            connection = DriverManager.getConnection("jdbc:h2:mem:registry-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            store.json = json; store.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
            root.setState(operation.equals("start") ? "inactive" : "active");
            when(objects.loadResource(io.cattle.platform.core.model.Account.class, 5L)).thenReturn(project);
            observer.store = store; observer.objects = objects; observer.json = json;
            var targets = new ApiKeyTargetResolver(); observer.targets = targets;
            var schemas = mock(SchemaFactory.class); when(schemas.getBaseType(anyString())).thenAnswer(call ->
                    "host".equals(call.getArgument(0)) ? "host" : call.getArgument(0));
            var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("host", "h", "credential", "c"));
            for (var entry : Map.of("objectManager", objects, "idFormatter", formatter).entrySet()) {
                var field = ApiKeyTargetResolver.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(targets, entry.getValue());
            }
            var targetField = ApiKeyProcessAuthorization.class.getDeclaredField("targets"); targetField.setAccessible(true); targetField.set(capture, targets);
            when(owner.getAccountId()).thenReturn(5L); when(owner.getAuthenticatedAsAccountId()).thenReturn(10L);
            when(objects.loadResource("host", 3L)).thenReturn(root); when(objects.loadResource("host", "3")).thenReturn(root);
            var plan = new LinkedHashMap<String, Object>(Map.of("id", id, "actorId", 10L, "accountId", 5L, "credentialId", 7L,
                    "credentialRevision", policy == null ? 0L : 3L, "credentialKind", policy == null ? "apiKey" : "apiKeyRestricted",
                    "resourceType", "host", "operation", operation, "status", "preview"));
            plan.put("internalResourceId", 3L); plan.put("resourceId", "1h3"); plan.put("expiresAt", "2030-01-01T00:00:00Z");
            plan.put("parameters", Map.of()); plan.put("body", Map.of()); plan.put("confirmationState", "approved");
            plan.put("snapshot", json.readValue(json.writeValueAsString(AiHostLifecycleChange.snapshot(root, project, json))));
            store.insert(plan); plan.put("status", "dispatching"); plan.put("operationId", id); assertTrue(store.claim(plan));
            request.setType("host"); request.setId("3"); request.setMethod("POST"); request.setAction(operation.equals("start") ? "activate" : "deactivate");
            request.setRequestObject(Map.of());
            ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setApiRequest(request); ApiContext.getContext().setIdFormatter(formatter);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 10, policy == null ? "apiKey" : "apiKeyRestricted",
                    policy == null ? 0 : 3, policy));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.keyId", "1c7");
            request.setAttribute("apiKey.audit.decision", "ALLOW"); request.setAttribute("apiKey.audit.operation", "update");
            when(owner.authorizeObject(root)).thenReturn(root);
            when(schemas.getSchema("host")).thenReturn(schema); when(schema.getResourceMethods()).thenReturn(List.of("GET"));
            when(schema.getResourceActions()).thenReturn(Map.of("activate", mock(io.github.ibuildthecloud.gdapi.model.Action.class),
                    "deactivate", mock(io.github.ibuildthecloud.gdapi.model.Action.class)));
            key.setId(7L); key.setAccountId(10L); key.setState("active"); key.setKind(policy == null ? "apiKey" : "apiKeyRestricted");
            key.setData(policy == null ? Map.of() : new ApiKeyPolicyCodec().store(key, policy, 3));
            when(objects.loadResource(io.cattle.platform.core.model.Credential.class, 7L)).thenReturn(key);
            var nativeTargets = mock(ApiKeyTargetResolver.class); when(nativeTargets.parseScopeId("credential", "1c7")).thenReturn(7L);
            when(nativeTargets.resolveObject(anyString(), eq(root), eq(owner)))
                    .thenReturn(ApiKeyPolicyEvaluator.Target.projectResource("host", "1h3", "1a5"));
            var authenticator = mock(io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.class);
            when(authenticator.currentAuthorization(eq(10L), eq(5L), any()))
                    .thenReturn(new io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.CurrentAuthorization(owner, schemas));
            inject(capture, "objectManager", objects); inject(capture, "targets", nativeTargets); inject(capture, "authenticator", authenticator);

        }
        ProcessRecord capture() {
            var nativeRoot = new LaunchConfiguration("host." + request.getAction(), "host", "3", 5L, 0, Map.of());
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
