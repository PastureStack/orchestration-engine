package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import static io.cattle.platform.core.model.tables.InstanceLinkTable.INSTANCE_LINK;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.*;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.InstanceTable;
import io.cattle.platform.core.model.tables.ServiceTable;
import io.cattle.platform.core.model.tables.records.*;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.After;
import org.junit.Test;

/** Exact native Link adapter coverage; no native HTTP, workload or broad suite. */
public class ApiKeyInstanceLinkScopeTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void nativeFrozenSchemaAlreadyExposesKnownLinkReadAndReferenceUpdate() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/v1/owner.ser"))) root = root.getParent();
        assertNotNull(root);
        for (String role : List.of("owner", "project")) {
            try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(root.resolve("resources/content/schema/v1/" + role + ".ser")))) {
                Schema link = ((List<?>) input.readObject()).stream().map(Schema.class::cast)
                        .filter(s -> "instanceLink".equals(s.getId())).findFirst().orElseThrow();
                assertEquals("instanceLinks", link.getPluralName());
                assertTrue(link.getCollectionMethods().contains("GET"));
                assertTrue(link.getResourceMethods().containsAll(List.of("GET", "PUT")));
                assertFalse(link.getResourceFields().get("instanceId").isUpdate());
                assertTrue(link.getResourceFields().get("targetInstanceId").isUpdate());
                assertEquals("reference[instance]", link.getResourceFields().get("targetInstanceId").getType());
            }
        }
    }

    @Test public void ownedLinkReadUsesItsExactPersistedProjectStackResourceAndGlobalScopes() {
        for (String version : List.of("v1", "v2-beta")) {
            Fixture f = new Fixture(version);
            for (ApiKeyPolicy.Scope scope : List.of(ApiKeyPolicy.Scope.project("1a5"), ApiKeyPolicy.Scope.stack("1st8"),
                    ApiKeyPolicy.Scope.resource("instanceLink", "1il9"), ApiKeyPolicy.Scope.global())) {
                f.authorize(policy(ApiKeyPolicy.Effect.DENY, allow(scope, "read")));
                assertEquals("ALLOW", f.request.getAttribute("apiKey.audit.decision"));
                assertEquals("read", f.request.getAttribute("apiKey.audit.operation"));
                assertEquals("1il9", f.request.getAttribute("apiKey.audit.targetId"));
                assertSame(f.owner, ApiContext.getContext().getPolicy());
            }
            var target = f.target(f.link);
            assertEquals("instanceLink", target.resourceType());
            assertEquals("1il9", target.resourceId());
            assertEquals("1a5", target.projectId());
            assertEquals("1st8", target.stackId());
        }
    }

    @Test public void exactLinkReadDoesNotGrantOrdinaryParentContainerRead() {
        Fixture f = new Fixture("v2-beta");
        ApiKeyPolicy key = policy(ApiKeyPolicy.Effect.DENY, allow(ApiKeyPolicy.Scope.resource("instanceLink", "1il9"), "read"));
        f.authorize(key);
        f.request.setType("container"); f.request.setId("1i3");
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () -> f.authorize(key)).getCode());
    }

    @Test public void wrongProjectStackOrExactTypeAndIdCannotReadLink() {
        Fixture f = new Fixture("v2-beta");
        for (ApiKeyPolicy.Scope scope : List.of(ApiKeyPolicy.Scope.project("1a6"), ApiKeyPolicy.Scope.stack("1st7"),
                ApiKeyPolicy.Scope.resource("instanceLink", "1il10"), ApiKeyPolicy.Scope.resource("container", "1i3"))) {
            ApiKeyPolicy key = policy(ApiKeyPolicy.Effect.DENY, allow(scope, "read"));
            assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () -> f.authorize(key)).getCode());
        }
    }

    @Test public void explicitLinkOrInheritedStackDenyWinsEvenWithOpenDefault() {
        Fixture f = new Fixture("v2-beta");
        for (ApiKeyPolicy.Scope scope : List.of(ApiKeyPolicy.Scope.stack("1st8"), ApiKeyPolicy.Scope.resource("instanceLink", "1il9"))) {
            ApiKeyPolicy key = policy(ApiKeyPolicy.Effect.ALLOW, rule("deny", ApiKeyPolicy.Effect.DENY, scope, "read"));
            assertEquals("KeyPolicyDenied", assertThrows(ClientVisibleException.class, () -> f.authorize(key)).getCode());
        }
    }

    @Test public void foreignLinkOrOwnerBlockedSourceCannotBorrowGlobalRead() {
        Fixture foreign = new Fixture("v2-beta"); foreign.link.setAccountId(6L);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> foreign.authorize(policy(ApiKeyPolicy.Effect.ALLOW))).getCode());
        Fixture blocked = new Fixture("v2-beta");
        blocked.owner = spy(blocked.owner); doReturn(null).when(blocked.owner).authorizeObject(blocked.source);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> blocked.target(blocked.link)).getCode());
    }

    @Test public void missingRemovedOrIncoherentPersistedLinkAncestryFailsClosed() {
        for (String invalid : List.of("missing-link", "missing-instance", "missing-project", "missing-stack", "removed-link", "removed-instance",
                "removed-project", "removed-stack", "purging-instance", "removed-state-link", "null-source", "source-account-mismatch")) {
            Fixture f = new Fixture("v2-beta");
            switch (invalid) {
                case "missing-link" -> f.rows.get(InstanceLink.class).clear();
                case "missing-instance" -> f.rows.get(Instance.class).clear();
                case "missing-project" -> f.rows.get(Account.class).clear();
                case "missing-stack" -> f.rows.get(Stack.class).remove(8L);
                case "removed-link" -> f.link.setRemoved(new Date(1));
                case "removed-instance" -> f.source.setRemoved(new Date(1));
                case "removed-project" -> f.project.setRemoved(new Date(1));
                case "removed-stack" -> f.stack.setRemoved(new Date(1));
                case "purging-instance" -> f.source.setState("purging");
                case "removed-state-link" -> f.link.setState("removed");
                case "null-source" -> f.link.setInstanceId(null);
                case "source-account-mismatch" -> f.source.setAccountId(6L);
            }
            assertThrows(invalid, ClientVisibleException.class, () -> f.target(f.link));
        }
    }

    @Test public void anInMemorySourcePointerCannotReplaceThePersistedLinkParent() {
        Fixture f = new Fixture("v2-beta");
        InstanceLinkRecord supplied = new InstanceLinkRecord(); supplied.setId(9L); supplied.setAccountId(5L); supplied.setInstanceId(4L);
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () -> f.target(supplied)).getCode());
    }

    @Test public void sourceContainerManagedAncestryIsReusedAndDestinationDoesNotSupplyStack() {
        Fixture f = new Fixture("v2-beta");
        assertEquals("1st8", f.target(f.link).stackId()); // targetInstanceId is owned but in Stack9.
        f.source.setStackId(null); f.source.setServiceId(2L);
        ServiceRecord service = new ServiceRecord(); service.setId(2L); service.setAccountId(5L); service.setStackId(8L); f.add(Service.class, service);
        assertEquals("1st8", f.target(f.link).stackId());
        f.source.setStackId(9L);
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () -> f.target(f.link)).getCode());
    }

    @Test public void newTargetReferenceNeedsIndependentReadAndRetainsReferenceDeny() {
        Fixture f = new Fixture("v2-beta"); f.request.setMethod("PUT"); f.request.setRequestObject(Map.of("targetInstanceId", "1i4"));
        ApiKeyPolicy.Rule update = allow(ApiKeyPolicy.Scope.resource("instanceLink", "1il9"), "update");
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.authorize(policy(ApiKeyPolicy.Effect.DENY, update))).getCode());
        ApiKeyPolicy.Rule read = rule("target-read", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("container", "1i4"), "read");
        f.authorize(policy(ApiKeyPolicy.Effect.DENY, update, read));
        assertEquals("update", f.request.getAttribute("apiKey.audit.operation"));
        ApiKeyPolicy.Rule deny = rule("target-deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("container", "1i4"), "read");
        assertEquals("KeyPolicyDenied", assertThrows(ClientVisibleException.class,
                () -> f.authorize(policy(ApiKeyPolicy.Effect.DENY, update, read, deny))).getCode());
    }

    @Test public void readonlySourcePointerAndMissingNewTargetCannotBorrowWriteAuthority() {
        Fixture f = new Fixture("v2-beta"); f.request.setMethod("PUT"); f.request.setRequestObject(Map.of("instanceId", "1i4"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.authorize(policy(ApiKeyPolicy.Effect.DENY, allow(ApiKeyPolicy.Scope.stack("1st9"), "update")))).getCode());
        f.request.setRequestObject(Map.of("targetInstanceId", "1i404"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.authorize(policy(ApiKeyPolicy.Effect.DENY, allow(ApiKeyPolicy.Scope.stack("1st8"), "update")))).getCode());
    }

    @Test public void fullLegacyClosedAndExpiryKeepTheirExistingBoundaries() {
        Fixture f = new Fixture("v2-beta"); f.rows.get(Instance.class).clear();
        for (ApiKeyPolicy key : Arrays.asList(null, new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()))) {
            f.authorize(key);
            assertNull(f.service.constrain(f.request, f.factory, "instanceLink", INSTANCE_LINK));
        }
        assertEquals("KeyPolicyDenied", assertThrows(ClientVisibleException.class,
                () -> f.authorize(new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of()))).getCode());
        assertEquals("ApiKeyExpired", assertThrows(ClientVisibleException.class,
                () -> f.authorize(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, NOW, List.of()))).getCode());
    }

    @Test public void nativeAdapterDoesNotOpenUnknownTypesOrAcceptWrongTypedIds() {
        Fixture f = new Fixture("v2-beta");
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED, f.resolver.resolveObject("futurePlugin", f.link, f.owner).level());
        assertTrue(render(ApiKeyQueryScopes.condition(policy(ApiKeyPolicy.Effect.ALLOW), "futurePlugin", INSTANCE_LINK, "read", ApiKeyInstanceLinkScopeTest::parse)).contains("false"));
        f.request.setId("1i9");
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.authorize(policy(ApiKeyPolicy.Effect.ALLOW))).getCode());
    }

    @Test public void linkSqlUsesSourceAncestryForAllScopesBeforePaginationAndDeny() {
        for (ApiKeyPolicy.Scope scope : List.of(ApiKeyPolicy.Scope.project("1a5"), ApiKeyPolicy.Scope.stack("1st8"),
                ApiKeyPolicy.Scope.resource("instanceLink", "1il9"), ApiKeyPolicy.Scope.global())) {
            Condition condition = ApiKeyQueryScopes.condition(policy(ApiKeyPolicy.Effect.DENY, allow(scope, "read")), "instanceLink", INSTANCE_LINK, "read", ApiKeyInstanceLinkScopeTest::parse);
            String predicate = render(condition);
            assertTrue(predicate, predicate.contains("key_scope_link_instance"));
            assertTrue(predicate, predicate.contains("`instance_link`.`instance_id`"));
            assertFalse(predicate, predicate.contains("target_instance_id"));
            String paged = DSL.using(SQLDialect.MYSQL, settings()).renderInlined(DSL.selectFrom(INSTANCE_LINK).where(condition).limit(1)).toLowerCase();
            assertTrue(paged, paged.indexOf("where") < paged.indexOf("limit"));
        }
        String denied = render(ApiKeyQueryScopes.condition(policy(ApiKeyPolicy.Effect.ALLOW,
                rule("deny-link", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("instanceLink", "1il9"), "read")), "instanceLink", INSTANCE_LINK, "read", ApiKeyInstanceLinkScopeTest::parse));
        assertTrue(denied, denied.contains("and not")); assertTrue(denied, denied.contains("`instance_link`.`id` = 9"));
    }

    @Test public void openDefaultAndExactIdCannotSkipLiveSameAccountSourceAndStackSqlGuards() {
        String sql = render(ApiKeyQueryScopes.condition(policy(ApiKeyPolicy.Effect.ALLOW), "instanceLink", INSTANCE_LINK, "read", ApiKeyInstanceLinkScopeTest::parse));
        for (String guard : List.of("`instance_link`.`removed` is null", "key_scope_link_project", "key_scope_link_stack",
                "`key_scope_link_instance`.`account_id` = `instance_link`.`account_id`", "key_scope_parent_service", "key_scope_checked_map", "not exists", "'purging'")) {
            assertTrue(guard + ": " + sql, sql.contains(guard));
        }
        assertTrue(render(ApiKeyQueryScopes.condition(policy(ApiKeyPolicy.Effect.ALLOW), "instanceLink", InstanceTable.INSTANCE, "read", ApiKeyInstanceLinkScopeTest::parse)).contains("false"));
    }

    @Test public void scalarStackComparisonsUseTotalNullSafeEqualityOnMysqlAndMariaDb() {
        Field<Long> scalarStack = DSL.field(DSL.select(DSL.inline(8L)));
        ServiceTable direct = ServiceTable.SERVICE.as("key_scope_parent_service");
        ServiceTable checked = ServiceTable.SERVICE.as("key_scope_checked_service");
        List<Condition> comparisons = List.of(ApiKeyQueryScopes.sameNullableStack(direct.STACK_ID, scalarStack),
                ApiKeyQueryScopes.sameNullableStack(checked.STACK_ID, scalarStack).not());
        for (SQLDialect dialect : List.of(SQLDialect.MYSQL, SQLDialect.MARIADB)) {
            for (Condition comparison : comparisons) {
                String sql = DSL.using(dialect, settings()).renderInlined(comparison).toLowerCase(Locale.ROOT);
                assertTrue(sql, sql.contains("(select 8)"));
                assertTrue(sql, sql.contains("is null") && sql.contains("is not null") && sql.contains(" = "));
                assertFalse(sql, sql.contains("<=>") || sql.contains("distinct from")
                        || sql.matches("(?s).*\\b(?:row|in|all|any|intersect|except)\\b.*"));
            }
            for (boolean isLink : List.of(true, false)) {
                String type = isLink ? "instanceLink" : "container";
                Table<?> table = isLink ? INSTANCE_LINK : InstanceTable.INSTANCE;
                Condition scoped = ApiKeyQueryScopes.condition(policy(ApiKeyPolicy.Effect.DENY,
                        allow(ApiKeyPolicy.Scope.stack("1st8"), "read")), type, table, "read", ApiKeyInstanceLinkScopeTest::parse);
                String sql = DSL.using(dialect, settings()).renderInlined(DSL.selectFrom(table).where(scoped).limit(1))
                        .toLowerCase(Locale.ROOT);
                assertTrue(sql, sql.contains("coalesce(") && sql.contains("(select"));
                if (isLink) {
                    assertTrue(sql, sql.matches("(?s).*\\(select\\s+coalesce\\(.*"));
                    String stackLookup = "select 1 as `one` from `environment` as `key_scope_link_stack`";
                    assertTrue(sql, sql.contains("(" + stackLookup));
                    assertFalse(sql, sql.contains("exists (" + stackLookup));
                    assertTrue(sql, sql.contains("`key_scope_link_stack`.`account_id` = `instance_link`.`account_id`"));
                    assertTrue(sql, sql.contains("`key_scope_link_stack`.`removed` is null"));
                }
                for (String alias : List.of("key_scope_parent_service", "key_scope_checked_service")) {
                    String column = "`" + alias + "`.`environment_id`";
                    assertTrue(sql, sql.contains(column + " is null") && sql.contains(column + " is not null") && sql.contains(column + " = "));
                    assertFalse(sql, sql.contains(column + " <=>") || sql.contains(column + " is distinct from")
                            || sql.contains(column + " is not distinct from"));
                }
                int pagination = sql.contains("limit") ? sql.indexOf("limit") : sql.indexOf("fetch next");
                assertTrue(sql, pagination > sql.indexOf("where") && sql.indexOf("where") >= 0);
                // Opt-in stdout oracle uses synthetic constants only, never QA IDs.
                if (Boolean.getBoolean("apiKey.nullSafeSqlOracle")) {
                    System.out.println("NULLSAFE_STACK_SQL_ORACLE_" + type + "_" + dialect + "=" + sql);
                }
            }
        }
    }

    private static Settings settings() { return new Settings().withRenderSchema(false); }
    private static String render(Condition condition) { return DSL.using(SQLDialect.MYSQL, settings()).renderInlined(condition).toLowerCase(); }
    private static Long parse(String type, String id) { return Long.valueOf(id.replaceFirst("^1[a-zA-Z]+", "")); }
    private static ApiKeyPolicy.Rule allow(ApiKeyPolicy.Scope scope, String operation) { return rule("allow", ApiKeyPolicy.Effect.ALLOW, scope, operation); }
    private static ApiKeyPolicy.Rule rule(String id, ApiKeyPolicy.Effect effect, ApiKeyPolicy.Scope scope, String operation) { return new ApiKeyPolicy.Rule(id, effect, scope, Set.of(operation)); }
    private static ApiKeyPolicy policy(ApiKeyPolicy.Effect base, ApiKeyPolicy.Rule... rules) { return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, base, null, List.of(rules)); }

    private static final class Fixture {
        final ApiKeyTargetResolver resolver = new ApiKeyTargetResolver();
        final ApiKeyAuthorizationService service = new ApiKeyAuthorizationService();
        final SchemaFactory factory = mock(SchemaFactory.class);
        final ApiRequest request = new ApiRequest(null, null);
        final Map<Class<?>, Map<Long, Object>> rows = new HashMap<>();
        final AccountRecord project = new AccountRecord();
        final StackRecord stack = new StackRecord();
        final InstanceRecord source = new InstanceRecord();
        final InstanceLinkRecord link = new InstanceLinkRecord();
        Policy owner;

        Fixture(String version) {
            project.setId(5L); project.setKind("project"); project.setState("active"); add(Account.class, project);
            AccountRecord principal = new AccountRecord(); principal.setId(10L); principal.setKind("user");
            owner = new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions());
            stack.setId(8L); stack.setAccountId(5L); stack.setState("active"); add(Stack.class, stack);
            StackRecord targetStack = new StackRecord(); targetStack.setId(9L); targetStack.setAccountId(5L); add(Stack.class, targetStack);
            source.setId(3L); source.setAccountId(5L); source.setStackId(8L); source.setState("inactive"); add(Instance.class, source);
            InstanceRecord target = new InstanceRecord(); target.setId(4L); target.setAccountId(5L); target.setStackId(9L); add(Instance.class, target);
            link.setId(9L); link.setAccountId(5L); link.setInstanceId(3L); link.setTargetInstanceId(4L); link.setState("active"); add(InstanceLink.class, link);
            resolver.objectManager = mock(ObjectManager.class);
            resolver.projectAccess = mock(ProjectAccessService.class);
            when(resolver.projectAccess.authorize(same(project), any(Policy.class))).thenAnswer(i ->
                    ((Policy) i.getArgument(1)).getAccountId() == project.getId() ? project : null);
            when(resolver.objectManager.loadResource(any(Class.class), any(Long.class))).thenAnswer(i -> lookup(i.getArgument(0), i.getArgument(1)));
            when(resolver.objectManager.loadResource(anyString(), any(Long.class))).thenAnswer(i -> {
                Class<?> type = switch ((String)i.getArgument(0)) {
                    case "instanceLink" -> InstanceLink.class;
                    case "instance", "container" -> Instance.class;
                    case "stack" -> Stack.class;
                    case "project", "account" -> Account.class;
                    case "service" -> Service.class;
                    default -> Object.class;
                };
                return lookup(type, (Long)i.getArgument(1));
            });
            // Input reference validation uses the String-ID overload, while
            // the exact request operand is decoded to Long before lookup.
            when(resolver.objectManager.loadResource(anyString(), anyString())).thenAnswer(i -> {
                Class<?> type = switch ((String)i.getArgument(0)) {
                    case "instance", "container" -> Instance.class;
                    default -> Object.class;
                };
                return lookup(type, Long.valueOf((String)i.getArgument(1)));
            });
            for (long id : List.of(3L, 4L)) {
                when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, id, SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of());
                when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, id, SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true)).thenReturn(List.of());
            }
            SchemaImpl nativeLink = new SchemaImpl(); nativeLink.setId("instanceLink"); nativeLink.setPluralName("instanceLinks"); nativeLink.setResourceMethods(List.of("GET", "PUT"));
            nativeLink.getResourceFields().put("targetInstanceId", field("reference[instance]", true));
            nativeLink.getResourceFields().put("instanceId", field("reference[instance]", false));
            when(factory.getSchema("instanceLink")).thenReturn(nativeLink); when(factory.getSchemaClass("instanceLink")).thenReturn((Class)InstanceLink.class);
            SchemaImpl container = new SchemaImpl(); container.setId("container"); container.setResourceMethods(List.of("GET"));
            when(factory.getSchema("container")).thenReturn(container);
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(factory);
            formatter.setTypeMappings(Map.of("project", "a", "stack", "st", "container", "i", "instance", "i", "instanceLink", "il", "service", "s", "credential", "c", "apiKey", "c"));
            resolver.idFormatter = formatter;
            ApiContext context = ApiContext.newContext(); context.setIdFormatter(formatter); context.setPolicy(owner); context.setApiRequest(request);
            request.setSchemaFactory(factory); request.setSchemaVersion(version); request.setType("instanceLink"); request.setId("1il9"); request.setMethod("GET");
            service.targets = resolver; service.clock = Clock.fixed(NOW, ZoneOffset.UTC); service.auditSinks = List.of(mock(ApiKeyAuditSink.class));
        }
        private static FieldImpl field(String type, boolean update) { FieldImpl field = new FieldImpl(); field.setType(type); field.setUpdate(update); field.setCreate(false); return field; }
        void add(Class<?> type, Object value) { Long id = (Long)io.cattle.platform.object.util.ObjectUtils.getPropertyIgnoreErrors(value, "id"); rows.computeIfAbsent(type, k -> new HashMap<>()).put(id, value); }
        Object lookup(Class<?> type, Long id) { return rows.getOrDefault(type, Map.of()).get(id); }
        ApiKeyPolicyEvaluator.Target target(InstanceLink value) { return resolver.resolveObject("instanceLink", value, owner); }
        void authorize(ApiKeyPolicy key) {
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1L, 10L, "apiKey", key == null ? 0 : 1, key));
            service.authorize(request, owner);
        }
    }
}
