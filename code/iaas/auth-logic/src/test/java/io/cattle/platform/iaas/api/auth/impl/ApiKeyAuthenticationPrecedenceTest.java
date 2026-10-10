package io.cattle.platform.iaas.api.auth.impl;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.integration.interfaces.AccountLookup;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.Test;

public class ApiKeyAuthenticationPrecedenceTest {
    @Test public void explicitBasicKeyCannotBecomeExistingBrowserCookie() {
        check(true);
    }
    @Test public void invalidExplicitKeyCannotFallBackToBroaderCookie() {
        check(false);
    }
    @Test public void malformedOrWhitespaceBasicCannotFallBackToCookie() {
        for (String header : List.of("Basic", " basic\tprivate-value ", "BASIC private extra")) check(false, header);
    }
    private void check(boolean validKey) {
        check(validKey, "Basic opaque-test-value");
    }
    private void check(boolean validKey, String header) {
        ApiAuthenticator auth = new ApiAuthenticator();
        AccountLookup cookie = mock(AccountLookup.class), basic = mock(AccountLookup.class);
        when(cookie.getName()).thenReturn("TokenAuth"); when(cookie.isConfigured()).thenReturn(true);
        when(basic.getName()).thenReturn("BasicAuth"); when(basic.isConfigured()).thenReturn(true);
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn(header);
        ApiServletContext servlet = mock(ApiServletContext.class); when(servlet.getRequest()).thenReturn(http);
        ApiRequest request = new ApiRequest(servlet, null);
        Account keyOwner = mock(Account.class);
        when(basic.getAccount(request)).thenReturn(validKey ? keyOwner : null);
        auth.accountLookups = List.of(cookie, basic);
        assertSame(validKey ? keyOwner : null, auth.getAccount(request));
        verify(cookie, never()).getAccount(request);
    }
}
