package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.api.resource.jooq.DefaultJooqResourceManager;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.tables.StackTable;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.AbstractResourceManagerFilter;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerFilter;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.request.resource.impl.FilteredResourceManager;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Native collection admission, real Catalog wrapper and real H2 scope predicate; no permissive manager mock. */
public class AiCatalogScopedCollectionTest {
    private Object previousEnabled;
    @Before public void setup() {
        previousEnabled = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
        ApiContext.newContext();
    }
    @After public void cleanup() {
        ApiContext.remove();
        if (previousEnabled == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previousEnabled);
    }

    @Test public void exactProjectReadSurvivesCatalogWrapperButCannotReadOtherProjectOrParent() throws Exception {
        Fixture f = new Fixture(projectRule());
        f.admit();
        assertEquals("KeyScopedCollection", f.request.getAttribute("apiKey.audit.reason"));
        assertEquals(List.of(924L, 926L), f.rows(null));
        assertEquals(List.of(), f.rows(925L));
        assertEquals(List.of(924L), f.rows(924L));
        f.key(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(projectRule())));
        f.admit(); assertEquals(List.of(924L, 926L), f.rows(null));
    }

    @Test public void sameStackGrantAndExplicitProjectOrChildDenialsStillReachSqlBeforeLimit() throws Exception {
        Fixture stackOnly = new Fixture(rule("one-stack", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st924")));
        stackOnly.admit(); assertEquals(List.of(924L), stackOnly.rows(null)); assertEquals(List.of(), stackOnly.rows(926L));
        Fixture childDeny = new Fixture(projectRule(), rule("deny-child", ApiKeyPolicy.Effect.DENY,
                ApiKeyPolicy.Scope.resource("stack", "1st924")));
        childDeny.admit(); assertEquals(List.of(926L), childDeny.rows(null));
        Fixture projectDeny = new Fixture(projectRule(), rule("deny-project", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.project("1a5")));
        projectDeny.admit(); assertEquals(List.of(), projectDeny.rows(null));
    }

    @Test public void unsupportedLookalikeFilterStillDeniesNativeCollectionBeforeAnyRead() throws Exception {
        Fixture f = new Fixture(projectRule());
        ResourceManagerFilter lookalike = new AbstractResourceManagerFilter() {
            @Override public String[] getTypes() { return new String[] { "stack" }; }
            @Override public Object list(String type, ApiRequest request, ResourceManager next) { return List.of("FOREIGN"); }
        };
        f.use(new FilteredResourceManager(lookalike, f.base));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, f::admit).getCode());
        assertEquals("DENY", f.request.getAttribute("apiKey.audit.decision"));
    }

    @Test public void missingOrDifferentGuardAndOverriddenQueryCannotBorrowCatalogAttestation() throws Exception {
        Fixture f = new Fixture(projectRule());
        assertFalse(f.wrapped.supportsScopedCollectionQuery(new Object()));
        var unbound = new FilteredResourceManager(f.catalog, new DefaultJooqResourceManager());
        assertFalse(unbound.supportsScopedCollectionQuery(f.access));
        var altered = new DefaultJooqResourceManager() {
            @Override protected Object listInternal(SchemaFactory schema, String type, Map<Object, Object> criteria, ListOptions options) {
                return List.of("FOREIGN");
            }
        };
        field(altered, "resourceAccess", List.of(f.access));
        assertFalse(new FilteredResourceManager(f.catalog, altered).supportsScopedCollectionQuery(f.access));
        var wrappedOverride = new FilteredResourceManager(f.catalog, f.base) {
            @Override public Object list(String type, ApiRequest request) { return List.of("FOREIGN"); }
        };
        assertFalse(wrappedOverride.supportsScopedCollectionQuery(f.access));
    }

    @Test public void actualCatalogValidatorStillChecksExactFrozenGraphForBothListOverloads() throws Exception {
        Fixture f = new Fixture(projectRule());
        AiCatalogLaunchDelegation delegation = f.catalog.delegation;
        delegation.store = mock(AiCatalogLaunchPlanStore.class);
        delegation.privateData = mock(AiCatalogLaunchPrivate.class);
        delegation.graphs = new AiCatalogLaunchGraph(); delegation.graphs.json = new JacksonJsonMapper();
        Map<String, Object> plan = Map.of("accountId", 5L);
        when(delegation.store.load("chosen-plan")).thenReturn(plan);
        when(delegation.privateData.open(plan)).thenReturn(Map.of("stackBody", Map.of("name", "chosen")));
        ResourceManager nativeManager = mock(ResourceManager.class);
        var stack = new StackRecord(); stack.setId(924L); stack.setAccountId(5L); stack.setKind("stack"); stack.setName("chosen");
        List<?> nativeRows = List.of(stack);
        when(nativeManager.list(eq("stack"), same(f.request))).thenReturn(nativeRows);
        doReturn(nativeRows).when(nativeManager).list(eq("stack"), anyMap(), any());
        var wrapper = new FilteredResourceManager(f.catalog, nativeManager);
        assertSame(nativeRows, wrapper.list("stack", f.request));
        f.request.setAttribute(AiCatalogLaunchDelegation.class.getName(),
                new AiCatalogLaunchDelegation.Verified("chosen-plan", null, null, null, false));
        assertSame(nativeRows, wrapper.list("stack", f.request));
        assertSame(nativeRows, wrapper.list("stack", Map.of(), null));
        stack.setAccountId(6L);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class, () -> wrapper.list("stack", f.request)).getCode());
        stack.setAccountId(5L); stack.setName("changed");
        assertEquals("CatalogLaunchSourceChanged", assertThrows(ClientVisibleException.class,
                () -> wrapper.list("stack", Map.of(), null)).getCode());
        when(nativeManager.list(eq("stack"), same(f.request))).thenReturn(List.of(stack, stack));
        assertEquals("CatalogLaunchSourceChanged", assertThrows(ClientVisibleException.class, () -> wrapper.list("stack", f.request)).getCode());
    }

    @Test public void ordinaryFullLegacyAndMissingNativeRoleKeepTheirExistingBehavior() throws Exception {
        Fixture f = new Fixture(projectRule());
        f.use(mock(ResourceManager.class));
        f.key(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        f.admit(); assertEquals("KeyFullAccess", f.request.getAttribute("apiKey.audit.reason"));
        assertNull(f.access.constrain(f.request, f.schemas, "stack", StackTable.STACK));
        f.key(null); f.admit(); assertNull(f.access.constrain(f.request, f.schemas, "stack", StackTable.STACK));
        f.key(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(projectRule())));
        when(f.schema.getCollectionMethods()).thenReturn(List.of());
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class, f::admit).getCode());
    }

    @Test public void allFourCatalogTypesKeepTheSameClosedCapabilityAndServiceGraphValidation() throws Exception {
        Fixture f = new Fixture(projectRule());
        assertEquals(Set.of("stack", "service", "volumeTemplate", "secret"), Set.of(f.catalog.getTypes()));
        for (String type : f.catalog.getTypes()) {
            f.request.setType(type); when(f.schemas.getSchema(type)).thenReturn(f.schema);
            when(f.locator.getResourceManagerByType(type)).thenReturn(f.wrapped);
            f.key(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(projectRule())));
            f.admit(); assertEquals("KeyScopedCollection", f.request.getAttribute("apiKey.audit.reason"));
        }
        ResourceManager.ScopedCollectionQueryGuard forgedGuard = filter -> true;
        assertFalse(f.wrapped.supportsScopedCollectionQuery(forgedGuard));
        f.request.setType("service");
        AiCatalogLaunchDelegation delegation = f.catalog.delegation;
        delegation.store = mock(AiCatalogLaunchPlanStore.class); delegation.privateData = mock(AiCatalogLaunchPrivate.class);
        Map<String, Object> plan = Map.of("createdGraph", Map.of("service:chosen", 110L));
        when(delegation.store.load("chosen-plan")).thenReturn(plan); when(delegation.privateData.open(plan)).thenReturn(Map.of());
        f.request.setAttribute(AiCatalogLaunchDelegation.class.getName(),
                new AiCatalogLaunchDelegation.Verified("chosen-plan", null, null, null, false));
        var selected = new io.cattle.platform.core.model.tables.records.ServiceRecord(); selected.setId(110L); selected.setName("chosen");
        ResourceManager normal = mock(ResourceManager.class); List<?> rows = List.of(selected);
        when(normal.list(eq("service"), same(f.request))).thenReturn(rows);
        var wrapper = new FilteredResourceManager(f.catalog, normal);
        assertSame(rows, wrapper.list("service", f.request));
        selected.setId(111L);
        assertEquals("CatalogLaunchSourceChanged", assertThrows(ClientVisibleException.class,
                () -> wrapper.list("service", f.request)).getCode());
    }

    private static ApiKeyPolicy.Rule projectRule() { return rule("project", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5")); }
    private static ApiKeyPolicy.Rule rule(String id, ApiKeyPolicy.Effect effect, ApiKeyPolicy.Scope scope) {
        return new ApiKeyPolicy.Rule(id, effect, scope, Set.of("read"));
    }
    private static void field(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(target, value); return; }
            catch (NoSuchFieldException inherited) { /* inspect inherited injection field */ }
        }
        throw new NoSuchFieldException(name);
    }
    private static final class Fixture {
        final ApiKeyAuthorizationService access = new ApiKeyAuthorizationService();
        final AiCatalogLaunchResponseFilter catalog = new AiCatalogLaunchResponseFilter();
        final DefaultJooqResourceManager base = new DefaultJooqResourceManager();
        final ResourceManagerLocator locator = mock(ResourceManagerLocator.class);
        final SchemaFactory schemas = mock(SchemaFactory.class);
        final Schema schema = mock(Schema.class);
        final Policy owner = mock(Policy.class);
        final ApiRequest request = new ApiRequest(null, schemas);
        final FilteredResourceManager wrapped;
        Fixture(ApiKeyPolicy.Rule... rules) throws Exception {
            request.setType("stack"); request.setMethod("GET");
            HttpServletRequest http = mock(HttpServletRequest.class);
            when(http.getHeader("X-PastureStack-Key-Expected-Revision")).thenReturn("1");
            request.setRequestServletContext(new ApiServletContext(http, null, null));
            when(schemas.getSchema("stack")).thenReturn(schema); when(schema.getCollectionMethods()).thenReturn(List.of("GET"));
            when(owner.getAccountId()).thenReturn(5L); when(owner.getAuthenticatedAsAccountId()).thenReturn(1L);
            field(access, "auditSinks", List.of(mock(ApiKeyAuditSink.class))); field(access, "resourceManagers", locator);
            ApiKeyTargetResolver targets = new ApiKeyTargetResolver(); DefaultIdFormatter formatter = new DefaultIdFormatter();
            formatter.setSchemaFactory(schemas); formatter.setTypeMappings(Map.of("project", "a", "account", "a", "stack", "st"));
            field(targets, "idFormatter", formatter); field(access, "targets", targets);
            field(base, "resourceAccess", List.of(access)); catalog.delegation = new AiCatalogLaunchDelegation();
            wrapped = new FilteredResourceManager(catalog, base); use(wrapped);
            ApiContext.getContext().setApiRequest(request); ApiContext.getContext().setPolicy(owner);
            key(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(rules), ApiKeyPolicy.Purpose.AI_BRIDGE));
        }
        void key(ApiKeyPolicy policy) {
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(8605L, 1L, "apiKeyRestricted", 1L, policy));
        }
        void use(ResourceManager manager) { when(locator.getResourceManagerByType("stack")).thenReturn(manager); }
        void admit() { access.authorize(request, owner); }
        List<Long> rows(Long parent) throws Exception {
            try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
                var sql = DSL.using(connection, SQLDialect.H2, new Settings().withRenderSchema(false));
                sql.execute("create table environment(id bigint, account_id bigint)");
                sql.execute("insert into environment values (924,5),(925,6),(926,5)");
                var condition = access.constrain(request, schemas, "stack", StackTable.STACK);
                if (parent != null) condition = condition.and(StackTable.STACK.ID.eq(parent));
                return sql.select(StackTable.STACK.ID).from(StackTable.STACK).where(condition).orderBy(StackTable.STACK.ID).limit(50)
                        .fetch(StackTable.STACK.ID);
            }
        }
    }
}
