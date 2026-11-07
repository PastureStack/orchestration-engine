package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.ProcessInstance;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyCodec;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyProcessAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.After;
import org.junit.Test;

/** A local real-plan/real-process-row regression; it is not deployed native acceptance. */
public class AiLoadBalancerScaleExecutionTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void approvedScaleTwoHasItsOwnExactActiveAndInactiveFullAndLegacyFrame() throws Exception {
        for (String state : List.of("active", "inactive")) for (boolean legacy : List.of(false, true)) {
            try (var f = new Fixture(state, legacy ? null : full())) {
                f.authorize(); f.authorize();
                assertEquals(901L, ((Number) f.store.execution(f.id).get("rootProcessId")).longValue());
                assertEquals(2, (int) DataAccessor.fieldInteger(f.row, "scale"));
                assertEquals(state, f.row.getState()); assertEquals("unchanged", f.row.getName());
                assertEquals(1, ((List<?>) f.store.execution(f.id).get("initial")).size());
                assertEquals("dispatching", f.store.load(f.id).get("status"));
                assertFalse(Boolean.TRUE.equals(f.store.load(f.id).get("verifiedResult")));
            }
        }
    }

    @Test public void scaleCannotBorrowMetadataForeignIdentityOrMutatedProcessPayload() throws Exception {
        try (var f = new Fixture("inactive", full())) {
            f.authorize();
            f.record.getData().put("name", "forbidden"); f.denied(); f.record.getData().remove("name");
            f.record.getData().put("lbConfig", Map.of()); f.denied(); f.record.getData().remove("lbConfig");
            f.record.getData().put("scale", 3); f.denied(); f.record.getData().put("scale", 2);
            f.record.getData().remove("scale"); f.denied(); f.record.getData().put("scale", 2);
            f.record.getData().put("old", Map.of("scale", 9)); f.denied();
            f.record.getData().put("old", Map.of("scale", 1, "name", "old")); f.denied();
            f.record.getData().put("old", Map.of("scale", 1));
            f.row.setUuid("other-uuid"); f.denied(); f.row.setUuid("lb-uuid");
            f.row.setKind("service"); f.denied(); f.row.setKind("loadBalancerService");
            f.row.setAccountId(88L); f.denied(); f.row.setAccountId(77L);
            f.row.setStackId(4L); f.denied(); f.row.setStackId(3L);
            f.stack.setUuid("other-stack"); f.denied(); f.stack.setUuid("stack-uuid");
            f.stack.setRemoved(new java.util.Date()); f.denied(); f.stack.setRemoved(null);
            DataAccessor.fields(f.row).withKey("scale").set(3); f.denied();
            DataAccessor.fields(f.row).withKey("scale").set(2);
            var original = f.store.execution(f.id); var wrong = new LinkedHashMap<>(original);
            wrong.put("operation", "update_metadata"); f.binding(wrong); f.denied();
            wrong.put("operation", "unknown"); f.binding(wrong); f.denied(); f.binding(original);
            f.plan.put("parameters", Map.of("scale", 2, "name", "mixed")); f.store.save(f.plan); f.denied();
            f.plan.put("parameters", Map.of("scale", 2)); f.store.save(f.plan);
            var other = new ProcessRecord(new LaunchConfiguration("service.update", "service", "8", 77L, 0,
                    f.record.getData()), 902L, "test");
            assertThrows(ProcessAuthorizationDeniedException.class, () -> f.observer.beforeExecution(other, f.metadata));
            var duplicate = new ProcessRecord(new LaunchConfiguration("service.update", "service", "7", 77L, 0,
                    f.record.getData()), 902L, "test"); f.persist(duplicate);
            assertThrows(ProcessAuthorizationDeniedException.class, () -> f.observer.beforeExecution(duplicate, f.metadata));
            f.authorize();
            f.sql.deleteFrom(io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE)
                    .where(io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE.ID.eq(901L)).execute();
            f.denied();
        }
    }

    @Test public void scaleAttestationKeepsLiveFullCustomReadRevocationRoleAndGlobalChecks() throws Exception {
        for (ApiKeyPolicy initial : List.of(full(), custom("1s7", "update"))) {
            try (var f = new Fixture("active", initial)) {
                f.authorize();
                f.key.setState("removed"); f.nativeDenied("ApiKeyRevoked"); f.key.setState("active");
                f.key.setData(new ApiKeyPolicyCodec().store(f.key, initial, 4)); f.nativeDenied("ApiKeyPolicyChanged");
                f.key.setData(new ApiKeyPolicyCodec().store(f.key, initial, 3));
                when(f.schema.getResourceMethods()).thenReturn(List.of("GET")); f.nativeDenied("OwnerPermissionDenied");
                when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
                when(f.owner.authorizeObject(f.row)).thenReturn(null); f.nativeDenied("OwnerPermissionDenied");
                when(f.owner.authorizeObject(f.row)).thenReturn(f.row);
                f.key.setData(new ApiKeyPolicyCodec().store(f.key, custom("1s7", "read"), 3)); f.nativeDenied("KeyScopeDenied");
                f.key.setData(new ApiKeyPolicyCodec().store(f.key, custom("1s8", "update"), 3)); f.nativeDenied("KeyScopeDenied");
                f.key.setData(new ApiKeyPolicyCodec().store(f.key, initial, 3)); f.authorize();
                DataAccessor.fields(f.row).withKey("launchConfig").set(Map.of("labels", Map.of(ServiceConstants.LABEL_SERVICE_GLOBAL, "true")));
                // The global-mode classifier belongs to the native server module; this API-module owner must honor its rejection.
                when(f.observer.deploymentReadiness.get().supportsManualScale(f.row)).thenReturn(false);
                assertFalse(f.observer.deploymentReadiness.get().supportsManualScale(f.row)); f.denied();
                var domain = new AiResourceChangeDomain(); domain.deploymentReadiness = f.observer.deploymentReadiness.get();
                assertEquals("ResourceChangeUnsupported", assertThrows(ClientVisibleException.class,
                        () -> domain.ready(AiResourceChangeDescriptor.require("loadBalancerService", "scale"), f.row, null)).getCode());
            }
        }
    }

    private static ApiKeyPolicy full() {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
    }
    private static ApiKeyPolicy custom(String id, String operation) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("one-target", ApiKeyPolicy.Effect.ALLOW,
                        ApiKeyPolicy.Scope.resource("service", id), Set.of(operation))), ApiKeyPolicy.Purpose.AI_BRIDGE);
    }
    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static class Fixture implements AutoCloseable {
        final Connection connection;
        final DSLContext sql;
        final AiResourceChangePlanStore store = new AiResourceChangePlanStore();
        final AiResourceChangeProcessObserver observer = new AiResourceChangeProcessObserver();
        final ApiKeyProcessAuthorization nativeOwner = new ApiKeyProcessAuthorization();
        final ObjectManager objects = mock(ObjectManager.class);
        final Policy owner = mock(Policy.class);
        final Schema schema = mock(Schema.class);
        final ServiceRecord row = new ServiceRecord();
        final StackRecord stack = new StackRecord();
        final CredentialRecord key = new CredentialRecord();
        final Map<String, Object> plan = new LinkedHashMap<>();
        final String id = "aichange-" + UUID.randomUUID();
        final ProcessRecord record;
        final Map<String, Object> metadata;
        Fixture(String state, ApiKeyPolicy keyPolicy) throws Exception {
            connection = DriverManager.getConnection("jdbc:h2:mem:lb-scale-frame-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),document CLOB,"
                    + "actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            var configuration = new DefaultConfiguration().set(connection).set(SQLDialect.H2);
            sql = DSL.using(configuration); store.json = new JacksonJsonMapper(); store.setConfiguration(configuration);
            observer.store = store; observer.objects = objects; observer.targets = new ApiKeyTargetResolver();
            observer.json = store.json;
            var schemas = mock(SchemaFactory.class); when(schemas.getSchema("loadBalancerService")).thenReturn(schema);
            when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
            var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas); formatter.setTypeMappings(Map.of("credential", "c"));
            inject(observer.targets, "idFormatter", formatter);
            when(owner.getAccountId()).thenReturn(77L); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L);
            when(owner.authorizeObject(any())).thenAnswer(call -> call.getArgument(0));
            ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setIdFormatter(formatter);
            row.setId(7L); row.setUuid("lb-uuid"); row.setAccountId(77L); row.setStackId(3L);
            row.setKind("loadBalancerService"); row.setState(state); row.setName("unchanged");
            DataAccessor.fields(row).withKey("scale").set(1);
            stack.setId(3L); stack.setAccountId(77L); stack.setUuid("stack-uuid"); stack.setKind("stack"); stack.setState("active");
            when(objects.loadResource("service", 7L)).thenReturn(row); when(objects.loadResource("service", "7")).thenReturn(row);
            when(objects.loadResource("loadBalancerService", 7L)).thenReturn(row);
            when(objects.loadResource(io.cattle.platform.core.model.Stack.class, 3L)).thenReturn(stack);
            key.setId(7L); key.setAccountId(42L); key.setState("active");
            key.setKind(keyPolicy == null ? "apiKey" : "apiKeyRestricted");
            key.setData(keyPolicy == null ? Map.of() : new ApiKeyPolicyCodec().store(key, keyPolicy, 3));
            when(objects.loadResource(Credential.class, 7L)).thenReturn(key);
            var targets = mock(ApiKeyTargetResolver.class); when(targets.parseScopeId("credential", "1c7")).thenReturn(7L);
            var target = ApiKeyPolicyEvaluator.Target.stackResource("service", "1s7", "1a77", "1st3");
            when(targets.resolveObject(anyString(), eq(row), eq(owner))).thenReturn(target);
            var auth = mock(ApiAuthenticator.class);
            when(auth.currentAuthorization(eq(42L), eq(77L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, schemas));
            inject(nativeOwner, "objectManager", objects); inject(nativeOwner, "targets", targets); inject(nativeOwner, "authenticator", auth);
            var readiness = mock(ServiceDeploymentReadiness.class);
            when(readiness.supportsManualScale(row)).thenReturn(true);
            observer.deploymentReadiness = () -> readiness;
            var domain = new AiResourceChangeDomain(); domain.json = store.json; domain.objects = objects;
            plan.putAll(Map.of("id", id, "resourceType", "loadBalancerService", "operation", "scale", "actorId", 42L, "accountId", 77L,
                    "credentialId", 7L, "credentialRevision", keyPolicy == null ? 0L : 3L, "credentialKind", key.getKind(),
                    "internalResourceId", 7L, "resourceId", "1s7"));
            plan.put("status", "preview"); plan.put("parameters", Map.of("scale", 2)); plan.put("body", Map.of("scale", 2));
            plan.put("expiresAt", "2030-01-01T00:00:00Z"); plan.put("snapshot", domain.snapshot(row));
            plan.put("referenceSnapshot", Map.of("id", 3, "uuid", "stack-uuid", "accountId", 77, "kind", "stack"));
            store.insert(plan); plan.put("status", "dispatching"); plan.put("operationId", id); assertTrue(store.claim(plan));
            var request = new ApiRequest(null, schemas); request.setType("loadBalancerService"); request.setMethod("PUT"); request.setId("7");
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, key.getKind(), keyPolicy == null ? 0 : 3, keyPolicy));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.keyId", "1c7"); request.setAttribute("apiKey.audit.operation", "update");
            request.setRequestObject(Map.of("scale", 2)); ApiContext.getContext().setApiRequest(request);
            request.setRequestObject(Map.of("scale", 2, "name", "unapproved"));
            assertThrows(IllegalStateException.class, () -> observer.dispatch(request, id, () -> {
                fail("An unapproved field must not reach the native PUT"); return null;
            }));
            request.setRequestObject(Map.of("scale", 2));
            // The native PUT persists desired fields before scheduling service.update.
            DataAccessor.fields(row).withKey("scale").set(2);
            var config = new LaunchConfiguration("service.update", "service", "7", 77L, 0, Map.of("scale", 2, "old", Map.of("scale", 1)));
            observer.dispatch(request, id, () -> { ProcessAuthorization.prepare(config, List.of(nativeOwner, observer)); return null; });
            record = new ProcessRecord(config, 901L, "test"); record.setData(store.json.readValue(store.json.writeValueAsString(record.getData())));
            metadata = ProcessAuthorization.metadata(record);
            var table = io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;
            sql.createSchemaIfNotExists("cattle").execute();
            sql.createTable(table).columns(java.util.Arrays.stream(table.fields()).map(field -> DSL.field(field.getUnqualifiedName(),
                    field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            persist(record);
            when(objects.loadResource(eq(ProcessInstance.class), anyLong())).thenAnswer(call -> sql.selectFrom(table)
                    .where(table.ID.eq((Long) call.getArgument(1))).fetchOne());
        }
        void persist(ProcessRecord value) {
            var table = io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;
            sql.insertInto(table).set(table.ID, value.getId()).set(table.ACCOUNT_ID, ((Number) value.getAccountId()).longValue())
                    .set(table.PROCESS_NAME, value.getProcessName()).set(table.RESOURCE_TYPE, value.getResourceType())
                    .set(table.RESOURCE_ID, value.getResourceId()).set(table.DATA, value.getData()).execute();
        }
        void binding(Map<String, Object> value) throws Exception {
            sql.update(AiResourceChangePlanStore.TABLE).set(AiResourceChangePlanStore.EXECUTION, store.json.writeValueAsString(value))
                    .where(AiResourceChangePlanStore.ID.eq(id)).execute();
        }
        void syncData() {
            var table = io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;
            sql.update(table).set(table.DATA, record.getData()).where(table.ID.eq(record.getId())).execute();
        }
        void authorize() { syncData(); nativeOwner.beforeExecution(record, metadata); observer.beforeExecution(record, metadata); }
        void denied() { syncData(); assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, metadata)); }
        void nativeDenied(String code) { assertEquals(code, assertThrows(ProcessAuthorizationDeniedException.class,
                () -> nativeOwner.beforeExecution(record, metadata)).getCode()); }
        @Override public void close() throws Exception { connection.close(); ApiContext.remove(); }
    }
}
