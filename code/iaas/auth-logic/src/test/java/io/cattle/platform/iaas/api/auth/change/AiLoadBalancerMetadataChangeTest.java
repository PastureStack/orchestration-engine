package io.cattle.platform.iaas.api.auth.change;

import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyProcessAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.After;
import org.junit.Test;

/** This new subset keeps the existing native owner, Key authorization and durable plan owners. */
public class AiLoadBalancerMetadataChangeTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void onlyLoadBalancerMetadataFieldsAndIntentAreAdmitted() {
        var descriptor = descriptor();
        assertEquals("PUT", descriptor.method()); assertEquals("update", descriptor.keyOperation()); assertNull(descriptor.action());
        var input = new LinkedHashMap<String, Object>(); input.put("name", "safe-name"); input.put("description", null);
        assertEquals(input, descriptor.body(descriptor.parameters(input)));
        for (String field : List.of("lbConfig", "portRules", "certificateIds", "image", "scale", "action", "data", "launchConfig", "stackId"))
            assertEquals("ResourceChangeInputInvalid", assertThrows(ClientVisibleException.class,
                    () -> descriptor.parameters(Map.of(field, "PRIVATE-CANARY"))).getCode());
        for (String operation : List.of("restart", "scale", "remove", "archive", "restore"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("loadBalancerService", operation));
        var create = AiResourceChangeDescriptor.require("loadBalancerService", "create");
        assertEquals("POST", create.method());
        assertThrows(ClientVisibleException.class, () -> create.parameters(Map.of("name", "safe-name", "stackId", "1st3")));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of()));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of("name", " ")));
    }

    @Test public void freshNativeStackAndKeyChecksProtectTheSparsePutAndPrivateParentRead() throws Exception {
        enabled(() -> {
            var f = new DomainFixture(); realKey(f, full());
            when(f.stackManager.getById(eq("stack"), eq("1st3"), any())).thenAnswer(call -> {
                ApiRequest request = ApiContext.getContext().getApiRequest();
                assertTrue(AiResourceChangeDomain.trustedDomain(request));
                var copied = new ApiRequest(request); assertFalse(AiResourceChangeDomain.trustedDomain(copied));
                request.setMethod("PUT"); assertFalse(AiResourceChangeDomain.trustedDomain(request)); request.setMethod("GET");
                assertEquals(Map.of(), request.getRequestObject()); return f.stack;
            });
            when(f.manager.update(eq("loadBalancerService"), eq("1s7"), any())).thenAnswer(call -> {
                ApiRequest actual = call.getArgument(2); assertTrue(AiResourceChangeDomain.trustedDomain(actual));
                assertEquals(Map.of("name", "after"), actual.getRequestObject()); return f.row;
            });
            assertEquals(Map.of("name", "after"), f.domain.prepare(f.request, descriptor(), "1s7", Map.of("name", "after")));
            assertSame(f.row, f.domain.dispatch(f.request, descriptor(), "1s7", Map.of("name", "after"), "own-plan"));
            assertEquals("Environment / Blue / shared", f.domain.targetPath(f.request, f.row, null, Map.of()));
            f.stack.setName("Green"); assertEquals("Environment / Green / shared", f.domain.targetPath(f.request, f.row, null, Map.of()));
            realKey(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                    List.of(new ApiKeyPolicy.Rule("lb-only", ApiKeyPolicy.Effect.ALLOW,
                            ApiKeyPolicy.Scope.resource("service", "1s7"), Set.of("read", "update"))), ApiKeyPolicy.Purpose.AI_BRIDGE));
            assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1s7", Map.of("name", "never"))).getCode());
            realKey(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                    List.of(new ApiKeyPolicy.Rule("lb-service", ApiKeyPolicy.Effect.ALLOW,
                            ApiKeyPolicy.Scope.resource("service", "1s7"), Set.of("read", "update")),
                            new ApiKeyPolicy.Rule("read-stack", ApiKeyPolicy.Effect.ALLOW,
                                    ApiKeyPolicy.Scope.resource("stack", "1st3"), Set.of("read"))), ApiKeyPolicy.Purpose.AI_BRIDGE));
            assertEquals(Map.of("name", "after"), f.domain.prepare(f.request, descriptor(), "1s7", Map.of("name", "after")));
            when(f.http.getHeader(io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("2");
            assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class,
                    () -> f.domain.dispatch(f.request, descriptor(), "1s7", Map.of("name", "never"), "own-plan")).getCode());
            verify(f.manager, times(1)).update(anyString(), anyString(), any());
            assertSame(f.request, ApiContext.getContext().getApiRequest()); assertSame(f.policy, ApiContext.getContext().getPolicy());
        });
    }

    @Test public void scalarSnapshotsAndStackContextCasExcludeConfigurationAndRejectScopeOrMovement() throws Exception {
        enabled(() -> {
            var f = new DomainFixture(); realKey(f, full());
            f.row.setData(Map.of("fields", Map.of("lbConfig", Map.of("secret", "PRIVATE-CANARY"))));
            var snapshot = f.domain.loadBalancerMetadataSnapshot(f.row);
            assertEquals("lb-uuid", snapshot.get("uuid")); assertFalse(snapshot.containsKey("data"));
            assertFalse(snapshot.toString().contains("PRIVATE-CANARY"));
            assertTrue(f.domain.snapshot(f.row).containsKey("data")); // Existing Service/Stack CAS semantics remain intact.
            var context = f.domain.loadBalancerMetadataContext(f.request, descriptor(), "1s7");
            var receipt = new LinkedHashMap<String, Object>();
            receipt.put("internalResourceId", 7L); receipt.put("resourceId", "1s7"); receipt.put("status", "accepted");
            receipt.put("snapshot", snapshot); receipt.put("referenceSnapshot", context);
            when(f.domain.objects.loadResource("loadBalancerService", 7L)).thenReturn(f.row);
            when(f.policy.authorizeObject(f.row)).thenReturn(f.row);
            f.row.setName("after");
            assertTrue(f.domain.complete(descriptor(), f.row, Map.of("name", "after"), snapshot));
            assertSame(f.row, f.domain.receiptTarget(f.request, descriptor(), receipt));
            f.row.setUuid("replacement");
            assertTrue("Matching metadata alone cannot prove the original resource identity",
                    f.domain.complete(descriptor(), f.row, Map.of("name", "after"), snapshot));
            assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                    () -> f.domain.receiptTarget(f.request, descriptor(), receipt)).getCode());
            f.row.setUuid("lb-uuid"); f.stack.setUuid("replacement");
            assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                    () -> f.domain.receiptTarget(f.request, descriptor(), receipt)).getCode());
            f.stack.setUuid("stack-uuid"); f.row.setStackId(4L);
            var otherStack = stack(); otherStack.setId(4L); otherStack.setUuid("other-stack-uuid");
            when(f.stackManager.getById(eq("stack"), eq("1st4"), any())).thenReturn(otherStack);
            assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                    () -> f.domain.receiptTarget(f.request, descriptor(), receipt)).getCode());
            f.row.setStackId(3L); f.row.setState("updating"); f.stack.setName("new-readable-name");
            assertSame("Normal mutable state and labels do not change receipt identity", f.row,
                    f.domain.receiptTarget(f.request, descriptor(), receipt));
            receipt.put("status", "succeeded"); receipt.put("verifiedResult", true);
            receipt.remove("snapshot"); receipt.remove("referenceSnapshot");
            assertSame("An already verified receipt remains historical after snapshots are erased", f.row,
                    f.domain.receiptTarget(f.request, descriptor(), receipt));
            f.stack.setName("renamed");
            assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1s7", Map.of("name", "after"), Map.of("referenceSnapshot", context))).getCode());
            f.stack.setAccountId(88L);
            assertEquals("NotFound", assertThrows(ClientVisibleException.class,
                    () -> f.domain.targetPath(f.request, f.row, null, Map.of())).getCode());
            f.stack.setAccountId(77L); when(f.stackManager.getById(eq("stack"), eq("1st3"), any())).thenAnswer(call -> {
                var moved = row(); moved.setStackId(4L); when(f.domain.objects.loadResource(Service.class, 7L)).thenReturn(moved); return f.stack;
            });
            assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                    () -> f.domain.loadBalancerMetadataContext(f.request, descriptor(), "1s7")).getCode());
            when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
            assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1s7", Map.of("name", "after"))).getCode());
            when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT")); f.name.setUpdate(false);
            assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1s7", Map.of("name", "after"))).getCode());
            verify(f.manager, never()).update(anyString(), anyString(), any());
        });
    }

    @Test public void loadBalancerPlanKeepsHumanApprovalSnapshotCasRevocationAndOneClaim() {
        var f = new AiResourceChangePlanServiceTest.Fixture(); f.target.setKind("loadBalancerService"); f.target.setUuid("lb-uuid");
        var owner = new AiResourceChangeDomain(); owner.json = f.json;
        when(f.domain.loadBalancerMetadataSnapshot(any())).thenAnswer(call -> owner.loadBalancerMetadataSnapshot(call.getArgument(0)));
        when(f.domain.loadBalancerMetadataContext(any(), any(), anyString())).thenReturn(Map.of("uuid", "stack-uuid", "name", "Blue"));
        when(f.domain.receiptTarget(any(), any(), anyMap())).thenAnswer(call -> {
            Map<?, ?> plan = call.getArgument(2);
            f.domain.loadBalancerMetadataContext(call.getArgument(0), call.getArgument(1), (String) plan.get("resourceId"));
            return f.target;
        });
        ApiRequest request = f.request(null, true); request.setRequestObject(Map.of("resourceType", "loadBalancerService", "operation", "update_metadata",
                "resourceId", "1s7", "parameters", Map.of("description", "new private description")));
        f.id = (String) f.service.preview(request).get("id");
        assertFalse(((Map<?, ?>) f.documents.get(f.id).get("snapshot")).containsKey("data"));
        assertEquals("ResourceChangeNeedsConfirmation", assertThrows(ClientVisibleException.class, () -> f.apply(true)).getCode());
        f.target.setUuid("replacement");
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class, f::confirm).getCode()); f.target.setUuid("lb-uuid");
        f.writeAllowed = false; assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class, f::confirm).getCode());
        f.writeAllowed = true; f.confirm();
        assertEquals("ResourceChangeBoundKeyRequired", assertThrows(ClientVisibleException.class, () -> f.apply(false)).getCode());
        assertEquals("accepted", f.apply(true).get("status")); f.apply(true);
        assertEquals(1, f.dispatched); assertEquals(1, f.claims); f.complete = true;
        var done = f.service.get(f.id, f.request(null, true)); assertEquals(true, done.get("verifiedResult"));
        assertFalse(done.containsKey("snapshot")); assertFalse(done.containsKey("referenceSnapshot"));
        when(f.domain.loadBalancerMetadataContext(any(), any(), anyString())).thenThrow(new ClientVisibleException(403, "KeyScopeDenied"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () -> f.service.get(f.id, f.request(null, true))).getCode());
    }

    @Test public void loadBalancerMetadataLocksActualServiceRowAndDetectsNativeScalarChanges() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:lb-lock-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE")) {
            var config = new DefaultConfiguration().set(connection).set(SQLDialect.H2); var sql = DSL.using(config);
            sql.createSchemaIfNotExists("cattle").execute();
            sql.createTable(SERVICE).columns(java.util.Arrays.stream(SERVICE.fields()).map(field -> DSL.field(field.getUnqualifiedName(),
                    field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            sql.insertInto(SERVICE).set(SERVICE.ID, 7L).set(SERVICE.ACCOUNT_ID, 77L).set(SERVICE.UUID, "lb-uuid")
                    .set(SERVICE.KIND, "loadBalancerService").set(SERVICE.NAME, "shared").set(SERVICE.STATE, "active").set(SERVICE.SYSTEM, false)
                    .set(SERVICE.REMOVED, (java.util.Date) null).set(SERVICE.DATA, Map.of()).execute();
            var domain = new AiResourceChangeDomain(); domain.json = new JacksonJsonMapper(); domain.setConfiguration(config);
            assertEquals("ResourceChangeTransactionRequired", assertThrows(ClientVisibleException.class,
                    () -> domain.lock("loadBalancerService", 7)).getCode());
            connection.setAutoCommit(false); var before = domain.loadBalancerMetadataSnapshot(domain.lock("loadBalancerService", 7));
            sql.update(SERVICE).set(SERVICE.DESCRIPTION, "human-update").where(SERVICE.ID.eq(7L)).execute();
            assertNotEquals(before, domain.loadBalancerMetadataSnapshot(domain.lock("loadBalancerService", 7))); connection.rollback();
        }
    }

    @Test public void queuedNativeServiceUpdateUsesExactLbKindUuidStackAndCapturedProcess() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:lb-observer-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE")) {
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),document CLOB,"
                    + "actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            var store = new AiResourceChangePlanStore(); store.json = new JacksonJsonMapper();
            store.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
            var observer = new AiResourceChangeProcessObserver(); observer.store = store; observer.objects = mock(ObjectManager.class);
            observer.targets = new ApiKeyTargetResolver(); var schema = mock(SchemaFactory.class);
            var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schema); formatter.setTypeMappings(Map.of("credential", "c"));
            inject(observer.targets, "idFormatter", formatter);
            var policy = mock(Policy.class); when(policy.getAccountId()).thenReturn(77L); when(policy.getAuthenticatedAsAccountId()).thenReturn(42L);
            ApiContext.newContext().setPolicy(policy); ApiContext.getContext().setIdFormatter(formatter);
            var row = row(); var stack = stack();
            when(observer.objects.loadResource("service", 7L)).thenReturn(row); when(observer.objects.loadResource("service", "7")).thenReturn(row);
            when(observer.objects.loadResource(io.cattle.platform.core.model.Stack.class, 3L)).thenReturn(stack);
            String id = "aichange-" + UUID.randomUUID(); var plan = new LinkedHashMap<String, Object>();
            plan.putAll(Map.of("id", id, "resourceType", "loadBalancerService", "operation", "update_metadata", "actorId", 42L, "accountId", 77L,
                    "credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted", "internalResourceId", 7L, "resourceId", "1s7"));
            plan.put("status", "preview"); plan.put("parameters", Map.of("name", "after")); plan.put("expiresAt", "2030-01-01T00:00:00Z");
            store.insert(plan); plan.put("status", "dispatching"); plan.put("operationId", id); assertTrue(store.claim(plan));
            var request = new ApiRequest(null, schema); request.setType("loadBalancerService"); request.setMethod("PUT"); request.setId("7");
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, full()));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.keyId", "1c7");
            request.setAttribute("apiKey.audit.operation", "update");
            request.setRequestObject(Map.of("name", "after"));
            ApiContext.getContext().setApiRequest(request);
            var nativeOwner = new ApiKeyProcessAuthorization();
            var config = new LaunchConfiguration("service.update", "service", "7", 77L, 0, Map.of("name", "after", "old", Map.of("name", "shared")));
            request.setRequestObject(Map.of("name", "after", "lbConfig", Map.of("unsafe", true)));
            assertThrows(IllegalStateException.class, () -> observer.dispatch(request, id, () -> { fail("unapproved business data"); return null; }));
            assertNull(request.getAttribute(AiResourceChangeProcessObserver.ATTRIBUTE)); request.setRequestObject(Map.of("name", "after"));
            observer.dispatch(request, id, () -> { ProcessAuthorization.prepare(config, List.of(nativeOwner, observer)); return null; });
            assertNull(request.getAttribute(AiResourceChangeProcessObserver.ATTRIBUTE));
            var record = new ProcessRecord(config, 901L, "test"); record.setData(store.json.readValue(store.json.writeValueAsString(record.getData())));
            var metadata = ProcessAuthorization.metadata(record);
            assertFalse("ordinary service.update does not use the Project-only captured execution capability", ProcessAuthorization.executionBound(record));

            assertEquals("stack-uuid", store.execution(id).get("rootStackUuid"));
            var check = AiResourceChangeProcessObserver.class.getDeclaredMethod("metadataMatches", Map.class, Map.class);
            check.setAccessible(true); assertEquals("metadataMatches", true, check.invoke(observer, store.execution(id), metadata));
            check = AiResourceChangeProcessObserver.class.getDeclaredMethod("provenChild", Map.class, LaunchConfiguration.class, Map.class);
            check.setAccessible(true); assertEquals("provenChild", true, check.invoke(observer, store.execution(id), record, metadata));
            var sql = DSL.using(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
            var processTable = io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;
            sql.createSchemaIfNotExists("cattle").execute();
            sql.createTable(processTable).columns(java.util.Arrays.stream(processTable.fields()).map(field -> DSL.field(field.getUnqualifiedName(),
                    field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            sql.insertInto(processTable).set(processTable.ID, 901L).set(processTable.ACCOUNT_ID, 77L).set(processTable.PROCESS_NAME, "service.update")
                    .set(processTable.RESOURCE_TYPE, "service").set(processTable.RESOURCE_ID, "7").set(processTable.DATA, record.getData()).execute();
            when(observer.objects.loadResource(eq(io.cattle.platform.core.model.ProcessInstance.class), anyLong())).thenAnswer(call ->
                    sql.selectFrom(processTable).where(processTable.ID.eq((Long) call.getArgument(1))).fetchOne());
            observer.beforeExecution(record, metadata); assertEquals(901L, ((Number) store.execution(id).get("rootProcessId")).longValue());
            assertEquals(7L, ((Number) store.execution(id).get("rootId")).longValue());
            row.setKind("service"); assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, metadata));
            row.setKind("loadBalancerService"); row.setUuid("replacement");
            assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, metadata)); row.setUuid("lb-uuid");
            stack.setUuid("replacement"); assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, metadata));
            stack.setUuid("stack-uuid"); row.setAccountId(88L);
            assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, metadata)); row.setAccountId(77L);
            var wrong = new ProcessRecord(new LaunchConfiguration("service.update", "service", "8", 77L, 0, record.getData()), 902L, "test");
            assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(wrong, metadata));
            var duplicate = new ProcessRecord(config, 902L, "test"); duplicate.setData(record.getData());
            sql.insertInto(processTable).set(processTable.ID, 902L).set(processTable.ACCOUNT_ID, 77L).set(processTable.PROCESS_NAME, "service.update")
                    .set(processTable.RESOURCE_TYPE, "service").set(processTable.RESOURCE_ID, "7").set(processTable.DATA, record.getData()).execute();
            assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(duplicate, metadata));
            record.getData().put("lbConfig", Map.of("unsafe", true));
            assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, metadata)); record.getData().remove("lbConfig");
            observer.beforeExecution(record, metadata); assertEquals(1, ((List<?>) store.execution(id).get("initial")).size());
            row.setRemoved(new java.util.Date());
            assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, metadata));
            row.setRemoved(null);
            stack.setRemoved(new java.util.Date());
            assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, metadata));
            stack.setRemoved(null);
            plan.put("status", "unknown"); store.save(plan); observer.beforeExecution(record, metadata);
            assertFalse(Boolean.TRUE.equals(store.load(id).get("verifiedResult"))); assertEquals("unknown", store.load(id).get("status"));
            sql.deleteFrom(processTable).where(processTable.ID.eq(901L)).execute();
            assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, metadata));
        }
    }

    @Test public void queuedNativeOwnerRetainsFullLegacySchemaRevisionAndRevocationChecks() throws Exception {
        var owner = mock(Policy.class); when(owner.authorizeObject(any())).thenAnswer(call -> call.getArgument(0));
        var schemas = mock(SchemaFactory.class); var schema = mock(io.github.ibuildthecloud.gdapi.model.Schema.class);
        when(schemas.getSchema("loadBalancerService")).thenReturn(schema); when(schema.getResourceMethods()).thenReturn(List.of("PUT"));
        var objects = mock(ObjectManager.class); when(objects.getSchemaFactory()).thenReturn(schemas);
        var credential = mock(io.cattle.platform.core.model.Credential.class); when(credential.getId()).thenReturn(7L);
        when(credential.getAccountId()).thenReturn(42L); when(credential.getKind()).thenReturn("apiKey"); when(credential.getState()).thenReturn("active");
        var row = row(); when(objects.loadResource(io.cattle.platform.core.model.Credential.class, 7L)).thenReturn(credential);
        when(objects.loadResource("loadBalancerService", 7L)).thenReturn(row); when(objects.loadResource("service", "7")).thenReturn(row);
        var authenticator = mock(io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.class);
        when(authenticator.currentAuthorization(eq(42L), eq(77L), any())).thenReturn(
                new io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.CurrentAuthorization(owner, schemas));
        var targets = mock(ApiKeyTargetResolver.class); when(targets.parseScopeId("credential", "1c7")).thenReturn(7L);
        when(targets.resolveObject(anyString(), eq(row), eq(owner))).thenReturn(
                ApiKeyPolicyEvaluator.Target.stackResource("service", "1s7", "1a77", "1st3"));
        var hook = new ApiKeyProcessAuthorization();
        inject(hook, "objectManager", objects);
        inject(hook, "authenticator", authenticator);
        inject(hook, "targets", targets);
        var metadata = new LinkedHashMap<String, Object>(Map.of("keyId", "1c7", "policyRevision", 0L, "principalAccountId", 42L,
                "accountId", 77L, "operation", "update", "targetType", "loadBalancerService", "targetId", "7",
                "requestMethod", "PUT", "requestCollection", false));
        var config = new LaunchConfiguration("service.update", "service", "7", 77L, 0, Map.of("name", "after", "old", Map.of("name", "shared")));
        metadata.put("requestId", UUID.randomUUID().toString());
        hook.beforeExecution(config, metadata); // Native legacy Key, not a mocked AI grant.
        new AiResourceChangeProcessObserver().beforeExecution(config, metadata);
        var codec = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyCodec();
        var stored = codec.store(credential, generalFull(), 3); when(credential.getData()).thenReturn(stored); metadata.put("policyRevision", 3L);
        hook.beforeExecution(config, metadata);
        when(owner.authorizeObject(row)).thenReturn(null);
        assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config, metadata)).getCode());
        when(owner.authorizeObject(row)).thenReturn(row); when(schema.getResourceMethods()).thenReturn(List.of("GET"));
        assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config, metadata)).getCode());
        when(schema.getResourceMethods()).thenReturn(List.of("PUT")); metadata.put("policyRevision", 2L);
        assertEquals("ApiKeyPolicyChanged", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config, metadata)).getCode());
        metadata.put("policyRevision", 3L); when(credential.getState()).thenReturn("removed");
        assertEquals("ApiKeyRevoked", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config, metadata)).getCode());
        verify(authenticator, atLeast(4)).currentAuthorization(eq(42L), eq(77L), any());
    }

    @Test public void ordinaryNativeV1V2FullAndLegacyCaptureNeverEnterTheAiMandatoryBranch() {
        for (String version : List.of("v1", "v2-beta")) for (boolean restrictedFull : List.of(false, true)) {
            var owner = mock(Policy.class); when(owner.getAccountId()).thenReturn(77L); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L);
            var request = new ApiRequest(null, null); request.setSchemaVersion(version); request.setType("loadBalancerService");
            request.setId("7"); request.setMethod("PUT"); request.setRequestObject(Map.of("name", "native-owner-change"));
            ApiContext.newContext().setPolicy(owner);
            var formatter = new DefaultIdFormatter(); formatter.setTypeMappings(Map.of("credential", "c"));
            formatter.setSchemaFactory(mock(SchemaFactory.class)); ApiContext.getContext().setIdFormatter(formatter);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, restrictedFull ? "apiKeyRestricted" : "apiKey",
                    restrictedFull ? 3 : 0, restrictedFull ? generalFull() : null));
            request.setAttribute("apiKey.audit.requestId", UUID.randomUUID().toString()); request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.keyId", "1c7"); request.setAttribute("apiKey.audit.operation", "update");
            ApiContext.getContext().setApiRequest(request);
            var observer = new AiResourceChangeProcessObserver(); observer.store = mock(AiResourceChangePlanStore.class);
            var config = new LaunchConfiguration("service.update", "service", "7", 77L, 0,
                    Map.of("name", "native-owner-change", "old", Map.of("name", "shared")));
            ProcessAuthorization.prepare(config, List.of(new ApiKeyProcessAuthorization(), observer));
            var metadata = ProcessAuthorization.metadata(config);
            assertEquals("loadBalancerService", metadata.get("targetType")); assertEquals("PUT", metadata.get("requestMethod"));
            assertEquals(77L, ((Number) metadata.get("accountId")).longValue()); assertEquals(42L, ((Number) metadata.get("principalAccountId")).longValue());
            observer.beforeExecution(config, metadata); verifyNoInteractions(observer.store);
            assertEquals(version, request.getSchemaVersion()); assertSame(request, ApiContext.getContext().getApiRequest());
        }
    }

    @Test public void loadBalancerStartStopUseOnlyFreshNativeActionsEmptyBodiesAndOriginalParentKeyCeilings() throws Exception {
        enabled(() -> {
            var f = new DomainFixture(); realKey(f, full());
            var action = mock(io.github.ibuildthecloud.gdapi.model.Action.class);
            when(f.schema.getResourceActions()).thenReturn(Map.of("activate", action, "deactivate", action));
            for (String operation : List.of("start", "stop")) {
                var descriptor = AiResourceChangeDescriptor.require("loadBalancerService", operation);
                assertEquals("POST", descriptor.method()); assertEquals("update", descriptor.keyOperation());
                assertEquals("start".equals(operation) ? "activate" : "deactivate", descriptor.action());
                assertEquals(Map.of(), descriptor.parameters(Map.of())); assertEquals(Map.of(), descriptor.body(Map.of()));
                for (String field : List.of("lbConfig", "launchConfig", "portRules", "ports", "certificateIds", "volumes", "scale", "action"))
                    assertEquals("ResourceChangeInputInvalid", assertThrows(ClientVisibleException.class,
                            () -> descriptor.parameters(Map.of(field, "UNAPPROVED"))).getCode());
                when(f.manager.resourceAction(eq("loadBalancerService"), any())).thenAnswer(call -> {
                    ApiRequest request = call.getArgument(1); assertEquals("1s7", request.getId());
                    assertEquals(descriptor.action(), request.getAction()); assertEquals(Map.of(), request.getRequestObject());
                    assertTrue(AiResourceChangeDomain.trustedDomain(request)); return f.row;
                });
                assertEquals(Map.of(), f.domain.prepare(f.request, descriptor, "1s7", Map.of()));
                assertSame(f.row, f.domain.dispatch(f.request, descriptor, "1s7", Map.of(), "own-plan"));
                when(f.schema.getResourceActions()).thenReturn(Map.of());
                assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                        () -> f.domain.prepare(f.request, descriptor, "1s7", Map.of())).getCode());
                when(f.schema.getResourceActions()).thenReturn(Map.of("activate", action, "deactivate", action));
                realKey(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                        List.of(new ApiKeyPolicy.Rule("lb-only", ApiKeyPolicy.Effect.ALLOW,
                                ApiKeyPolicy.Scope.resource("service", "1s7"), Set.of("read", "update"))), ApiKeyPolicy.Purpose.AI_BRIDGE));
                assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                        () -> f.domain.prepare(f.request, descriptor, "1s7", Map.of())).getCode()); realKey(f, full());
                var before = f.domain.snapshot(f.row); var reference = f.domain.loadBalancerMetadataContext(f.request, descriptor, "1s7");
                var plan = new LinkedHashMap<String, Object>(Map.of("status", "accepted", "resourceId", "1s7", "internalResourceId", 7L,
                        "snapshot", before, "referenceSnapshot", reference));
                when(f.policy.authorizeObject(f.row)).thenReturn(f.row);
                when(f.domain.objects.loadResource("loadBalancerService", 7L)).thenReturn(f.row);
                assertSame(f.row, f.domain.receiptTarget(f.request, descriptor, plan));
                f.stack.setUuid("replacement"); assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                        () -> f.domain.receiptTarget(f.request, descriptor, plan)).getCode()); f.stack.setUuid("stack-uuid");
            }
            verify(f.manager, times(2)).resourceAction(eq("loadBalancerService"), any());
            verify(f.manager, never()).create(anyString(), any()); verify(f.manager, never()).update(anyString(), anyString(), any());
        });
    }

    @Test public void loadBalancerLifecycleReceiptRequiresNativeReadinessStoppedInstancesAndEveryProtectedConfiguration() {
        var f = new DomainFixture(); f.domain.deploymentReadiness = mock(io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness.class);
        when(f.domain.deploymentReadiness.isReady(f.row)).thenReturn(true);
        io.cattle.platform.object.util.DataAccessor.fields(f.row).withKey("lbConfig").set(Map.of("portRules", List.of(Map.of("protocol",
                "tcp", "sourcePort", 8080,
                "targetPort", 80, "serviceId", "9")), "certificateIds", List.of("secret-certificate-reference")));
        io.cattle.platform.object.util.DataAccessor.fields(f.row).withKey("launchConfig").set(Map.of("imageUuid", "server-lb", "ports",
                List.of("8080:8080/tcp"),
                "dataVolumes", List.of("/data"), "volumeDriver", "driver"));
        var before = f.domain.snapshot(f.row); var start = AiResourceChangeDescriptor.require("loadBalancerService", "start");
        var stop = AiResourceChangeDescriptor.require("loadBalancerService", "stop");
        assertTrue(f.domain.complete(start, f.row, Map.of(), before));
        when(f.domain.deploymentReadiness.isReady(f.row)).thenReturn(false); assertFalse(f.domain.complete(start, f.row, Map.of(), before));
        when(f.domain.deploymentReadiness.isReady(f.row)).thenReturn(true);
        for (String field : List.of("lbConfig", "launchConfig", "secondaryLaunchConfigs", "scale", "ports", "certificateIds",
                "defaultCertificateId", "volumes", "volumeMounts")) {
            Object original = io.cattle.platform.object.util.DataAccessor.field(f.row, field, Object.class);
            io.cattle.platform.object.util.DataAccessor.fields(f.row).withKey(field).set(Map.of("changed", true));
            assertFalse(field, f.domain.complete(start, f.row, Map.of(), before));
            io.cattle.platform.object.util.DataAccessor.fields(f.row).withKey(field).set(original);
        }
        assertFalse(f.domain.complete(start, f.row, Map.of("scale", 2), before));
        f.row.setUuid("replacement"); assertFalse(f.domain.complete(start, f.row, Map.of(), before)); f.row.setUuid("lb-uuid");
        var instance = new io.cattle.platform.core.model.tables.records.InstanceRecord(); instance.setId(91L); instance.setAccountId(77L);
                instance.setState("running");
        var link = new io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord(); link.setId(92L); link.setInstanceId(91L); link.setManaged(true);
        when(f.domain.objects.find(io.cattle.platform.core.model.ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(link));
        when(f.domain.objects.loadResource(io.cattle.platform.core.model.Instance.class, 91L)).thenReturn(instance);
        before = f.domain.snapshot(f.row); f.row.setState("inactive");
        assertFalse(f.domain.complete(stop, f.row, Map.of(), before)); instance.setState("stopped");
        assertTrue(f.domain.complete(stop, f.row, Map.of(), before));
        f.row.setState("active"); assertTrue(f.domain.complete(start, f.row, Map.of(), before));
        f.row.setState("activating"); assertFalse(f.domain.complete(start, f.row, Map.of(), before));
        f.row.setRemoved(new java.util.Date()); assertFalse(f.domain.complete(start, f.row, Map.of(), before));
    }

    @Test public void loadBalancerLifecyclePlansKeepHumanApprovalConfigurationCasBoundKeyAndOneDispatch() throws Exception {
        for (String operation : List.of("start", "stop")) {
            var f = new AiResourceChangePlanServiceTest.Fixture(); f.target.setKind("loadBalancerService"); f.target.setUuid("lb-uuid");
            f.target.setStackId(3L); f.target.setState("start".equals(operation) ? "inactive" : "active");
            var owner = new AiResourceChangeDomain(); owner.json = f.json; owner.objects = mock(ObjectManager.class);
            when(f.domain.snapshot(any())).thenAnswer(call -> owner.snapshot(call.getArgument(0)));
            when(f.domain.loadBalancerMetadataContext(any(), any(), anyString())).thenReturn(Map.of("uuid", "stack-uuid", "name", "Blue"));
            doAnswer(call -> { f.dispatched++; return f.target; }).when(f.domain).dispatch(any(), any(), nullable(String.class), anyMap(), anyString());
            ApiRequest request = f.request(null, true); request.setRequestObject(Map.of("resourceType", "loadBalancerService", "operation", operation,
                    "resourceId", "1s7", "parameters", Map.of())); f.id = (String) f.service.preview(request).get("id");
            assertEquals(Map.of("uuid", "stack-uuid", "name", "Blue"), f.documents.get(f.id).get("referenceSnapshot"));
            assertEquals("ResourceChangeNeedsConfirmation", assertThrows(ClientVisibleException.class, () -> f.apply(true)).getCode());
            Map<String, Object> originalData = f.target.getData() == null ? null : f.json.readValue(f.json.writeValueAsString(f.target.getData()));
            io.cattle.platform.object.util.DataAccessor.fields(f.target).withKey("lbConfig").set(Map.of("changed", true));
            assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class, f::confirm).getCode());
            f.target.setData(originalData);
            f.writeAllowed = false; assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class, f::confirm).getCode());
            f.writeAllowed = true; f.confirm(); assertEquals("ResourceChangeBoundKeyRequired", assertThrows(ClientVisibleException.class,
                    () -> f.apply(false)).getCode());
            assertEquals("accepted", f.apply(true).get("status")); f.apply(true); assertEquals(1, f.claims); assertEquals(1, f.dispatched);
            f.complete = true; assertEquals(true, f.service.get(f.id, f.request(null, true)).get("verifiedResult"));
            assertTrue(f.audits.stream().anyMatch(value -> value.startsWith("preview:loadBalancerService:true:")));
            assertTrue(f.audits.stream().anyMatch(value -> value.startsWith("apply:loadBalancerService:true:")));
        }
    }

    @Test public void queuedLoadBalancerActivateDeactivateRetainFreshOwnerActionKeyRevisionAndRevocationBounds() throws Exception {
        for (String actionName : List.of("activate", "deactivate")) {
            var owner = mock(Policy.class); when(owner.authorizeObject(any())).thenAnswer(call -> call.getArgument(0));
            var schemas = mock(SchemaFactory.class); var schema = mock(io.github.ibuildthecloud.gdapi.model.Schema.class);
            var action = mock(io.github.ibuildthecloud.gdapi.model.Action.class);
            when(schemas.getSchema("loadBalancerService")).thenReturn(schema); when(schema.getResourceActions()).thenReturn(Map.of(actionName, action));
            var objects = mock(ObjectManager.class); when(objects.getSchemaFactory()).thenReturn(schemas);
            var credential = mock(io.cattle.platform.core.model.Credential.class); when(credential.getId()).thenReturn(7L);
            when(credential.getAccountId()).thenReturn(42L); when(credential.getKind()).thenReturn("apiKey"); when(credential.getState()).thenReturn("active");
            var row = row(); when(objects.loadResource(io.cattle.platform.core.model.Credential.class, 7L)).thenReturn(credential);
            when(objects.loadResource("loadBalancerService", 7L)).thenReturn(row); when(objects.loadResource("service", "7")).thenReturn(row);
            var authenticator = mock(io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.class);
            when(authenticator.currentAuthorization(eq(42L), eq(77L), any())).thenReturn(
                    new io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.CurrentAuthorization(owner, schemas));
            var targets = mock(ApiKeyTargetResolver.class); when(targets.parseScopeId("credential", "1c7")).thenReturn(7L);
            when(targets.resolveObject(anyString(), eq(row), eq(owner))).thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("service",
                    "1s7", "1a77", "1st3"));
            var hook = new ApiKeyProcessAuthorization(); inject(hook, "objectManager", objects); inject(hook, "authenticator", authenticator);
                    inject(hook, "targets", targets);
            var metadata = new LinkedHashMap<String, Object>(Map.of("keyId", "1c7", "policyRevision", 0L, "principalAccountId", 42L,
                    "accountId", 77L, "operation", "update", "targetType", "loadBalancerService", "targetId", "7", "requestMethod", "POST",
                            "requestCollection", false));
            metadata.put("requestAction", actionName); metadata.put("requestId", UUID.randomUUID().toString());
            var config = new LaunchConfiguration("service." + actionName, "service", "7", 77L, 0, Map.of());
            hook.beforeExecution(config, metadata); // Existing legacy virtual Full, without a typed bypass.
            var codec = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyCodec();
            var stored = codec.store(credential, generalFull(), 3); when(credential.getData()).thenReturn(stored); metadata.put("policyRevision", 3L);
            hook.beforeExecution(config, metadata);
            when(schema.getResourceActions()).thenReturn(Map.of());
            assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config,
                    metadata)).getCode());
            when(schema.getResourceActions()).thenReturn(Map.of(actionName, action)); when(owner.authorizeObject(row)).thenReturn(null);
            assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config,
                    metadata)).getCode());
            when(owner.authorizeObject(row)).thenReturn(row); metadata.put("policyRevision", 2L);
            assertEquals("ApiKeyPolicyChanged", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config,
                    metadata)).getCode());
            metadata.put("policyRevision", 3L); when(credential.getState()).thenReturn("removed");
            assertEquals("ApiKeyRevoked", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config, metadata)).getCode());
        }
    }

    private static AiResourceChangeDescriptor descriptor() { return AiResourceChangeDescriptor.require("loadBalancerService", "update_metadata"); }
    private static ApiKeyPolicy full() {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
    }
    private static ApiKeyPolicy generalFull() {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.GENERAL);
    }
    private static ServiceRecord row() {
        var row = new ServiceRecord(); row.setId(7L); row.setAccountId(77L); row.setUuid("lb-uuid"); row.setKind("loadBalancerService");
        row.setStackId(3L); row.setName("shared"); row.setState("active"); return row;
    }
    private static StackRecord stack() {
        var stack = new StackRecord(); stack.setId(3L); stack.setAccountId(77L); stack.setUuid("stack-uuid"); stack.setKind("stack");
        stack.setName("Blue"); stack.setState("active"); return stack;
    }
    private static class DomainFixture extends AiResourceChangeDomainTest.Fixture {
        final ServiceRecord row = row(); final StackRecord stack = stack();
        final ResourceManager manager = mock(ResourceManager.class), stackManager = mock(ResourceManager.class);
        DomainFixture() {
            when(schemas.getSchema("loadBalancerService")).thenReturn(schema); when(schemas.getSchema("stack")).thenReturn(schema);
            when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT")); var description = new FieldImpl(); description.setUpdate(true);
            when(schema.getResourceFields()).thenReturn(Map.of("name", name, "description", description));
            domain.managers = mock(ResourceManagerLocator.class); when(domain.managers.getResourceManagerByType("loadBalancerService")).thenReturn(manager);
            when(domain.managers.getResourceManagerByType("stack")).thenReturn(stackManager);
            when(manager.getById(eq("loadBalancerService"), eq("1s7"), any())).thenReturn(row);
            when(stackManager.getById(eq("stack"), eq("1st3"), any())).thenReturn(stack);
            when(domain.objects.loadResource(Service.class, 7L)).thenReturn(row);
            domain.targets = mock(ApiKeyTargetResolver.class); when(domain.targets.parseScopeId(anyString(), anyString())).thenAnswer(call ->
                    Long.parseLong(((String) call.getArgument(1)).replaceFirst("^1(?:st|s)", "")));
            var formatter = new DefaultIdFormatter();
            formatter.setSchemaFactory(schemas);
            when(schemas.getBaseType("loadBalancerService")).thenReturn("service");
            formatter.setTypeMappings(Map.of("service", "s", "stack", "st")); ApiContext.getContext().setIdFormatter(formatter);
            ((io.cattle.platform.core.model.Account) domain.objects.loadResource(io.cattle.platform.core.model.Account.class, 77L)).setName("Environment");
        }
    }
    private static void realKey(DomainFixture f, ApiKeyPolicy policy) throws Exception {
        var service = new ApiKeyAuthorizationService(); var targets = mock(ApiKeyTargetResolver.class);
        when(targets.resolve(any(), any())).thenAnswer(call -> {
            ApiRequest request = call.getArgument(0);
            return List.of(ApiKeyPolicyEvaluator.Target.projectResource("loadBalancerService".equals(request.getType()) ? "service" : request.getType(),
                    request.getId(), "1a77"));
        });
        inject(service, "targets", targets); inject(service, "auditSinks", List.of(mock(ApiKeyAuditSink.class))); f.domain.keyAccess = service;
        ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, policy));
    }
    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void enabled(Checked operation) throws Exception {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try { ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true); operation.run(); }
        finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }
    private interface Checked { void run() throws Exception; }
}
