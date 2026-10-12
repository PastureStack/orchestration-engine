package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.StackTable.STACK;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ApiResourceAccess;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.resource.AbstractObjectResourceManager;
import io.cattle.platform.api.resource.jooq.AbstractJooqResourceManager;
import io.cattle.platform.api.resource.jooq.DefaultJooqResourceManager;
import io.cattle.platform.core.model.tables.ServiceTable;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.meta.impl.DefaultObjectMetaDataManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Include;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Pagination;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.Sort;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.TypeUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.Condition;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.conf.Settings;
import org.jooq.conf.StatementType;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.After;
import org.junit.Test;

/** Real manager/query dispatch, with SQL capture rather than a real database lifecycle. */
public class ApiKeyMutationTargetLookupTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");

    @After public void cleanup() { ApiContext.remove(); }

    @Test public void v1EnvironmentUpdateOnlyLoadsExactOperandWithoutRead() throws Exception {
        positive("v1", "environment", "PUT", null, "update");
    }

    @Test public void v2StackUpdateOnlyLoadsExactOperandWithoutRead() throws Exception {
        positive("v2-beta", "stack", "PUT", null, "update");
    }

    @Test public void v1EnvironmentDeleteOnlyLoadsExactOperandWithoutRead() throws Exception {
        positive("v1", "environment", "DELETE", null, "delete");
    }

    @Test public void v2StackDeleteOnlyLoadsExactOperandWithoutRead() throws Exception {
        positive("v2-beta", "stack", "DELETE", null, "delete");
    }

    @Test public void postUpgradeUsesUpgradeNotLiteralPutUpdate() throws Exception {
        positive("v2-beta", "stack", "POST", "upgrade", "upgrade");
    }

    @Test public void composeProjectAliasUsesItsProvenStackRecordClass() throws Exception {
        positive("v2-beta", "composeProject", "PUT", null, "update");
    }

    @Test public void differentOrUnknownAliasRecordClassFallsBackToRead() throws Exception {
        Fixture f = new Fixture("v2-beta", "composeProject", "PUT", null, custom("update"));
        ListOptions options = ListOptions.forMutationTarget(f.request, "composeProject", "8");
        when(f.factory.getSchemaClass("composeProject")).thenReturn((Class) AccountRecord.class);
        assertEquals(render(f.service.constrain(f.request, f.factory, "stack", STACK)),
                render(f.service.constrainMutationTarget(f.request, f.factory, "stack", STACK,
                        options.getMutationTarget())));
        when(f.factory.getSchemaClass("composeProject")).thenReturn(null);
        assertEquals(render(f.service.constrain(f.request, f.factory, "stack", STACK)),
                render(f.service.constrainMutationTarget(f.request, f.factory, "stack", STACK,
                        options.getMutationTarget())));
        assertEquals(0, f.sql.size());
    }

    @Test public void upgradeGrantCannotAuthorizeLiteralPut() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "PUT", null, custom("upgrade"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.service.authorize(f.request, f.owner)).getCode());
        assertEquals(0, f.sql.size());
        assertEquals(0, f.manager.mutations);
    }

    @Test public void updateGrantCannotAuthorizePostUpgrade() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "POST", "upgrade", custom("update"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.service.authorize(f.request, f.owner)).getCode());
        // Also prove the target-loader operation is upgrade, not an assumed POST=create/update.
        assertNull(f.manager.resourceAction("stack", f.request));
        assertEquals("upgrade", f.access.operation);
        assertEquals(1, f.access.mutations);
        assertEquals(0, f.manager.mutations);
    }

    @Test public void plainReferenceAndCollectionReadsStayReadInsideSamePutRequest() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "PUT", null, custom("update"));
        f.service.authorize(f.request, f.owner);
        assertNotNull(f.manager.update("stack", "8", f.request));
        assertNull(f.manager.getById("stack", "8", new ListOptions(f.request)));
        assertNull(f.manager.observed.getMutationTarget());
        List<?> listed = (List<?>) f.manager.list("stack", f.request);
        assertTrue(listed.isEmpty());
        assertEquals(1, f.access.mutations);
        assertEquals(2, f.access.reads);
        assertEquals(1, f.manager.mutations);
        assertEquals("read", f.access.operation);
    }

    @Test public void publicGetWithoutReadStillDeniedBeforeLookup() throws Exception {
        Fixture f = new Fixture("v1", "environment", "GET", null, custom("update"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.service.authorize(f.request, f.owner)).getCode());
        assertEquals(0, f.sql.size());
    }

    @Test public void foreignStackIdAndExplicitDenyCannotBorrowAllowedOperand() throws Exception {
        Fixture foreign = new Fixture("v2-beta", "stack", "PUT", null, custom("update"));
        foreign.request.setId("9");
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> foreign.service.authorize(foreign.request, foreign.owner)).getCode());
        assertNull(foreign.manager.update("stack", "9", foreign.request));
        assertEquals(0, foreign.manager.mutations);
        Fixture denied = new Fixture("v2-beta", "stack", "PUT", null,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                        rule("allow", ApiKeyPolicy.Effect.ALLOW, "update"),
                        rule("deny", ApiKeyPolicy.Effect.DENY, "update"))));
        assertEquals("KeyPolicyDenied", assertThrows(ClientVisibleException.class,
                () -> denied.service.authorize(denied.request, denied.owner)).getCode());
        assertNull(denied.manager.update("stack", "8", denied.request));
        assertEquals(0, denied.manager.mutations);
    }

    @Test public void realOwnerRowGuardAndSchemaUpperBoundRemainEffective() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "PUT", null, custom("update"));
        f.row.setAccountId(999L);
        assertNull(f.owner.authorizeObject(f.row));
        assertNull(f.manager.update("stack", "8", f.request));
        assertEquals(0, f.manager.mutations);
        assertTrue(f.sql.get(0), f.sql.get(0).contains("`account_id` = 5"));
        f.row.setAccountId(5L);
        when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.service.authorize(f.request, f.owner)).getCode());
        assertEquals(1, f.sql.size());
    }

    @Test public void expiryAndClosedDenyBeforeSqlFetchOrMutation() throws Exception {
        for (ApiKeyPolicy policy : List.of(
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, NOW,
                        List.of(rule("allow", ApiKeyPolicy.Effect.ALLOW, "update"))),
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of()))) {
            Fixture f = new Fixture("v2-beta", "stack", "PUT", null, policy);
            String code = policy.getMode() == ApiKeyPolicy.Mode.CLOSED ? "KeyPolicyDenied" : "ApiKeyExpired";
            assertEquals(code, assertThrows(ClientVisibleException.class,
                    () -> f.manager.update("stack", "8", f.request)).getCode());
            assertEquals(0, f.sql.size());
            assertEquals(0, f.manager.mutations);
        }
    }

    @Test public void staleRequestOrLookupCriteriaMarkerFallsBackToRead() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "PUT", null, custom("update"));
        ListOptions old = ListOptions.forMutationTarget(f.request, "stack", "8");
        assertFalse(old.getMutationTarget().matches(f.request, "stack", "9"));
        assertNull(f.manager.getById("stack", "9", old));
        assertEquals(0, f.access.mutations);
        assertEquals(1, f.access.reads);
        ApiRequest replacement = new ApiRequest(null, f.factory);
        replacement.setType("stack"); replacement.setId("8"); replacement.setMethod("PUT");
        assertFalse(old.getMutationTarget().matches(replacement, "stack", "8"));
        f.request.setAction("upgrade");
        assertFalse(old.getMutationTarget().matches(f.request, "stack", "8"));
        assertNull(f.manager.getById("stack", "8", old));
        assertEquals(0, f.access.mutations);
        assertEquals(2, f.access.reads);
    }

    @Test public void unrelatedCoreTableAndUnknownActionNeverGainMutationReadBypass() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "PUT", null, custom("update"));
        ListOptions options = ListOptions.forMutationTarget(f.request, "stack", "8");
        assertEquals(render(f.service.constrain(f.request, f.factory, "service", ServiceTable.SERVICE)),
                render(f.service.constrainMutationTarget(f.request, f.factory, "service", ServiceTable.SERVICE,
                        options.getMutationTarget())));
        f.request.setMethod("POST"); f.request.setAction("futurePluginAction");
        assertNull(f.manager.resourceAction("stack", f.request));
        assertEquals("read", f.access.operation);
        assertEquals(0, f.manager.mutations);
    }

    @Test public void invalidDecodedOperandIdsFailClosedWithoutTypedScopeParserReuse() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "PUT", null, custom("update"));
        for (String invalid : List.of("0", "-1", "08", "1st8", " 8", "9223372036854775808")) {
            f.request.setId(invalid);
            ListOptions options = ListOptions.forMutationTarget(f.request, "stack", invalid);
            assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                    () -> f.service.constrainMutationTarget(f.request, f.factory, "stack", STACK,
                            options.getMutationTarget())).getCode());
        }
        assertEquals(0, f.sql.size());
        assertEquals(0, f.manager.mutations);
        verify(f.targets, never()).parseScopeId(anyString(), eq("8"));
    }

    @Test public void resourceActionKeepsEmptyLegacyOptionsAndNoAmbientScopeAfterFailure() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "POST", "upgrade", custom("upgrade"));
        f.request.setSort(mock(Sort.class));
        f.request.setPagination(new Pagination(1));
        f.request.setInclude(mock(Include.class));
        f.failNext = true;
        assertThrows(DataAccessException.class, () -> f.manager.resourceAction("stack", f.request));
        assertNotNull(f.manager.observed.getMutationTarget());
        assertNull(f.manager.observed.getSort());
        assertNull(f.manager.observed.getPagination());
        assertNull(f.manager.observed.getInclude());
        assertNull(f.manager.getById("stack", "8", new ListOptions()));
        assertNull(f.manager.observed.getMutationTarget());
        assertEquals(1, f.access.mutations);
        assertEquals(1, f.access.reads);
        assertEquals(0, f.manager.mutations);
    }

    @Test public void fullAndLegacyKeepExistingOwnerScopedSqlWithoutNewKeyPredicate() throws Exception {
        for (ApiKeyPolicy policy : new ApiKeyPolicy[]{null,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of())}) {
            Fixture f = new Fixture("v2-beta", "stack", "PUT", null, policy);
            f.service.authorize(f.request, f.owner);
            assertNotNull(f.manager.update("stack", "8", f.request));
            assertNull(f.access.condition);
            assertEquals(1, f.sql.size());
            assertTrue(f.sql.get(0), f.sql.get(0).contains("`account_id` = 5"));
            assertEquals(1, f.manager.mutations);
        }
    }

    private void positive(String version, String type, String method, String action, String operation) throws Exception {
        Fixture f = new Fixture(version, type, method, action, custom(operation));
        f.service.authorize(f.request, f.owner);
        Object result = "PUT".equals(method) ? f.manager.update(type, "8", f.request)
                : "DELETE".equals(method) ? f.manager.delete(type, "8", f.request)
                : f.manager.resourceAction(type, f.request);
        assertNotNull(result);
        assertEquals(1, f.sql.size());
        assertEquals(1, f.access.mutations);
        assertEquals(0, f.access.reads);
        assertEquals(operation, f.access.operation);
        assertEquals(type, f.manager.observed.getMutationTarget().type());
        assertEquals("8", f.manager.observed.getMutationTarget().id());
        assertEquals(1, f.manager.mutations);
        verify(f.targets, never()).parseScopeId(anyString(), eq("8"));
    }

    private static ApiKeyPolicy.Rule rule(String id, ApiKeyPolicy.Effect effect, String operation) {
        return new ApiKeyPolicy.Rule(id, effect, ApiKeyPolicy.Scope.stack("1st8"), Set.of(operation));
    }

    private static ApiKeyPolicy custom(String operation) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(rule("allow", ApiKeyPolicy.Effect.ALLOW, operation)));
    }

    private static long typedId(String kind, String id) {
        assertEquals("stack", kind);
        assertTrue(id, id.matches("1st[1-9][0-9]*"));
        return Long.parseLong(id.substring(3));
    }

    private static String render(Condition condition) {
        return condition == null ? "<legacy>" : DSL.using(SQLDialect.MARIADB,
                new Settings().withRenderSchema(false)).renderInlined(condition).replaceAll("\\s+", " ").toLowerCase();
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private static class StackMetadata extends DefaultObjectMetaDataManager {
        void register() { registerTableFields(STACK); }
    }

    private static class Fixture {
        final SchemaFactory factory = mock(SchemaFactory.class);
        final Schema schema = mock(Schema.class);
        final ApiRequest request;
        final AccountPolicy owner;
        final StackRecord row = new StackRecord();
        final ApiKeyTargetResolver targets = mock(ApiKeyTargetResolver.class);
        final ApiKeyAuthorizationService service = new ApiKeyAuthorizationService();
        final LookupManager manager = new LookupManager();
        final RecordingAccess access;
        final List<String> sql = new ArrayList<>();
        final ApiKeyPolicy policy;
        boolean failNext;

        Fixture(String version, String type, String method, String action, ApiKeyPolicy policy) throws Exception {
            this.policy = policy;
            when(schema.getId()).thenReturn("stack");
            when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT", "DELETE"));
            when(schema.getCollectionMethods()).thenReturn(List.of("GET", "POST"));
            when(schema.getResourceActions()).thenReturn(Map.of("upgrade", mock(Action.class)));
            when(factory.getSchema(anyString())).thenReturn(schema);
            when(factory.getSchema(StackRecord.class)).thenReturn(schema);
            when(factory.getSchemaClass(anyString())).thenReturn((Class) StackRecord.class);
            when(factory.getSchemaClass("service")).thenReturn((Class) ServiceRecord.class);
            when(factory.getSchemaName(StackRecord.class)).thenReturn("stack");
            request = new ApiRequest(null, factory);
            request.setVersion(version); request.setType(type); request.setId("8");
            request.setMethod(method); request.setAction(action);
            AccountRecord account = new AccountRecord(); account.setId(5L);
            owner = new AccountPolicy(account, account, Set.of(), new NoPolicyOptions());
            ApiContext.newContext().setApiRequest(request); ApiContext.getContext().setPolicy(owner);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 5, "apiKey", 1, policy));
            row.setId(8L); row.setAccountId(5L); row.setKind("stack"); row.setState("active");
            service.targets = targets; service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
            service.auditSinks = List.of(mock(ApiKeyAuditSink.class));
            service.objectManager = mock(ObjectManager.class);
            when(service.objectManager.getSchemaFactory()).thenReturn(factory);
            when(service.objectManager.getType(any())).thenReturn("stack");
            when(targets.parseScopeId(anyString(), anyString())).thenAnswer(a -> typedId(a.getArgument(0), a.getArgument(1)));
            when(targets.resolve(any(), any())).thenAnswer(a -> List.of(target(request.getId())));
            when(targets.references(any(), any())).thenReturn(List.of());
            when(targets.resolveObject(anyString(), any(), any())).thenAnswer(a -> target("8"));
            ObjectManager objects = mock(ObjectManager.class);
            when(objects.getSchemaFactory()).thenReturn(factory);
            manager.setObjectManager(objects);
            StackMetadata metadata = new StackMetadata(); metadata.setSchemaFactory(factory); metadata.register();
            manager.setMetaDataManager(metadata);
            access = new RecordingAccess(this);
            for (Class<?> clz : List.of(AbstractJooqResourceManager.class, AbstractObjectResourceManager.class)) {
                var field = clz.getDeclaredField("resourceAccess"); field.setAccessible(true); field.set(manager, List.of(access));
            }
            var infrastructure = AbstractJooqResourceManager.class.getDeclaredField("infraAccess");
            infrastructure.setAccessible(true);
            infrastructure.set(manager, mock(io.cattle.platform.iaas.api.infrastructure.InfrastructureAccessManager.class));
            var records = DSL.using(SQLDialect.MARIADB);
            manager.setConfiguration(DSL.using(new MockConnection(context -> {
                String emitted = context.sql().replaceAll("\\s+", " ").toLowerCase();
                sql.add(emitted);
                // A row mock must not mask the old READ-only SQL bug. Validate
                // the actual hook condition AND its presence in emitted SQL first.
                Condition expected = policy == null || policy.getMode() == ApiKeyPolicy.Mode.FULL ? null
                        : ApiKeyQueryScopes.condition(policy, "stack", STACK, access.operation,
                                ApiKeyMutationTargetLookupTest::typedId);
                if (expected != null && access.operand != null) expected = expected.and(STACK.ID.eq(Long.valueOf(access.operand.id())));
                assertEquals(render(expected), render(access.condition));
                assertTrue(emitted, emitted.contains("`account_id` = 5"));
                int predicates = occurrences(render(expected), "`environment`.`id` = ");
                if (manager.lookupId != null) {
                    assertTrue(emitted, emitted.contains("`environment`.`id` = " + manager.lookupId));
                    predicates++;
                }
                assertEquals(emitted, predicates, occurrences(emitted, "`environment`.`id` = "));
                assertTrue(emitted, emitted.contains("where"));
                if (failNext) { failNext = false; throw new java.sql.SQLException("test SQL failure"); }
                // This is an explicit persisted-row fixture/evaluator oracle, not
                // a claim that MockConnection executes SQL or verifies a real DB.
                boolean allowed = owner.authorizeObject(row) != null
                        && (manager.lookupId == null || manager.lookupId.equals("8"));
                if (policy != null) allowed &= new ApiKeyPolicyEvaluator().evaluate(policy,
                        new ApiKeyPolicyEvaluator.Request(true, access.operation, true, List.of(target("8"))), NOW).allowed();
                var result = records.newResult(STACK);
                if (allowed) result.add(row);
                return new MockResult[]{new MockResult(result.size(), result)};
            }), SQLDialect.MARIADB, new Settings().withRenderSchema(false)
                    .withStatementType(StatementType.STATIC_STATEMENT)).configuration());
        }

        ApiKeyPolicyEvaluator.Target target(String id) {
            return ApiKeyPolicyEvaluator.Target.stackResource("stack", "1st" + id, "1a5", "1st" + id);
        }
    }

    private static class RecordingAccess implements ApiResourceAccess {
        final Fixture f;
        int reads, mutations;
        String operation;
        Condition condition;
        ListOptions.MutationTarget operand;
        RecordingAccess(Fixture f) { this.f = f; }
        @Override public Condition constrain(ApiRequest request, SchemaFactory factory, String type, Table<?> table) {
            reads++; operation = "read"; operand = null;
            return condition = f.service.constrain(request, factory, type, table);
        }
        @Override public Condition constrainMutationTarget(ApiRequest request, SchemaFactory factory, String type,
                Table<?> table, ListOptions.MutationTarget target) {
            mutations++;
            assertTrue(target.matches(request, request.getType(), f.manager.lookupId));
            operation = ApiKeyOperations.of(request).registered() ? ApiKeyOperations.of(request).id() : "read";
            operand = ApiKeyOperations.of(request).registered() ? target : null;
            return condition = f.service.constrainMutationTarget(request, factory, type, table, target);
        }
        @Override public List<?> filterCollection(ApiRequest request, List<?> values) {
            return f.service.filterCollection(request, values);
        }
    }

    private static class LookupManager extends DefaultJooqResourceManager {
        ListOptions observed;
        String lookupId;
        int mutations;
        @Override protected Object getByIdInternal(String type, String id, ListOptions options) {
            observed = options; lookupId = id;
            return super.getByIdInternal(type, id, options);
        }
        @Override protected Object listInternal(SchemaFactory factory, String type, Map<Object, Object> criteria,
                ListOptions options) {
            lookupId = (String) criteria.get(TypeUtils.ID_FIELD);
            return super.listInternal(factory, type, criteria, options);
        }
        @Override protected Object updateInternal(String type, String id, Object object, ApiRequest request) {
            mutations++; return object;
        }
        @Override protected Object deleteInternal(String type, String id, Object object, ApiRequest request) {
            mutations++; return object;
        }
        @Override protected Object resourceActionInternal(Object object, ApiRequest request) {
            mutations++; return object;
        }
    }
}
