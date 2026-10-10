package io.cattle.platform.iaas.api.auth.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.netflix.config.ConfigurationManager;
import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.AuthTokenRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.iaas.api.auth.AbstractTokenUtil;
import io.cattle.platform.iaas.api.auth.AchaiusPolicyOptionsFactory;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.iaas.api.auth.integration.external.ExternalServiceAuthProvider;
import io.cattle.platform.iaas.api.auth.integration.external.ExternalServiceTokenUtil;
import io.cattle.platform.iaas.api.auth.integration.external.ServiceAuthConstants;
import io.cattle.platform.iaas.api.auth.integration.internal.rancher.RancherIdentityProvider;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.token.TokenService;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiAuthenticatorPrincipalIdentitiesTest {
    private BoundaryAuthenticator authenticator;
    private AuthDao auth;
    private AccountRecord principal, project;
    private ExternalServiceAuthProvider external;
    private SchemaFactory schemas;
    private static final List<String> SETTINGS = List.of(SecurityConstants.AUTH_PROVIDER_SETTING,
            ServiceAuthConstants.EXTERNAL_AUTH_PROVIDER_SETTING, ServiceAuthConstants.USERTYPE_SETTING,
            ServiceAuthConstants.ACCESSMODE_SETTING, ServiceAuthConstants.NO_IDENTITY_LOOKUP_SETTING);

    @Before public void setup() throws Exception {
        ApiContext.remove();
        ConfigurationManager.getConfigInstance().setProperty(SecurityConstants.AUTH_PROVIDER_SETTING, "oidcconfig");
        ConfigurationManager.getConfigInstance().setProperty(ServiceAuthConstants.EXTERNAL_AUTH_PROVIDER_SETTING, true);
        ConfigurationManager.getConfigInstance().setProperty(ServiceAuthConstants.USERTYPE_SETTING, "oidc_user");
        ConfigurationManager.getConfigInstance().setProperty(ServiceAuthConstants.NO_IDENTITY_LOOKUP_SETTING, false);
        ConfigurationManager.getConfigInstance().setProperty(ServiceAuthConstants.ACCESSMODE_SETTING, AbstractTokenUtil.UNRESTRICTED_ACCESSMODE);
        principal = account(42L, "user"); project = account(5L, "project");
        principal.setExternalIdType("oidc_user"); principal.setExternalId("issuer|owner");
        auth = mock(AuthDao.class); AccountDao accounts = mock(AccountDao.class);
        when(auth.getAccountById(42L)).thenReturn(principal); when(auth.getAccountById(5L)).thenReturn(project);
        when(accounts.getAccountById(42L)).thenReturn(principal); when(accounts.isActiveAccount(any())).thenReturn(true);
        when(auth.hasAccessToProject(eq(5L), eq(42L), anyBoolean(), anySet())).thenReturn(true);
        when(auth.getRole(any(), any(), any())).thenReturn("owner");
        DefaultAuthorizationProvider authorization = new DefaultAuthorizationProvider();
        authorization.authDao = auth; authorization.accountDao = accounts;
        authorization.setOptionsFactory(new AchaiusPolicyOptionsFactory());
        schemas = mock(SchemaFactory.class); authorization.schemaFactories.put("owner", schemas);
        external = new ExternalServiceAuthProvider();
        AuthTokenDao tokens = mock(AuthTokenDao.class); TokenService crypto = mock(TokenService.class);
        AuthTokenRecord token = new AuthTokenRecord(); token.setAccountId(42L); token.setAuthenticatedAsAccountId(42L);
        token.setProvider("oidcconfig"); token.setVersion(SecurityConstants.TOKEN_VERSION); token.setValue("owner-session");
        token.setExpires(new Date(System.currentTimeMillis() + 600_000));
        when(tokens.getTokenByAccountId(42L)).thenReturn(token);
        when(crypto.getJsonPayload("owner-session", true)).thenReturn(Map.of(
                AbstractTokenUtil.TOKEN, "externaljwt", AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID, "42",
                AbstractTokenUtil.ID_LIST, List.of("rancher_id:42", "oidc_user:issuer|owner", "oidc_group:owner-group")));
        BoundUtil util = new BoundUtil(); util.dependencies(auth, accounts);
        inject(external, "tokenUtil", util); inject(external, "tokenService", crypto); inject(external, "authTokenDao", tokens);
        authenticator = new BoundaryAuthenticator(); authenticator.principal = principal;
        authenticator.authDao = auth; authenticator.accountDao = accounts; authenticator.externalAuthProvider = external;
        authenticator.identityProviders = List.of(new RancherIdentityProvider());
        authenticator.authorizationProviders = List.of(authorization);
        authenticator.apiKeyAuthorization = mock(ApiKeyAuthorizationService.class);
    }

    @After public void cleanup() {
        ApiContext.remove(); SETTINGS.forEach(key -> ConfigurationManager.getConfigInstance().clearProperty(key));
    }

    @Test public void actualCurrentAuthorizationRebuildsAccountPolicyWithoutAnyHttpContext() {
        ApiRequest request = new ApiRequest((ApiServletContext) null, null);
        ApiAuthenticator.CurrentAuthorization current = authenticator.currentAuthorization(42L, 5L, request);
        assertTrue(current.policy() instanceof AccountPolicy); assertEquals(42L, current.policy().getAuthenticatedAsAccountId());
        assertEquals(5L, current.policy().getAccountId()); assertSame(schemas, current.schemas());
        assertTrue(current.policy().getIdentities().contains(new Identity("oidc_group", "owner-group")));
        InstanceRecord own = new InstanceRecord(); own.setAccountId(5L);
        InstanceRecord other = new InstanceRecord(); other.setAccountId(6L);
        assertSame(own, current.policy().authorizeObject(own)); assertNull(current.policy().authorizeObject(other));
        assertNull(ApiContext.getContext()); assertNull(request.getServletContext());
    }

    @Test public void backgroundCurrentMembershipRevocationRemainsAuthoritative() {
        ApiRequest request = new ApiRequest((ApiServletContext) null, null);
        authenticator.currentAuthorization(42L, 5L, request);
        when(auth.hasAccessToProject(eq(5L), eq(42L), anyBoolean(), anySet())).thenReturn(false);
        ClientVisibleException failure = assertThrows(ClientVisibleException.class,
                () -> authenticator.currentAuthorization(42L, 5L, request));
        assertEquals(403, failure.getStatus()); assertEquals("OwnerPermissionDenied", failure.getCode());
        verify(auth, times(2)).hasAccessToProject(eq(5L), eq(42L), anyBoolean(), anySet());
        assertNull(ApiContext.getContext());
    }

    @Test public void verifiedLegacyKeyUsesOwnerIdentitiesEvenWithAnotherPersonsCookie() throws Exception {
        HttpServletRequest servlet = mock(HttpServletRequest.class);
        when(servlet.getCookies()).thenReturn(new Cookie[]{new Cookie("token", "other-person-cookie")});
        ApiRequest request = new ApiRequest(new ApiServletContext(servlet, null, null), null); request.setMethod("GET");
        ApiContext.newContext().setApiRequest(request); authenticator.key = true;
        authenticator.authenticate(request);
        assertNotNull(ApiKeyCredentialContext.get(request)); assertTrue(authenticator.saved instanceof AccountPolicy);
        assertTrue(authenticator.saved.getIdentities().contains(new Identity("oidc_group", "owner-group")));
        assertFalse(authenticator.saved.getIdentities().contains(new Identity("oidc_group", "other-person-group")));
        verify(servlet, never()).getCookies();
    }

    @Test public void normalSessionKeepsExistingExternalProviderPath() throws Exception {
        ExternalServiceAuthProvider sessionProvider = mock(ExternalServiceAuthProvider.class);
        when(sessionProvider.getIdentities(principal)).thenReturn(Set.of(new Identity("oidc_group", "session-group")));
        authenticator.externalAuthProvider = sessionProvider;
        ApiRequest request = new ApiRequest(new ApiServletContext(mock(HttpServletRequest.class), null, null), null);
        ApiContext.newContext().setApiRequest(request);
        authenticator.authenticate(request);
        assertTrue(authenticator.saved.getIdentities().contains(new Identity("oidc_group", "session-group")));
        verify(sessionProvider).getIdentities(principal); verify(sessionProvider, never()).getPrincipalIdentities(any());
    }

    @Test public void localProviderBackgroundKeepsStablePrincipalIdentityWithoutExternalToken() {
        ConfigurationManager.getConfigInstance().setProperty(ServiceAuthConstants.EXTERNAL_AUTH_PROVIDER_SETTING, false);
        ApiAuthenticator.CurrentAuthorization current = authenticator.currentAuthorization(42L, 5L,
                new ApiRequest((ApiServletContext) null, null));
        assertEquals(Set.of(new Identity("rancher_id", "42")), current.policy().getIdentities());
        assertNull(ApiContext.getContext());
    }

    private static AccountRecord account(long id, String kind) {
        AccountRecord value = new AccountRecord(); value.setId(id); value.setKind(kind); value.setState("active"); return value;
    }
    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static class BoundUtil extends ExternalServiceTokenUtil {
        void dependencies(AuthDao dao, AccountDao accounts) { authDao = dao; accountDao = accounts; }
        @Override public boolean findAndSetJWT() { throw new AssertionError("owner identity lookup consumed Cookie"); }
        @Override public String getJWT() { throw new AssertionError("owner decoder read current request"); }
    }
    private static class BoundaryAuthenticator extends ApiAuthenticator {
        Account principal; boolean key; Policy saved;
        @Override protected Account getAccount(ApiRequest request) {
            if (key) ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(12, 42, "apiKey", 0, null));
            return principal;
        }
        @Override protected void saveInContext(ApiRequest request, Policy policy, SchemaFactory schemas, Account owner) {
            saved = policy; ApiContext.getContext().setPolicy(policy);
        }
    }
}
