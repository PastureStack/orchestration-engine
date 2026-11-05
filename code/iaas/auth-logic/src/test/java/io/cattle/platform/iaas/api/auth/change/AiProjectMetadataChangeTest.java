package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.*;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.sql.DriverManager;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.After;
import org.junit.Test;

public class AiProjectMetadataChangeTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void projectDescriptorIsMetadataOnlyAndKeepsNativeTextBounds() {
        var d = descriptor(); assertEquals("PUT", d.method()); assertNull(d.action()); assertEquals("update", d.keyOperation());
        assertEquals(Map.of("name", "x".repeat(255)), d.parameters(Map.of("name", "x".repeat(255))));
        var nullable = new LinkedHashMap<String, Object>(); nullable.put("description", null);
        assertTrue(d.parameters(nullable).containsKey("description")); assertNull(d.parameters(nullable).get("description"));
        for (Object input : List.of(Map.of(), Map.of("name", ""), Map.of("name", "x".repeat(256)),
                Map.of("description", "x".repeat(1025)), Map.of("name", 1), Map.of("description", false),
                Map.of("members", List.of()), Map.of("labels", Map.of()), Map.of("networkId", "7"), Map.of("state", "active")))
            assertEquals("ResourceChangeInputInvalid", assertThrows(ClientVisibleException.class, () -> d.parameters(input)).getCode());
        for (String op : List.of("create", "remove", "start", "stop", "restart", "archive", "restore", "update_targets"))
            assertEquals("ResourceChangeUnsupported", assertThrows(ClientVisibleException.class,
                    () -> AiResourceChangeDescriptor.require("project", op)).getCode());
    }

    @Test public void selectedAccountIdentityAndScalarSnapshotNeverCopyConfigurationOrCredentials() {
        AccountRecord project = project(77); project.setDescription(null);
        project.setData(Map.of("members", "PRIVATE", "network", "PRIVATE", "authConfig", "PRIVATE"));
        assertSame(project, AiProjectMetadataChange.selected(project, 77));
        Map<String, Object> before = AiProjectMetadataChange.snapshot(project);
        assertEquals(Set.of("id", "uuid", "kind", "name", "description", "state", "removed"), before.keySet());
        assertNull(before.get("description")); assertFalse(before.toString().contains("PRIVATE"));
        project.setName("literal <script> فارسی"); assertNotEquals(before, AiProjectMetadataChange.snapshot(project));
        assertEquals("literal <script> فارسی", AiProjectMetadataChange.snapshot(project).get("name"));
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> AiProjectMetadataChange.selected(project, 88)).getStatus());
        project.setKind("user"); assertThrows(ClientVisibleException.class, () -> AiProjectMetadataChange.selected(project, 77));
        project.setKind("project"); project.setRemoved(new Date()); assertThrows(ClientVisibleException.class,
                () -> AiProjectMetadataChange.selected(project, 77));
        project.setRemoved(null); project.setState("inactive"); assertThrows(ClientVisibleException.class,
                () -> AiProjectMetadataChange.selected(project, 77));
        project.setState("active"); project.setUuid(null); assertThrows(ClientVisibleException.class,
                () -> AiProjectMetadataChange.selected(project, 77));
    }

    @Test public void metadataOwnerUsesNativeProjectOwnerAndGlobalSchemaWithoutGrantingObjects() {
        var f = new Fixture(); assertNull(f.global.authorizeObject(f.project));
        f.domain.projectMetadata.authorize(f.project, f.global, f.schema, true, Set.of("name"));
        verify(f.dao).isProjectOwner(77L, 42L, false, f.global.getIdentities());
        assertNull(f.global.authorizeObject(f.project));
        when(f.dao.isProjectOwner(anyLong(), anyLong(), anyBoolean(), anySet())).thenReturn(false);
        assertEquals(403, assertThrows(ClientVisibleException.class,
                () -> f.domain.projectMetadata.authorize(f.project, f.global, f.schema, true, Set.of("name"))).getStatus());
        when(f.dao.isProjectOwner(anyLong(), anyLong(), anyBoolean(), anySet())).thenReturn(true);
        when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
        assertEquals(403, assertThrows(ClientVisibleException.class,
                () -> f.domain.projectMetadata.authorize(f.project, f.global, f.schema, true, Set.of("name"))).getStatus());
        verify(f.manager, never()).update(anyString(), anyString(), any());
    }

    @Test public void selectedProjectReadAndPreflightUseFreshGlobalNativeOwnerThenRestoreSelectedContext() {
        var f = new Fixture();
        assertSame(f.project, f.domain.visible(f.request, descriptor(), "77"));
        assertEquals(Map.of("name", "after"), f.domain.prepare(f.request, descriptor(), "77", Map.of("name", "after")));
        verify(f.domain.authenticator, times(2)).currentAuthorization(eq(42L), eq(42L), any());
        assertSame(f.selected, ApiContext.getContext().getPolicy()); assertSame(f.request, ApiContext.getContext().getApiRequest());
        assertEquals(404, assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor(), "88", Map.of("name", "after"))).getStatus());
        verify(f.manager, never()).update(anyString(), anyString(), any());
        when(f.dao.isProjectOwner(anyLong(), anyLong(), anyBoolean(), anySet())).thenReturn(false);
        assertEquals(403, assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor(), "77", Map.of("name", "after"))).getStatus());
        assertSame(f.selected, ApiContext.getContext().getPolicy());
    }

    @Test public void customReadKeyDoesNotBorrowGlobalOwnerWriteAuthority() throws Exception {
        var f = new Fixture(); var keyAccess = new ApiKeyAuthorizationService(); var targets = mock(ApiKeyTargetResolver.class);
        when(targets.resolve(any(), any())).thenReturn(List.of(ApiKeyPolicyEvaluator.Target.projectResource("project", "1a77", "1a77")));
        inject(keyAccess, "targets", targets); inject(keyAccess, "auditSinks", List.of(mock(io.cattle.platform.api.auth.ApiKeyAuditSink.class)));
        f.domain.keyAccess = keyAccess;
        // actor() has already independently freshened this selected context; this test isolates the normal domain Key ceiling.
        f.domain.keys = mock(AiKeyAuthorization.class); when(f.domain.keys.actor(any())).thenReturn(f.selected);
        var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("exact-project-read", ApiKeyPolicy.Effect.ALLOW,
                        ApiKeyPolicy.Scope.resource("project", "1a77"), Set.of("read"))), ApiKeyPolicy.Purpose.GENERAL);
        ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, policy));
        assertSame(f.project, f.domain.visible(f.request, descriptor(), "77"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor(), "77", Map.of("name", "after"))).getCode());
        verify(f.manager, never()).update(anyString(), anyString(), any());
    }

    @Test public void durableAccountUpdateBindingUsesSelectedRootAndSeparateNativePrincipal() throws Exception {
        try (var f = new ProcessFixture(true)) {
            assertEquals(77L, ((Number) f.metadata.get("accountId")).longValue());
            assertEquals(42L, ((Number) f.metadata.get("principalAccountId")).longValue());
            assertEquals(List.of("name"), f.metadata.get("projectUpdateFields"));
            assertEquals(List.of("description", "name"), f.store.execution(f.id).get("metadataFields"));
            assertNull(f.fixture.global.authorizeObject(f.fixture.project));
            f.before(f.execution, f.metadata); assertNull(f.fixture.global.authorizeObject(f.fixture.project));
            assertEquals(991L, ((Number) f.store.execution(f.id).get("rootProcessId")).longValue());
            assertEquals(1, f.scheduled.get()); assertFalse(f.store.claim(f.plan));
            var privatePlan = f.store.load(f.id); Object approved = privatePlan.get("parameters");
            privatePlan.put("parameters", Map.of("name", "different-private-value")); f.store.save(privatePlan);
            f.observerDenied(f.execution, f.metadata); privatePlan.put("parameters", approved); f.store.save(privatePlan);
            ((FieldImpl) f.fixture.schema.getResourceFields().get("name")).setUpdate(false);
            f.denied("OwnerPermissionDenied", f.execution, f.metadata);
            ((FieldImpl) f.fixture.schema.getResourceFields().get("name")).setUpdate(true);
            when(f.fixture.dao.isProjectOwner(anyLong(), anyLong(), anyBoolean(), anySet())).thenReturn(false);
            f.denied("OwnerPermissionDenied", f.execution, f.metadata);
            when(f.fixture.dao.isProjectOwner(anyLong(), anyLong(), anyBoolean(), anySet())).thenReturn(true);
            f.credential.setRemoved(new Date()); f.denied("ApiKeyRevoked", f.execution, f.metadata); f.credential.setRemoved(null);
            var readOnly = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                    List.of(new ApiKeyPolicy.Rule("exact-read", ApiKeyPolicy.Effect.ALLOW,
                            ApiKeyPolicy.Scope.resource("project", "1a77"), Set.of("read"))), ApiKeyPolicy.Purpose.GENERAL);
            f.credential.setData(new ApiKeyPolicyCodec().store(f.credential, readOnly, 3)); f.denied("KeyScopeDenied", f.execution, f.metadata);
            assertNull(f.fixture.global.authorizeObject(f.fixture.project));
            f.request.setRequestObject(Map.of("name", "not-approved"));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(IllegalStateException.class, () -> f.observer.dispatch(f.request, f.id, () -> calls.incrementAndGet()));
            assertEquals(0, calls.get()); assertEquals(1, f.scheduled.get());
        }
    }

    @Test public void accountRootLockUsesTheActualAccountRowAndDetectsConcurrentMetadataChange() throws Exception {
        String url = "jdbc:h2:mem:project-lock-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000";
        try (var owner = DriverManager.getConnection(url); var ai = DriverManager.getConnection(url); var ui = DriverManager.getConnection(url);
                var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var sql = DSL.using(owner, SQLDialect.H2); var table = io.cattle.platform.core.model.tables.AccountTable.ACCOUNT;
            sql.createSchemaIfNotExists("cattle").execute();
            sql.createTable(table).columns(Arrays.stream(table.fields()).map(field -> DSL.field(field.getUnqualifiedName(),
                    field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            sql.insertInto(table).set(table.ID, 77L).set(table.UUID, "project-77").set(table.KIND, "project")
                    .set(table.STATE, "active").set(table.NAME, "before").set(table.DATA, Map.of("authConfig", "PRIVATE")).execute();
            ai.setAutoCommit(false); ui.setAutoCommit(false); var domain = new AiResourceChangeDomain(); domain.json = new JacksonJsonMapper();
            domain.setConfiguration(DSL.using(ai, SQLDialect.H2).configuration());
            Account locked = (Account) domain.lock("project", 77); assertSame(locked, AiProjectMetadataChange.selected(locked, 77));
            Map<String, Object> before = domain.snapshot(locked); assertFalse(before.toString().contains("PRIVATE"));
            var attempted = new java.util.concurrent.CountDownLatch(1);
            var change = pool.submit(() -> { attempted.countDown(); return ui.createStatement()
                    .executeUpdate("UPDATE cattle.account SET name='changed' WHERE id=77"); });
            assertTrue(attempted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> change.get(150, java.util.concurrent.TimeUnit.MILLISECONDS));
            ai.commit(); assertEquals(1, change.get(3, java.util.concurrent.TimeUnit.SECONDS).intValue()); ui.commit();
            assertNotEquals(before, domain.snapshot(domain.lock("project", 77))); ai.commit();
        }
    }

    @Test public void typedProjectProcessCannotFallbackWhenDurableBindingIsMissingOrMismatched() throws Exception {
        try (var f = new ProcessFixture(true)) {
            // The native Full owner succeeds independently; the missing AI claim still cannot reach execution.
            assertNull(f.fixture.global.authorizeObject(f.fixture.project)); f.authorizer.beforeExecution(f.execution, f.metadata);
            var changedBinding = new LinkedHashMap<>(f.store.execution(f.id)); changedBinding.put("resourceType", "service");
            f.sql.update(AiResourceChangePlanStore.TABLE).setNull(AiResourceChangePlanStore.EXECUTION).execute();
            f.observerDenied(f.execution, f.metadata);
            f.sql.update(AiResourceChangePlanStore.TABLE).set(AiResourceChangePlanStore.EXECUTION,
                    f.store.json.writeValueAsString(changedBinding)).execute(); f.observerDenied(f.execution, f.metadata);
            f.sql.deleteFrom(AiResourceChangePlanStore.TABLE).execute(); f.observerDenied(f.execution, f.metadata);
            var changed = new LinkedHashMap<>(f.metadata); changed.put("targetType", "service"); f.observerDenied(f.execution, changed);
            var nonProject = new LinkedHashMap<>(f.metadata); nonProject.put("targetType", "service"); nonProject.put("targetId", "55");
            f.observer.beforeExecution(new LaunchConfiguration("service.update", "service", "55", 77L, 0, Map.of()), nonProject);
            assertEquals(1, f.scheduled.get());
        }
        try (var ordinary = new ProcessFixture(false)) {
            ordinary.before(ordinary.execution, ordinary.metadata);
            assertNull(ordinary.fixture.global.authorizeObject(ordinary.fixture.project));
            assertEquals(77L, ((Number) ordinary.metadata.get("accountId")).longValue());
            assertNull(ordinary.store.load(ordinary.id)); assertEquals(1, ordinary.scheduled.get());
        }
    }

    @Test public void unknownProjectExecutionKeepsOnlyItsOriginalDurableProcessWithoutReplayOrTerminalAuthority() throws Exception {
        try (var f = new ProcessFixture(true)) {
            var plan = f.store.load(f.id); plan.put("status", "unknown"); plan.put("dispatchAcknowledged", false);
            plan.put("verifiedResult", false); f.store.save(plan); f.before(f.execution, f.metadata); f.before(f.execution, f.metadata);
            assertEquals(991L, ((Number) f.store.execution(f.id).get("rootProcessId")).longValue()); assertFalse(f.store.claim(f.plan));
            var other = f.insertProcess(992L); f.observerDenied(other, ProcessAuthorization.metadata(other));
            for (String state : List.of("preview", "cancelled", "failed", "succeeded")) {
                f.sql.update(AiResourceChangePlanStore.TABLE).set(AiResourceChangePlanStore.STATE, state).execute();
                f.observerDenied(f.execution, f.metadata);
            }
            f.sql.update(AiResourceChangePlanStore.TABLE).set(AiResourceChangePlanStore.STATE, "unknown")
                    .set(AiResourceChangePlanStore.FAILURE, AiResourceChangePlanStore.AUTHORIZATION_DENIED).execute();
            f.observerDenied(f.execution, f.metadata); f.sql.update(AiResourceChangePlanStore.TABLE).setNull(AiResourceChangePlanStore.FAILURE).execute();
            var table = io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;
            f.sql.update(table).set(table.END_TIME, new Date()).where(table.ID.eq(991L)).execute(); f.observerDenied(f.execution, f.metadata);
            f.sql.update(table).setNull(table.END_TIME).where(table.ID.eq(991L)).execute();
            f.credential.setRemoved(new Date()); f.denied("ApiKeyRevoked", f.execution, f.metadata); f.credential.setRemoved(null);
            var service = new AiResourceChangePlanService(); service.store = f.store; service.domain = mock(AiResourceChangeDomain.class);
            var unchanged = f.store.load(f.id); service.observe(unchanged, f.request);
            assertEquals("unknown", unchanged.get("status")); assertEquals(false, unchanged.get("verifiedResult"));
            verifyNoInteractions(service.domain); assertEquals(1, f.scheduled.get());
        }
    }

    private static final class ProcessFixture implements AutoCloseable {
        final java.sql.Connection connection;
        final org.jooq.DSLContext sql;
        final Fixture fixture = new Fixture();
        final AiResourceChangePlanStore store = new AiResourceChangePlanStore();
        final AiResourceChangeProcessObserver observer = new AiResourceChangeProcessObserver();
        final ApiKeyProcessAuthorization authorizer = new ApiKeyProcessAuthorization();
        final io.cattle.platform.core.model.tables.records.CredentialRecord credential =
                new io.cattle.platform.core.model.tables.records.CredentialRecord();
        final String id;
        final Map<String, Object> plan = new LinkedHashMap<>();
        final ApiRequest request;
        final LaunchConfiguration config;
        final java.util.concurrent.atomic.AtomicInteger scheduled = new java.util.concurrent.atomic.AtomicInteger();
        final io.cattle.platform.engine.manager.impl.jooq.JooqProcessRecordDao processDao =
                new io.cattle.platform.engine.manager.impl.jooq.JooqProcessRecordDao();
        final io.cattle.platform.engine.manager.impl.ProcessRecord execution;
        final Map<String, Object> metadata;
        ProcessFixture(boolean ai) throws Exception {
            id = (ai ? "aichange-" : "") + UUID.randomUUID();
            connection = DriverManager.getConnection("jdbc:h2:mem:project-native-ai-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
            sql = DSL.using(connection, SQLDialect.H2);
            sql.execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,document CLOB,state VARCHAR(32),"
                    + "actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            store.json = new JacksonJsonMapper(); store.setConfiguration(sql.configuration());
            var approved = new LinkedHashMap<String, Object>(); approved.put("name", "after"); approved.put("description", null);
            plan.putAll(Map.of("id", id, "actorId", 42L, "accountId", 77L, "credentialId", 7L, "credentialRevision", 3L,
                    "credentialKind", "apiKeyRestricted", "resourceType", "project", "operation", "update_metadata", "status", "preview"));
            plan.put("internalResourceId", 77L); plan.put("resourceId", "1a77"); plan.put("expiresAt", "2030-01-01T00:00:00Z");
            plan.put("snapshot", AiProjectMetadataChange.snapshot(fixture.project)); plan.put("parameters", approved);
            if (ai) { store.insert(plan); plan.put("status", "dispatching"); assertTrue(store.claim(plan)); }
            observer.store = store; observer.objects = fixture.domain.objects; observer.targets = mock(ApiKeyTargetResolver.class);
            when(observer.targets.parseScopeId("credential", "1c7")).thenReturn(7L);
            when(observer.targets.parseScopeId("project", "77")).thenReturn(77L);
            when(observer.targets.resolveObject(eq("project"), same(fixture.project), any())).thenReturn(
                    ApiKeyPolicyEvaluator.Target.projectResource("project", "1a77", "1a77"));
            when(observer.objects.loadResource("account", 77L)).thenReturn(fixture.project);
            when(observer.objects.loadResource("account", "77")).thenReturn(fixture.project);
            when(observer.objects.loadResource("project", 77L)).thenReturn(fixture.project);
            request = new ApiRequest(null, fixture.schemas); request.setType("project"); request.setMethod("PUT"); request.setId("77");
            request.setRequestObject(new LinkedHashMap<>(approved));
            ApiContext.getContext().setPolicy(fixture.global); ApiContext.getContext().setApiRequest(request);
            var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.GENERAL);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, policy));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.keyId", "1c7"); request.setAttribute("apiKey.audit.operation", "update");
            credential.setId(7L); credential.setAccountId(42L); credential.setKind("apiKeyRestricted"); credential.setState("active");
            credential.setData(new ApiKeyPolicyCodec().store(credential, policy, 3));
            when(observer.objects.loadResource(io.cattle.platform.core.model.Credential.class, 7L)).thenReturn(credential);
            inject(authorizer, "objectManager", observer.objects); inject(authorizer, "authenticator", fixture.domain.authenticator);
            inject(authorizer, "targets", observer.targets); inject(authorizer, "projectAccess", fixture.domain.projectMetadata.projectAccess);
            var manager = new NativeProjectManager(); inject(manager, "authDao", fixture.dao);
            inject(manager, "projectAccess", fixture.domain.projectMetadata.projectAccess); manager.setObjectManager(observer.objects);
            ((FieldImpl) fixture.schema.getResourceFields().get("name")).getAttributes().put("scheduleUpdate", true);
            ((FieldImpl) fixture.schema.getResourceFields().get("description")).getAttributes().put("scheduleUpdate", true);
            when(observer.objects.setFields(same(fixture.schema), same(fixture.project), anyMap())).thenAnswer(call -> {
                Map<String, Object> fields = call.getArgument(2);
                if (fields.containsKey("name")) fixture.project.setName((String) fields.get("name"));
                if (fields.containsKey("description")) fixture.project.setDescription((String) fields.get("description"));
                return fixture.project;
            });
            when(observer.objects.reload(same(fixture.project))).thenReturn(fixture.project);
            var processes = mock(io.cattle.platform.object.process.ObjectProcessManager.class); manager.setObjectProcessManager(processes);
            var scheduledProcess = new java.util.concurrent.atomic.AtomicReference<LaunchConfiguration>();
            doAnswer(call -> {
                assertEquals(io.cattle.platform.object.process.StandardProcess.UPDATE, call.getArgument(0)); scheduled.incrementAndGet();
                var actual = new LaunchConfiguration("account.update", "account", "77", null, 0, new LinkedHashMap<>(call.getArgument(2)));
                ProcessAuthorization.prepare(actual, List.of(authorizer, observer)); scheduledProcess.set(actual); return null;
            }).when(processes).scheduleStandardProcess(eq(io.cattle.platform.object.process.StandardProcess.UPDATE), same(fixture.project), anyMap());
            if (ai) observer.dispatch(request, id, () -> manager.apply(fixture.project, request));
            else manager.apply(fixture.project, request);
            config = scheduledProcess.get(); assertNotNull("normal native superclass scheduled the actual update", config);
            var table = io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;
            sql.createSchemaIfNotExists("cattle").execute();
            sql.createTable(table).columns(Arrays.stream(table.fields()).map(field -> DSL.field(field.getUnqualifiedName(),
                    field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            when(observer.objects.loadResource(eq(io.cattle.platform.core.model.ProcessInstance.class), anyLong())).thenAnswer(call ->
                    sql.selectFrom(table).where(table.ID.eq((Long) call.getArgument(1))).fetchOne());
            processDao.setConfiguration(sql.configuration()); execution = insertProcess(991L); metadata = ProcessAuthorization.metadata(execution);
            assertTrue(ProcessAuthorization.executionBound(execution));
        }
        io.cattle.platform.engine.manager.impl.ProcessRecord insertProcess(long number) {
            var table = io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;
            sql.insertInto(table).set(table.ID, number).set(table.PROCESS_NAME, "account.update").set(table.RESOURCE_TYPE, "account")
                    .set(table.RESOURCE_ID, "77").set(table.DATA, config.getData()).set(table.EXECUTION_COUNT, 0L).execute();
            return processDao.getRecord(number);
        }
        void before(LaunchConfiguration process, Map<String, Object> values) {
            authorizer.beforeExecution(process, values); observer.beforeExecution(process, values);
        }
        void denied(String code, LaunchConfiguration process, Map<String, Object> values) {
            assertEquals(code, assertThrows(io.cattle.platform.engine.process.ProcessAuthorizationDeniedException.class,
                    () -> before(process, values)).getCode());
        }
        void observerDenied(LaunchConfiguration process, Map<String, Object> values) {
            assertEquals("OwnerPermissionDenied", assertThrows(io.cattle.platform.engine.process.ProcessAuthorizationDeniedException.class,
                    () -> observer.beforeExecution(process, values)).getCode());
        }
        @Override public void close() throws java.sql.SQLException { connection.close(); }
    }

    private static final class NativeProjectManager extends io.cattle.platform.iaas.api.auth.projects.ProjectResourceManager {
        Object apply(Account project, ApiRequest request) { return super.updateInternal("project", "77", project, request); }
        @Override protected io.github.ibuildthecloud.gdapi.model.Resource createResource(Object object,
                io.github.ibuildthecloud.gdapi.id.IdFormatter formatter, ApiRequest request) {
            return new io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl("77", "project", AiProjectMetadataChange.snapshot((Account) object));
        }
    }

    private static AiResourceChangeDescriptor descriptor() { return AiResourceChangeDescriptor.require("project", "update_metadata"); }
    private static AccountRecord project(long id) {
        var value = new AccountRecord(); value.setId(id); value.setUuid("project-" + id); value.setKind("project");
        value.setState("active"); value.setName("project"); return value;
    }
    private static void inject(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    private static final class Fixture {
        final AiResourceChangeDomain domain = new AiResourceChangeDomain(); final AccountRecord project = project(77), actor = project(42);
        final Policy selected, global; final AuthDao dao = mock(AuthDao.class); final SchemaFactory schemas = mock(SchemaFactory.class);
        final Schema schema = mock(Schema.class); final ResourceManager manager = mock(ResourceManager.class);
        final ApiRequest request = new ApiRequest(null, schemas);
        Fixture() {
            actor.setKind("user"); selected = new AccountPolicy(project, actor, Set.of(), new NoPolicyOptions());
            global = new AccountPolicy(actor, actor, Set.of(), new NoPolicyOptions());
            domain.keys = new AiKeyAuthorization(); domain.objects = mock(ObjectManager.class);
            when(domain.objects.loadResource(Account.class, 42L)).thenReturn(actor); when(domain.objects.loadResource(Account.class, 77L)).thenReturn(project);
            domain.projectMetadata = new AiProjectMetadataChange();
            domain.projectMetadata.projectAccess = new io.cattle.platform.iaas.api.auth.projects.ProjectAccessService(dao,
                    mock(io.cattle.platform.core.dao.AccountDao.class));
            when(dao.isProjectOwner(anyLong(), anyLong(), anyBoolean(), anySet())).thenReturn(true);
            domain.authenticator = mock(ApiAuthenticator.class);
            when(domain.authenticator.currentAuthorization(eq(42L), eq(42L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(global, schemas));
            when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT")); when(schemas.getSchema("project")).thenReturn(schema);
            var name = new FieldImpl(); name.setUpdate(true); var description = new FieldImpl(); description.setUpdate(true);
            when(schema.getResourceFields()).thenReturn(Map.of("name", name, "description", description));
            domain.validation = mock(io.github.ibuildthecloud.gdapi.validation.ValidationHandler.class);
            domain.keyAccess = new ApiKeyAuthorizationService(); domain.targets = mock(ApiKeyTargetResolver.class);
            when(domain.targets.parseScopeId(eq("project"), anyString())).thenAnswer(call -> Long.parseLong(call.getArgument(1)));
            domain.managers = mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator.class);
            when(domain.managers.getResourceManagerByType("project")).thenReturn(manager);
            when(manager.getById(eq("project"), eq("77"), any())).thenReturn(project); domain.json = new JacksonJsonMapper();
            ApiContext.newContext().setPolicy(selected); ApiContext.getContext().setApiRequest(request);
            var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("account", "a", "project", "a", "credential", "c")); ApiContext.getContext().setIdFormatter(formatter);
        }
    }
}
