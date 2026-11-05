package io.cattle.platform.iaas.api.auth.ai.view;

import static io.cattle.platform.core.model.tables.AccountTable.ACCOUNT;
import static io.cattle.platform.core.model.tables.ProjectMemberTable.PROJECT_MEMBER;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.iaas.api.auth.dao.impl.AuthDaoImpl;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.iaas.api.auth.projects.ProjectResourceManager;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.After;
import org.junit.Test;

/** Real native list -> AuthDao H2 -> ProjectAccess -> common authorize/Key filter. */
public class AiProjectCollectionOwnerTest {
    @After public void clearContext() { ApiContext.remove(); }

    @Test public void adminNativeFullAndLegacySeeNonMembershipProjectsWithoutWideningMemberOrReadonly() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(List.of("1a5", "1a6", "1a7"), f.ids(f.read(42, true, full(), Map.of("resourceType", "project"))));
            assertArrayEquals(new String[] {"true"}, f.manager.allValues.getLast());
            // Legacy is the original native GET contract, not a new Legacy AI-Bridge admission.
            assertEquals(List.of(5L, 6L, 7L), f.nativeLegacyIds(42, true));
            assertEquals(List.of("1a5"), f.ids(f.read(42, false, full(), Map.of("resourceType", "project"))));
            assertEquals(List.of("1a6"), f.ids(f.read(43, false, full(), Map.of("resourceType", "project"))));
            assertEquals(2, f.sql.fetchCount(PROJECT_MEMBER)); // No Admin membership was added.
        }
    }

    @Test public void exactProjectCustomDenyAndReadonlyKeysRetainExactSetsThroughNativeCommonCollectionFilter() throws Exception {
        try (Fixture f = new Fixture()) {
            for (ApiKeyPolicy.Scope scope : List.of(ApiKeyPolicy.Scope.resource("project", "1a6"), ApiKeyPolicy.Scope.project("1a6"))) {
                var only = custom(ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Effect.ALLOW, scope, "read");
                assertEquals(List.of("1a6"), f.ids(f.read(42, true, only, Map.of("resourceType", "project"))));
            }
            var deny = custom(ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("project", "1a6"), "read");
            assertEquals(List.of("1a5", "1a7"), f.ids(f.read(42, true, deny, Map.of("resourceType", "project"))));
            var readonly = custom(ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.global(), "read");
            assertEquals(List.of("1a5", "1a6", "1a7"), f.ids(f.read(42, true, readonly, Map.of("resourceType", "project"))));
            assertEquals(List.of("1a5"), f.ids(f.read(42, false, readonly, Map.of("resourceType", "project"))));
            var updateOnly = custom(ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a6"), "update");
            assertEquals(List.of(), f.ids(f.read(42, true, updateOnly, Map.of("resourceType", "project"))));
            var memberOnly = custom(ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5"), "read");
            f.manager.afterNativeList = () -> f.sql.deleteFrom(PROJECT_MEMBER).where(PROJECT_MEMBER.EXTERNAL_ID.eq("42")).execute();
            Map<String, Object> removed = f.read(42, false, memberOnly, Map.of("resourceType", "project"));
            assertEquals(List.of(), f.ids(removed)); assertNull(removed.get("nextMarker"));
            assertFalse(removed.containsKey("total")); assertFalse(removed.toString().contains("environment-five"));
        }
    }

    @Test public void allIsPrivateToTypedProjectCollectionAndNeverChangesSingleGetOrOtherTypesOrAcceptsModelAll() throws Exception {
        try (Fixture f = new Fixture()) {
            assertEquals(List.of("1a6"), f.ids(f.read(42, true, full(), Map.of("resourceType", "project", "resourceId", "1a6"))));
            assertNull(f.manager.allValues.getLast());
            ResourceManager setting = mock(ResourceManager.class);
            when(setting.list(eq("setting"), any(ApiRequest.class))).thenAnswer(call -> {
                assertFalse(((ApiRequest) call.getArgument(1)).getRequestParams().containsKey("all"));
                return List.of(new ResourceImpl("safe", "setting", Map.of("name", "ordinary")));
            });
            when(setting.convertResponse(any(Object.class), any())).thenAnswer(call -> call.getArgument(0));
            when(f.managers.getResourceManagerByType("setting")).thenReturn(setting);
            assertEquals(1, ((List<?>) f.read(42, true, full(), Map.of("resourceType", "setting")).get("data")).size());
            int count = f.manager.allValues.size();
            ClientVisibleException rejected = assertThrows(ClientVisibleException.class,
                    () -> f.read(42, true, full(), Map.of("resourceType", "project", "all", true)));
            assertEquals("ResourceViewInputInvalid", rejected.getCode()); assertEquals(count, f.manager.allValues.size());
            assertFalse(f.lastInput.getRequestParams().containsKey("all"));
        }
    }

    private static ApiKeyPolicy full() {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
    }
    private static ApiKeyPolicy custom(ApiKeyPolicy.Effect fallback, ApiKeyPolicy.Effect effect, ApiKeyPolicy.Scope scope, String operation) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, fallback, null,
                List.of(new ApiKeyPolicy.Rule("focused-project-read", effect, scope, Set.of(operation))), ApiKeyPolicy.Purpose.AI_BRIDGE);
    }
    private static void inject(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException next) { /* native superclass fields are intentional */ }
        }
        throw new NoSuchFieldException(name);
    }

    static class NativeProjectManager extends ProjectResourceManager {
        final List<String[]> allValues = new ArrayList<>();
        Runnable afterNativeList;
        @Override public Object listInternal(SchemaFactory schemas, String type, Map<Object, Object> criteria, ListOptions options) {
            allValues.add((String[]) ApiContext.getContext().getApiRequest().getRequestParams().get("all"));
            Object rows = super.listInternal(schemas, type, criteria, options);
            if (afterNativeList != null) { Runnable hook = afterNativeList; afterNativeList = null; hook.run(); }
            return rows;
        }
        @Override public Resource convertResponse(Object value, ApiRequest request) {
            Account row = (Account) value;
            return new ResourceImpl("1a" + row.getId(), "project", Map.of("name", row.getName(), "state", row.getState()));
        }
    }

    static class Fixture implements AutoCloseable {
        final Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";NON_KEYWORDS=ROLE");
        final DSLContext sql = DSL.using(connection, SQLDialect.H2, new Settings().withRenderQuotedNames(RenderQuotedNames.NEVER));
        final AuthDaoImpl dao = new AuthDaoImpl();
        final NativeProjectManager manager = new NativeProjectManager();
        final AiResourceViewService view = new AiResourceViewService();
        final ApiKeyAuthorizationService keyAccess = new ApiKeyAuthorizationService();
        final ResourceManagerLocator managers = mock(ResourceManagerLocator.class);
        final SchemaFactory schemas = mock(SchemaFactory.class);
        final Schema schema = mock(Schema.class);
        final Object previousEnabled = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        ApiRequest lastInput;
        Fixture() throws Exception {
            sql.createSchema("cattle").execute(); sql.createTable(ACCOUNT).columns(ACCOUNT.fields()).execute();
            sql.createTable(PROJECT_MEMBER).columns(PROJECT_MEMBER.fields()).execute(); dao.setConfiguration(sql.configuration());
            sql.insertInto(ACCOUNT, ACCOUNT.ID, ACCOUNT.KIND, ACCOUNT.STATE, ACCOUNT.UUID, ACCOUNT.NAME)
                .values(42L, "user", "active", "user-42", "actor42").values(43L, "user", "active", "user-43", "actor43")
                .values(5L, "project", "active", "project-five", "environment-five")
                .values(6L, "project", "active", "project-six", "environment-six")
                .values(7L, "project", "active", "project-seven", "environment-seven").execute();
            sql.insertInto(PROJECT_MEMBER, PROJECT_MEMBER.ID, PROJECT_MEMBER.PROJECT_ID, PROJECT_MEMBER.EXTERNAL_ID_TYPE,
                    PROJECT_MEMBER.EXTERNAL_ID, PROJECT_MEMBER.ROLE, PROJECT_MEMBER.STATE, PROJECT_MEMBER.KIND, PROJECT_MEMBER.UUID)
                .values(1L, 5L, "rancher_id", "42", "member", "active", "projectMember", "member-one")
                .values(2L, 6L, "rancher_id", "43", "readonly", "active", "projectMember", "member-two").execute();
            // Generated DDL represents the MySQL NULL default as a CLOB literal on H2.
            sql.execute("update cattle.account set data='{}'"); sql.execute("update cattle.project_member set data='{}'");
            AccountDao accounts = mock(AccountDao.class);
            when(accounts.isActiveAccount(any(Account.class))).thenAnswer(call -> {
                Account row = call.getArgument(0); return "active".equals(row.getState()) && row.getRemoved() == null;
            });
            var access = new ProjectAccessService(dao, accounts); var objects = mock(ObjectManager.class);
            when(objects.getType(any(Object.class))).thenReturn("account");
            when(objects.loadResource(eq(Account.class), anyString())).thenAnswer(call -> dao.getAccountById(Long.valueOf(call.getArgument(1).toString())));
            when(objects.loadResource(eq(Account.class), anyLong())).thenAnswer(call -> dao.getAccountById(call.getArgument(1)));
            when(objects.loadResource(eq("project"), anyString())).thenAnswer(call -> dao.getAccountById(Long.valueOf(call.getArgument(1).toString())));
            when(objects.loadResource(eq("project"), anyLong())).thenAnswer(call -> dao.getAccountById(call.getArgument(1)));
            IdFormatter formatter = mock(IdFormatter.class);
            when(formatter.parseId(anyString())).thenAnswer(call -> call.<String>getArgument(0).replaceFirst("^1a", ""));
            when(formatter.formatId(anyString(), any())).thenAnswer(call -> "1a" + call.getArgument(1));
            var resolver = new ApiKeyTargetResolver(); inject(resolver, "objectManager", objects);
            inject(resolver, "projectAccess", access); inject(resolver, "idFormatter", formatter);
            inject(keyAccess, "targets", resolver); inject(keyAccess, "objectManager", objects); inject(keyAccess, "resourceManagers", managers);
            inject(keyAccess, "auditSinks", List.of(mock(ApiKeyAuditSink.class)));
            manager.setObjectManager(objects); inject(manager, "authDao", dao);
            inject(manager, "projectAccess", access); inject(manager, "resourceAccess", List.of(keyAccess));
            when(managers.getResourceManagerByType("project")).thenReturn(manager);
            when(schemas.getSchema(anyString())).thenReturn(schema); when(schema.getCollectionMethods()).thenReturn(List.of("GET"));
            when(schema.getResourceMethods()).thenReturn(List.of("GET")); when(schema.getResourceFields()).thenReturn(Map.of());
            view.managers = managers; view.keyAccess = keyAccess;
            view.authenticator = mock(ApiAuthenticator.class); view.keyAuthorization = mock(AiKeyAuthorization.class);
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            ApiContext.newContext().setIdFormatter(formatter);
        }
        Policy policy(long actor, boolean admin) {
            Account principal = dao.getAccountById(actor);
            return new AccountPolicy(principal, principal, Set.of(new Identity("rancher_id", Long.toString(actor))), new NoPolicyOptions() {
                @Override public boolean isOption(String name) { return admin && Policy.AUTHORIZED_FOR_ALL_ACCOUNTS.equals(name); }
            });
        }
        Map<String, Object> read(long actor, boolean admin, ApiKeyPolicy key, Map<String, Object> body) {
            Policy owner = policy(actor, admin); when(view.keyAuthorization.actor(any())).thenReturn(owner);
            when(view.authenticator.currentAuthorization(eq(actor), eq(actor), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, schemas));
            ApiRequest request = new ApiRequest(null, schemas); request.setType("aiResourceView"); request.setMethod("POST"); request.setRequestObject(body);
            request.setRequestParams(new LinkedHashMap<>()); HttpServletRequest http = mock(HttpServletRequest.class);
            when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
            request.setRequestServletContext(new ApiServletContext(http, null, null));
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(99, actor, "apiKeyRestricted", 3, key));
            ApiContext.getContext().setApiRequest(request); ApiContext.getContext().setPolicy(owner); lastInput = request;
            return view.read(request);
        }
        List<Long> nativeLegacyIds(long actor, boolean admin) {
            var request = new ApiRequest(null, schemas); request.setType("project"); request.setMethod("GET");
            request.setRequestParams(Map.of("all", new String[] {"true"})); request.setConditions(new LinkedHashMap<>());
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(100, actor, "apiKey", 0, null));
            Policy owner = policy(actor, admin); ApiContext.getContext().setApiRequest(request); ApiContext.getContext().setPolicy(owner);
            keyAccess.authorize(request, owner);
            return ((List<?>) manager.list("project", request)).stream().map(x -> ((Account) x).getId()).sorted().toList();
        }
        List<String> ids(Map<String, Object> value) {
            return ((List<?>) value.get("data")).stream().map(x -> (String) ((Map<?, ?>) x).get("id")).sorted().toList();
        }
        @Override public void close() throws Exception {
            if (previousEnabled == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previousEnabled);
            connection.close(); ApiContext.remove();
        }
    }
}
