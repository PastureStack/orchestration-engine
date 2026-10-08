package io.cattle.platform.iaas.api.auth.integration.external;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.netflix.config.ConfigurationManager;
import com.sun.net.httpserver.HttpServer;
import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.AuthTokenRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.iaas.api.auth.AbstractTokenUtil;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.iaas.api.auth.integration.local.LocalAuthConstants;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.token.TokenException;
import io.cattle.platform.token.TokenService;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ExternalServicePrincipalIdentitiesTest {
    private final JacksonJsonMapper json = new JacksonJsonMapper();
    private ExternalServiceAuthProvider provider;
    private ContextForbiddenTokenUtil util;
    private AuthDao auth;
    private AccountDao accounts;
    private AuthTokenDao tokens;
    private TokenService crypto;
    private AccountRecord owner;
    private AuthTokenRecord session;
    private HttpServer server;
    private final AtomicInteger getCalls = new AtomicInteger(), postCalls = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>(), posted = new AtomicReference<>();
    private static final List<String> SETTINGS = List.of(SecurityConstants.AUTH_PROVIDER_SETTING,
            SecurityConstants.SECURITY_SETTING, ServiceAuthConstants.EXTERNAL_AUTH_PROVIDER_SETTING,
            ServiceAuthConstants.NO_IDENTITY_LOOKUP_SETTING, ServiceAuthConstants.USERTYPE_SETTING,
            ServiceAuthConstants.ACCESSMODE_SETTING, "system.stack.auth.url", "auth.service.external.id.types",
            ServiceAuthConstants.IDENTITY_SEPARATOR_SETTING, ServiceAuthConstants.ALLOWED_IDENTITIES_SETTING,
            LocalAuthConstants.RECOVERY_ENABLED_SETTING);

    @Before public void setup() throws Exception {
        ApiContext.remove();
        setting(SecurityConstants.AUTH_PROVIDER_SETTING, "oidcconfig");
        setting(SecurityConstants.SECURITY_SETTING, true);
        setting(ServiceAuthConstants.EXTERNAL_AUTH_PROVIDER_SETTING, true);
        setting(ServiceAuthConstants.NO_IDENTITY_LOOKUP_SETTING, false);
        setting(ServiceAuthConstants.USERTYPE_SETTING, "oidc_user");
        setting(ServiceAuthConstants.ACCESSMODE_SETTING, AbstractTokenUtil.UNRESTRICTED_ACCESSMODE);
        setting("auth.service.external.id.types", "ldap_user,ldap_group,shibboleth_user,shibboleth_group");
        owner = new AccountRecord(); owner.setId(42L); owner.setKind("user"); owner.setState("active");
        owner.setExternalIdType("oidc_user"); owner.setExternalId("issuer|owner");
        owner.setData(new HashMap<>());
        DataAccessor.fields(owner).withKey(ServiceAuthConstants.ACCESS_TOKEN).set("owner-access-token");
        auth = mock(AuthDao.class); tokens = mock(AuthTokenDao.class); crypto = mock(TokenService.class);
        accounts = mock(AccountDao.class);
        util = new ContextForbiddenTokenUtil(); util.dependencies(auth, accounts);
        provider = new ExternalServiceAuthProvider(); provider.tokenUtil = util; provider.tokenService = crypto;
        inject(provider, "authTokenDao", tokens); inject(provider, "jsonMapper", json);
        session = new AuthTokenRecord(); session.setAccountId(42L); session.setAuthenticatedAsAccountId(42L);
        session.setProvider("oidcconfig"); session.setVersion(SecurityConstants.TOKEN_VERSION);
        session.setExpires(new Date(System.currentTimeMillis() + 600_000)); session.setValue("owner-signed-session");
        when(crypto.getJsonPayload("owner-signed-session", true)).thenReturn(payload(42L));
    }

    @After public void cleanup() {
        if (server != null) server.stop(0);
        ApiContext.remove(); SETTINGS.forEach(key -> ConfigurationManager.getConfigInstance().clearProperty(key));
    }

    @Test public void existingOwnerSessionWorksWithoutHttpAndDoesNotRefreshShortAccessToken() throws Exception {
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        fixture(401, Map.of(), Map.of());
        Set<Identity> identities = provider.getPrincipalIdentities(owner);
        assertTrue(identities.contains(new Identity("oidc_group", "issuer|group")));
        assertNull(ApiContext.getContext()); assertEquals(0, getCalls.get()); assertEquals(0, postCalls.get());
        verify(crypto).getJsonPayload("owner-signed-session", true);
        verify(tokens, never()).createToken(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test public void anotherPersonsCookieCannotContributeIdentitiesOrMutateRequest() {
        HttpServletRequest servlet = mock(HttpServletRequest.class);
        when(servlet.getCookies()).thenReturn(new Cookie[]{new Cookie("token", "other-person-session")});
        ApiRequest request = new ApiRequest(new ApiServletContext(servlet, null, null), null);
        request.setAttribute("externaljwt", "other-person-session");
        ApiContext.newContext().setApiRequest(request);
        clearInvocations(servlet); // ApiRequest construction reads getServletContext; the resolver must not read HTTP.
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        Set<Identity> identities = provider.getPrincipalIdentities(owner);
        assertTrue(identities.contains(new Identity("oidc_group", "issuer|group")));
        assertFalse(identities.contains(new Identity("oidc_group", "other-person-group")));
        assertEquals("other-person-session", request.getAttribute("externaljwt"));
        assertNull(request.getAttribute(ServiceAuthConstants.ACCESS_TOKEN));
        verifyNoInteractions(servlet);
    }

    @Test public void absentSessionUsesExactLiveEndpointAndOwnerBearerWithoutCreatingToken() throws Exception {
        fixture(200, Map.of("data", live("oidc_user", "issuer|owner", "oidc_group")), Map.of());
        assertTrue(provider.getPrincipalIdentities(owner).contains(new Identity("oidc_group", "live-group")));
        assertEquals("Bearer owner-access-token", authorization.get()); assertEquals(1, getCalls.get());
        assertEquals(0, postCalls.get()); assertNull(ApiContext.getContext()); verifyNoInteractions(crypto);
        verify(tokens, never()).createToken(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test public void validEmptyCollectionPreservesAdRefreshWithoutPlatformSessionOrContext() throws Exception {
        setting(SecurityConstants.AUTH_PROVIDER_SETTING, "activedirectory");
        setting(ServiceAuthConstants.USERTYPE_SETTING, "ldap_user");
        owner.setExternalIdType("ldap_user"); owner.setExternalId("owner-dn");
        DataAccessor.fields(owner).withKey(ServiceAuthConstants.ACCESS_TOKEN).set("owner-dn");
        fixture(200, Map.of("data", List.of()), Map.of("identities", live("ldap_user", "owner-dn", "ldap_group")));
        assertTrue(provider.getPrincipalIdentities(owner).contains(new Identity("ldap_group", "live-group")));
        assertEquals(1, getCalls.get()); assertEquals(1, postCalls.get());
        assertEquals(Map.of("accessToken", "owner-dn"), json.readValue(posted.get()));
        assertNull(ApiContext.getContext()); verifyNoInteractions(crypto);
        verify(tokens, never()).createToken(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test public void providerErrorsNeverTriggerEmptyCollectionRefresh() throws Exception {
        for (int status : List.of(401, 403, 500)) {
            fixture(status, Map.of("data", List.of()), Map.of());
            unavailable(); assertEquals(0, postCalls.get()); server.stop(0); server = null;
        }
    }

    @Test public void malformedSuccessfulResponseNeverTriggersRefresh() throws Exception {
        fixture(200, Map.of("other", List.of()), Map.of()); unavailable(); assertEquals(0, postCalls.get());
    }

    @Test public void connectionFailureIsRetryableUnavailableAndDoesNotCreateContext() throws Exception {
        fixture(200, Map.of("data", List.of()), Map.of()); server.stop(0); server = null;
        unavailable(); assertNull(ApiContext.getContext()); assertEquals(0, postCalls.get());
    }

    @Test public void liveGroupsFromForeignUserCannotAuthorizeOwner() throws Exception {
        fixture(200, Map.of("data", live("oidc_user", "issuer|other", "oidc_group")), Map.of());
        ClientVisibleException e = assertThrows(ClientVisibleException.class, () -> provider.getPrincipalIdentities(owner));
        assertEquals(403, e.getStatus()); assertEquals("OwnerPermissionDenied", e.getCode());
        assertEquals(0, postCalls.get());
    }

    @Test public void linkedOidcOwnerIsVerifiedAgainstActiveProviderLink() throws Exception {
        owner.setExternalIdType("rancher_id"); owner.setExternalId("42");
        CredentialRecord link = new CredentialRecord();
        link.setAccountId(42L); link.setKind("authIdentity"); link.setState("active");
        link.setData(Map.of("provider", "oidcconfig", "externalIdType", "oidc_user", "externalId", "issuer|owner"));
        doReturn(List.of(link)).when(auth).getIdentityLinks(42L);
        fixture(200, Map.of("data", live("oidc_user", "issuer|owner", "oidc_group")), Map.of());
        assertTrue(provider.getPrincipalIdentities(owner).contains(new Identity("oidc_group", "live-group")));
        link.setData(Map.of("provider", "other-provider", "externalIdType", "oidc_user", "externalId", "issuer|owner"));
        assertThrows(ClientVisibleException.class, () -> provider.getPrincipalIdentities(owner));
    }

    @Test public void invalidTokenRecordCannotContributeForeignCachedGroups() throws Exception {
        fixture(200, Map.of("data", live("oidc_user", "issuer|owner", "oidc_group")), Map.of());
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        session.setAccountId(9L); assertLive(); session.setAccountId(42L);
        session.setAuthenticatedAsAccountId(9L); assertLive(); session.setAuthenticatedAsAccountId(42L);
        session.setProvider("other-provider"); assertLive(); session.setProvider("oidcconfig");
        session.setExpires(new Date(0)); assertLive(); session.setExpires(new Date(System.currentTimeMillis() + 600_000));
        session.setVersion("old-version"); assertLive();
        verifyNoInteractions(crypto);
    }

    @Test public void expiredSignedSessionFallsBackToOwnerLiveSource() throws Exception {
        fixture(200, Map.of("data", live("oidc_user", "issuer|owner", "oidc_group")), Map.of());
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        when(crypto.getJsonPayload("owner-signed-session", true)).thenThrow(new TokenException("unit expired"));
        assertLive();
    }

    @Test public void signedForeignPrincipalCannotContributeCachedGroups() throws Exception {
        fixture(200, Map.of("data", live("oidc_user", "issuer|owner", "oidc_group")), Map.of());
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        when(crypto.getJsonPayload("owner-signed-session", true)).thenReturn(payload(99L)); assertLive();
    }

    @Test public void noLookupRetainsExistingOwnerSessionGroupsUntilItsExistingExpiry() throws Exception {
        setting(ServiceAuthConstants.NO_IDENTITY_LOOKUP_SETTING, true);
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        assertTrue(provider.getPrincipalIdentities(owner).contains(new Identity("oidc_group", "issuer|group")));
        session.setExpires(new Date(0));
        assertEquals(Set.of(new Identity("oidc_user", "issuer|owner")), provider.getPrincipalIdentities(owner));
        assertNull(ApiContext.getContext());
    }

    @Test public void noLookupWithoutSessionDoesNotAddNewTokenRequirementOrForeignGroups() {
        setting(ServiceAuthConstants.NO_IDENTITY_LOOKUP_SETTING, true);
        assertEquals(Set.of(new Identity("oidc_user", "issuer|owner")), provider.getPrincipalIdentities(owner));
        verifyNoInteractions(crypto); assertNull(ApiContext.getContext());
    }

    @Test public void legacySignedUserMustBelongToOwnerAndExistingProvider() {
        Map<String, Object> legacy = new HashMap<>(payload(42L)); legacy.remove(AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID);
        legacy.put(AbstractTokenUtil.ACCOUNT_ID, "issuer|owner");
        legacy.put(AbstractTokenUtil.USER_IDENTITY, identity("oidc_user", "issuer|owner", true));
        assertTrue(util.identitiesForAccount(owner, legacy).contains(new Identity("oidc_group", "issuer|group")));
        legacy.put(AbstractTokenUtil.ACCOUNT_ID, "issuer|other");
        legacy.put(AbstractTokenUtil.USER_IDENTITY, identity("oidc_user", "issuer|other", true));
        assertNull(util.identitiesForAccount(owner, legacy)); assertNull(ApiContext.getContext());
    }

    @Test public void cachedSessionStillChecksCurrentRestrictedProjectMembership() {
        setting(ServiceAuthConstants.ACCESSMODE_SETTING, AbstractTokenUtil.RESTRICTED_ACCESSMODE);
        setting(ServiceAuthConstants.IDENTITY_SEPARATOR_SETTING, ",");
        setting(ServiceAuthConstants.ALLOWED_IDENTITIES_SETTING, "");
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        when(auth.hasAccessToAnyProject(anySet(), eq(false), isNull())).thenReturn(true);
        assertFalse(provider.getPrincipalIdentities(owner).isEmpty());
        when(auth.hasAccessToAnyProject(anySet(), eq(false), isNull())).thenReturn(false);
        ClientVisibleException e = assertThrows(ClientVisibleException.class, () -> provider.getPrincipalIdentities(owner));
        assertEquals(403, e.getStatus()); verify(auth, times(2)).hasAccessToAnyProject(anySet(), eq(false), isNull());
    }

    @Test public void unconfiguredLocalProviderHasNoExternalOrHttpDependency() {
        setting(ServiceAuthConstants.EXTERNAL_AUTH_PROVIDER_SETTING, false);
        assertTrue(provider.getPrincipalIdentities(owner).isEmpty()); verifyNoInteractions(tokens, crypto);
        assertNull(ApiContext.getContext());
    }

    @Test public void ownerLocalRecoverySessionKeepsExistingRequiredModeSemantics() throws Exception {
        setting(ServiceAuthConstants.ACCESSMODE_SETTING, AbstractTokenUtil.REQUIRED_ACCESSMODE);
        setting(ServiceAuthConstants.IDENTITY_SEPARATOR_SETTING, ",");
        setting(ServiceAuthConstants.ALLOWED_IDENTITIES_SETTING, "oidc_group:required-group");
        setting(LocalAuthConstants.RECOVERY_ENABLED_SETTING, true);
        owner.setKind("admin"); when(auth.getAccountById(42L)).thenReturn(owner);
        when(accounts.isActiveAccount(owner)).thenReturn(true);
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        when(crypto.getJsonPayload("owner-signed-session", true)).thenReturn(Map.of(
                AbstractTokenUtil.TOKEN, LocalAuthConstants.JWT,
                AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID, "42", AbstractTokenUtil.ID_LIST, List.of("rancher_id:42")));
        fixture(401, Map.of(), Map.of());
        assertEquals(Set.of(new Identity("rancher_id", "42")), provider.getPrincipalIdentities(owner));
        assertEquals(0, getCalls.get()); assertNull(ApiContext.getContext());
    }

    @Test public void localKeyKeepsRancherRbacWithRequiredExternalModeAndRecoveryDisabled() throws Exception {
        localOwnerWithRequiredMode();
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        when(crypto.getJsonPayload("owner-signed-session", true)).thenReturn(Map.of(
                AbstractTokenUtil.TOKEN, LocalAuthConstants.JWT, AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID, "42",
                AbstractTokenUtil.ID_LIST, List.of("rancher_id:42")));
        assertTrue(provider.getPrincipalIdentities(owner).isEmpty());
        verifyNoInteractions(tokens, crypto); assertNull(ApiContext.getContext());
    }

    @Test public void linkedOidcWithoutAccessTokenIsNotTreatedAsLocal() throws Exception {
        localOwnerWithRequiredMode();
        CredentialRecord link = activeLink();
        doReturn(List.of(link)).when(auth).getIdentityLinks(42L);
        when(tokens.getTokenByAccountId(42L)).thenReturn(session);
        setting(ServiceAuthConstants.ALLOWED_IDENTITIES_SETTING, "oidc_group:issuer|group");
        assertTrue(provider.getPrincipalIdentities(owner).contains(new Identity("oidc_group", "issuer|group")));
        verify(crypto).getJsonPayload("owner-signed-session", true);
        setting(ServiceAuthConstants.ALLOWED_IDENTITIES_SETTING, "oidc_group:other-group");
        assertEquals(403, assertThrows(ClientVisibleException.class, () -> provider.getPrincipalIdentities(owner)).getStatus());
    }

    @Test public void onlyActiveOwnedCurrentProviderUserLinksPreventLocalFallback() {
        localOwnerWithRequiredMode();
        CredentialRecord link = activeLink(); doReturn(List.of(link)).when(auth).getIdentityLinks(42L);
        assertTrue(util.hasCurrentProviderPrincipal(owner));
        link.setState("inactive"); assertFalse(util.hasCurrentProviderPrincipal(owner)); link.setState("active");
        link.setRemoved(new Date()); assertFalse(util.hasCurrentProviderPrincipal(owner)); link.setRemoved(null);
        link.setAccountId(99L); assertFalse(util.hasCurrentProviderPrincipal(owner)); link.setAccountId(42L);
        link.setKind("apiKey"); assertFalse(util.hasCurrentProviderPrincipal(owner)); link.setKind("authIdentity");
        link.setData(Map.of("provider", "other-provider", "externalIdType", "oidc_user", "externalId", "issuer|owner"));
        assertFalse(util.hasCurrentProviderPrincipal(owner));
        link.setData(Map.of("provider", "oidcconfig", "externalIdType", "oidc_group", "externalId", "group"));
        assertFalse(util.hasCurrentProviderPrincipal(owner));
        link.setData(Map.of("provider", "oidcconfig", "externalIdType", "oidc_user", "externalId", ""));
        assertFalse(util.hasCurrentProviderPrincipal(owner)); assertNull(ApiContext.getContext());
    }

    @Test public void ordinaryOidcBrowserStillUsesCookieSessionIdentities() throws Exception {
        localOwnerWithRequiredMode();
        setting(ServiceAuthConstants.ALLOWED_IDENTITIES_SETTING, "oidc_group:issuer|group");
        browserSession(payload(42L));
        assertTrue(provider.getIdentities(owner).contains(new Identity("oidc_group", "issuer|group")));
        verify(tokens, atLeastOnce()).getTokenByKey("browser-session"); verify(tokens, never()).getTokenByAccountId(anyLong());
    }

    @Test public void ordinaryLocalBrowserStillEnforcesRecoveryAndExternalAccessMode() throws Exception {
        localOwnerWithRequiredMode();
        browserSession(Map.of(AbstractTokenUtil.TOKEN, LocalAuthConstants.JWT,
                AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID, "42", AbstractTokenUtil.ID_LIST, List.of("rancher_id:42")));
        assertEquals(403, assertThrows(ClientVisibleException.class, () -> provider.getIdentities(owner)).getStatus());
        setting(LocalAuthConstants.RECOVERY_ENABLED_SETTING, true);
        assertEquals(Set.of(new Identity("rancher_id", "42")), provider.getIdentities(owner));
        verify(tokens, never()).getTokenByAccountId(anyLong());
    }

    private void localOwnerWithRequiredMode() {
        owner.setKind("admin"); owner.setExternalIdType("rancher_id"); owner.setExternalId("42");
        DataAccessor.fields(owner).withKey(ServiceAuthConstants.ACCESS_TOKEN).set(null);
        setting(ServiceAuthConstants.ACCESSMODE_SETTING, AbstractTokenUtil.REQUIRED_ACCESSMODE);
        setting(ServiceAuthConstants.IDENTITY_SEPARATOR_SETTING, ",");
        setting(ServiceAuthConstants.ALLOWED_IDENTITIES_SETTING, "oidc_group:required-group");
        setting(LocalAuthConstants.RECOVERY_ENABLED_SETTING, false);
        when(auth.getAccountById(42L)).thenReturn(owner); when(accounts.isActiveAccount(owner)).thenReturn(true);
    }
    private CredentialRecord activeLink() {
        CredentialRecord link = new CredentialRecord(); link.setAccountId(42L); link.setKind("authIdentity"); link.setState("active");
        link.setData(Map.of("provider", "oidcconfig", "externalIdType", "oidc_user", "externalId", "issuer|owner"));
        return link;
    }
    private void browserSession(Map<String, Object> payload) throws Exception {
        BrowserUtil browser = new BrowserUtil(); browser.dependencies(auth, accounts);
        Field service = AbstractTokenUtil.class.getDeclaredField("tokenService"); service.setAccessible(true); service.set(browser, crypto);
        Field dao = AbstractTokenUtil.class.getDeclaredField("authTokenDao"); dao.setAccessible(true); dao.set(browser, tokens);
        provider.tokenUtil = browser;
        when(tokens.getTokenByKey("browser-session")).thenReturn(session);
        when(crypto.getJsonPayload("owner-signed-session", true)).thenReturn(payload);
        HttpServletRequest servlet = mock(HttpServletRequest.class);
        when(servlet.getCookies()).thenReturn(new Cookie[]{new Cookie("token", "browser-session")});
        ApiContext.newContext().setApiRequest(new ApiRequest(new ApiServletContext(servlet, null, null), null));
    }

    private void assertLive() {
        Set<Identity> result = provider.getPrincipalIdentities(owner);
        assertTrue(result.contains(new Identity(ServiceAuthConstants.USER_TYPE.get().replace("_user", "_group"), "live-group")));
        assertFalse(result.contains(new Identity("oidc_group", "issuer|group")));
    }
    private void unavailable() {
        ClientVisibleException e = assertThrows(ClientVisibleException.class, () -> provider.getPrincipalIdentities(owner));
        assertEquals(503, e.getStatus()); assertEquals("AuthenticationUnavailable", e.getCode());
    }
    private void fixture(int status, Object getBody, Object postBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] getBytes = json.writeValueAsString(getBody).getBytes(StandardCharsets.UTF_8);
        byte[] postBytes = json.writeValueAsString(postBody).getBytes(StandardCharsets.UTF_8);
        server.createContext("/v1-auth/me/identities", exchange -> {
            getCalls.incrementAndGet(); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("GET", exchange.getRequestMethod()); assertNull(exchange.getRequestURI().getQuery());
            exchange.sendResponseHeaders(status, getBytes.length); exchange.getResponseBody().write(getBytes); exchange.close();
        });
        server.createContext("/v1-auth/token", exchange -> {
            postCalls.incrementAndGet(); posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("POST", exchange.getRequestMethod());
            exchange.sendResponseHeaders(200, postBytes.length); exchange.getResponseBody().write(postBytes); exchange.close();
        });
        server.start(); setting("system.stack.auth.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1-auth");
    }
    private static Map<String, Object> payload(long principal) {
        return Map.of(AbstractTokenUtil.TOKEN, "externaljwt", AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID, String.valueOf(principal),
                AbstractTokenUtil.ID_LIST, List.of("rancher_id:" + principal, "oidc_user:issuer|owner", "oidc_group:issuer|group"));
    }
    private static List<Map<String, Object>> live(String userType, String subject, String groupType) {
        return List.of(identity(userType, subject, true), identity(groupType, "live-group", false));
    }
    private static Map<String, Object> identity(String type, String subject, boolean user) {
        return Map.of("externalIdType", type, "externalId", subject, "user", user);
    }
    private static void setting(String key, Object value) { ConfigurationManager.getConfigInstance().setProperty(key, value); }
    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static class ContextForbiddenTokenUtil extends ExternalServiceTokenUtil {
        void dependencies(AuthDao dao, AccountDao accounts) { authDao = dao; accountDao = accounts; }
        @Override public boolean findAndSetJWT() { throw new AssertionError("principal resolver consumed request token"); }
        @Override public String getJWT() { throw new AssertionError("principal decoder read getJWT"); }
        @Override public Set<Identity> getIdentities() { throw new AssertionError("principal decoder read request context"); }
        @Override public io.cattle.platform.iaas.api.auth.identity.Token createToken(Set<Identity> ids,
                io.cattle.platform.core.model.Account account, String login) { throw new AssertionError("created platform session"); }
    }
    private static class BrowserUtil extends ExternalServiceTokenUtil {
        void dependencies(AuthDao dao, AccountDao accounts) { authDao = dao; accountDao = accounts; }
    }
}
