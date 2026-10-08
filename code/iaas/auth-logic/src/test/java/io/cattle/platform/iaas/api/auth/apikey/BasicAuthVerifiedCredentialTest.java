package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;

import io.cattle.platform.core.model.Account;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.integration.internal.rancher.BasicAuthImpl;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BasicAuthVerifiedCredentialTest {
    @Before public void setup() { ApiContext.newContext().setIdFormatter(new IdentityFormatter()); }
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void verifiedLegacyPrincipalIsRetainedAndContextHasNoPolicy() {
        AccountRecord account = account(77);
        ApiKeyCredentialContext context = new ApiKeyCredentialContext(12, 77, "apiKey", 0, null);
        ApiRequest request = request();
        assertSame(account, lookup(account, context).getAccount(request));
        assertEquals(context, ApiKeyCredentialContext.get(request));
        assertEquals("12", request.getAttribute("apiKey.audit.keyId"));
        assertEquals(0L, request.getAttribute("apiKey.audit.policyRevision"));
    }

    @Test public void expiredCredentialIsRecordedThenRejectedWithoutFallingBack() {
        ApiKeyPolicy expired = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW,
                Instant.EPOCH, List.of());
        ApiKeyCredentialContext context = new ApiKeyCredentialContext(12, 42, "apiKeyRestricted", 3, expired);
        ApiRequest request = request();
        ClientVisibleException failure = assertThrows(ClientVisibleException.class,
                () -> lookup(account(42), context).getAccount(request));
        assertEquals(403, failure.getStatus());
        assertEquals("ApiKeyExpired", failure.getCode());
        assertEquals(context, ApiKeyCredentialContext.get(request));
        assertEquals("ApiKeyExpired", request.getAttribute("apiKey.audit.reason"));
    }

    @Test public void agentCredentialKeepsExistingAccountWithoutUserPolicyContext() {
        AccountRecord account = account(88);
        ApiRequest request = request();
        assertSame(account, lookup(account, null).getAccount(request));
        assertNull(ApiKeyCredentialContext.get(request));
    }

    private BasicAuthImpl lookup(Account account, ApiKeyCredentialContext context) {
        BasicAuthImpl lookup = new BasicAuthImpl();
        lookup.setAuthDao((AuthDao) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AuthDao.class},
                (proxy, method, args) -> "getVerifiedApiCredential".equals(method.getName())
                        ? new VerifiedApiCredential(account, context) : null));
        lookup.setAuditSinks(List.of((request, policy) -> request.setAttribute("apiKey.audit.admitted", true)));
        return lookup;
    }
    private static AccountRecord account(long id) {
        AccountRecord account = new AccountRecord(); account.setId(id); account.setKind("project"); return account;
    }
    private static ApiRequest request() {
        String header = "Basic " + Base64.getEncoder().encodeToString("test-public:test-secret".getBytes(StandardCharsets.UTF_8));
        HttpServletRequest servlet = (HttpServletRequest) Proxy.newProxyInstance(BasicAuthVerifiedCredentialTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> "getHeader".equals(method.getName()) ? header : null);
        return new ApiRequest(new ApiServletContext(servlet, null, null), null);
    }
}
