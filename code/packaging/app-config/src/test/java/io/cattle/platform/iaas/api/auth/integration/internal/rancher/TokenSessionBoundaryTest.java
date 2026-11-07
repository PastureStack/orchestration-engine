package io.cattle.platform.iaas.api.auth.integration.internal.rancher;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.netflix.config.ConfigurationManager;
import io.cattle.platform.api.auth.ApiCookieAuthentication;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.AuthTokenRecord;
import io.cattle.platform.iaas.api.auth.AbstractTokenUtil;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.iaas.api.auth.integration.local.LocalAuthConstants;
import io.cattle.platform.iaas.api.auth.integration.local.LocalAuthTokenUtils;
import io.cattle.platform.token.impl.*;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.*;

public class TokenSessionBoundaryTest {
    private final String key = "PRIVATE_SESSION_KEY";
    private final TokenAuthLookup owner = new TokenAuthLookup();
    private final AuthTokenDao dao = mock(AuthTokenDao.class);
    private final AuthTokenRecord row = new AuthTokenRecord();
    private final AccountRecord actor = new AccountRecord();
    private final Cookie cookie = new Cookie("token", key);
    private ApiRequest request;
    private JwtTokenServiceImpl jwt;
    private boolean live = true;
    @Before public void setup() throws Exception {
        ConfigurationManager.getConfigInstance().setProperty(SecurityConstants.AUTH_PROVIDER_SETTING, LocalAuthConstants.CONFIG);
        ConfigurationManager.getConfigInstance().setProperty(LocalAuthConstants.ACCESS_MODE_SETTING, "unrestricted");
        var keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048); var pair = keys.generateKeyPair();
        RSAKeyProvider provider = mock(RSAKeyProvider.class);
        when(provider.getPrivateKey()).thenReturn(new RSAPrivateKeyHolder("probe", (RSAPrivateKey) pair.getPrivate()));
        when(provider.getDefaultPublicKey()).thenReturn(pair.getPublic()); jwt = new JwtTokenServiceImpl(); jwt.setKeyProvider(provider);
        actor.setId(42L); actor.setState("active");
        AuthDao accounts = mock(AuthDao.class); when(accounts.getAccountById(42L)).thenReturn(actor);
        AccountDao states = mock(AccountDao.class); when(states.isActiveAccount(any(Account.class))).thenAnswer(call -> "active".equals(actor.getState()));
        row.setId(71L); row.setKey(key); row.setAccountId(42L); row.setAuthenticatedAsAccountId(42L);
        row.setClientSessionId("generation-a"); row.setProvider(LocalAuthConstants.CONFIG); row.setVersion(SecurityConstants.TOKEN_VERSION);
        row.setExpires(new Date(System.currentTimeMillis()+60000)); row.setValue(payload(60000));
        when(dao.getTokenByKey(key)).thenAnswer(call -> live ? row : null);
        LocalAuthTokenUtils tokens = new LocalAuthTokenUtils();
        set(tokens, AbstractTokenUtil.class, "authTokenDao", dao); set(tokens, AbstractTokenUtil.class, "tokenService", jwt);
        set(tokens, AbstractTokenUtil.class, "authDao", accounts); set(tokens, AbstractTokenUtil.class, "accountDao", states);
        owner.tokenUtils = List.of(tokens); owner.sessionTokens = dao; owner.init();
        HttpServletRequest http = mock(HttpServletRequest.class); when(http.getCookies()).thenReturn(new Cookie[]{cookie});
        ApiServletContext servlet = mock(ApiServletContext.class); when(servlet.getRequest()).thenReturn(http);
        request = new ApiRequest(servlet,null); request.setType("aiResourceView");
        ApiContext.newContext().setApiRequest(request); assertSame(actor,owner.getAccount(request));
    }
    @After public void cleanup() {
        ApiContext.remove(); ConfigurationManager.getConfigInstance().clearProperty(SecurityConstants.AUTH_PROVIDER_SETTING);
        ConfigurationManager.getConfigInstance().clearProperty(LocalAuthConstants.ACCESS_MODE_SETTING);
    }
    @Test public void capturesSameDurableSessionWithoutExportingHandleFields() {
        var handle = owner.captureVerifiedSession(request,42); owner.requireCurrentSession(handle,request);
        assertEquals(0,handle.getClass().getConstructors().length); assertFalse(handle.toString().contains(key));
        assertEquals(0,java.util.Arrays.stream(handle.getClass().getMethods()).filter(m -> m.getDeclaringClass()==handle.getClass()).count());
    }
    @Test public void revokeAndExpiredDurableRecordAreDenied() {
        var handle=owner.captureVerifiedSession(request,42); live=false; deny(() -> owner.requireCurrentSession(handle,request));
        live=true; row.setExpires(new Date(1)); deny(() -> owner.requireCurrentSession(handle,request));
    }
    @Test public void recordGenerationActorAccountProviderVersionAndJwtAreBound() {
        var handle=owner.captureVerifiedSession(request,42);
        row.setClientSessionId("generation-b"); deny(() -> owner.requireCurrentSession(handle,request)); row.setClientSessionId("generation-a");
        row.setId(72L); deny(() -> owner.requireCurrentSession(handle,request)); row.setId(71L);
        row.setAuthenticatedAsAccountId(43L); deny(() -> owner.requireCurrentSession(handle,request)); row.setAuthenticatedAsAccountId(42L);
        row.setAccountId(5L); deny(() -> owner.requireCurrentSession(handle,request)); row.setAccountId(42L);
        row.setProvider("other"); deny(() -> owner.requireCurrentSession(handle,request)); row.setProvider(LocalAuthConstants.CONFIG);
        row.setVersion("v2"); deny(() -> owner.requireCurrentSession(handle,request)); row.setVersion(SecurityConstants.TOKEN_VERSION);
        row.setValue(payload(-60000)); deny(() -> owner.requireCurrentSession(handle,request));
    }
    @Test public void differentRequestContextChangedCookieAndNullHandleAreDenied() {
        var handle=owner.captureVerifiedSession(request,42);
        ApiRequest other = new ApiRequest(request); deny(() -> owner.requireCurrentSession(handle,other));
        ApiContext.getContext().setApiRequest(other); deny(() -> owner.requireCurrentSession(handle,request));
        ApiContext.getContext().setApiRequest(request); cookie.setValue("OTHER_SECRET"); deny(() -> owner.requireCurrentSession(handle,request));
        deny(() -> owner.requireCurrentSession(null,request));
    }
    @Test public void inactiveActorAndForgedProvenanceDoNotBecomeLiveAuthority() {
        var handle=owner.captureVerifiedSession(request,42); actor.setState("inactive"); deny(() -> owner.requireCurrentSession(handle,request));
        actor.setState("active"); request.setAttribute(ApiCookieAuthentication.class.getName(),Boolean.TRUE);
        deny(() -> owner.captureVerifiedSession(request,42));
    }
    @Test public void rawJwtCompatibilityNeverBecomesDurableSessionAuthority() {
        cookie.setValue(payload(60000)); assertSame(actor,owner.getAccount(request));
        deny(() -> owner.captureVerifiedSession(request,42));
    }
    @Test public void expiredJwtCannotMintAuthorityFromVerifiedMarkerAndFutureDurableExpiry() {
        assertTrue(ApiCookieAuthentication.isVerified(request));
        assertTrue(row.getExpires().after(new Date()));
        row.setValue(payload(-60000));
        deny(() -> owner.captureVerifiedSession(request, 42));
    }
    @Test public void nonpositiveDurableTokenAccountCannotBecomeSessionAuthority() {
        for (long account : new long[] {0, -1}) {
            row.setAccountId(account);
            deny(() -> owner.captureVerifiedSession(request, 42));
        }
    }
    public TokenAuthLookup owner() { return owner; }
    public ApiRequest verifiedRequest() { return request; }
    public void revoke() { live = false; }
    private String payload(long expiry) { return jwt.generateEncryptedToken(Map.of(AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID,"42",AbstractTokenUtil.ID_LIST,List.of()),new Date(System.currentTimeMillis()+expiry)); }
    private static void set(Object target,Class<?> owner,String field,Object value)throws Exception {var f=owner.getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
    private static void deny(Runnable call) {var e=assertThrows(ClientVisibleException.class,call::run);assertEquals(403,e.getStatus());assertEquals("SessionAuthorityChanged",e.getCode());assertFalse(e.toString().contains("PRIVATE"));}
}
