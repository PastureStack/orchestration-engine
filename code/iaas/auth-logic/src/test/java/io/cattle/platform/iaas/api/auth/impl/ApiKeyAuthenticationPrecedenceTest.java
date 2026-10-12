package io.cattle.platform.iaas.api.auth.impl;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
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

    @Test public void dedicatedRegistrationBasicUsesItsValidatorRegardlessOfLookupOrder() {
        registrationBasic(true);
    }

    @Test public void rejectedRegistrationBasicNeverFallsBackToKeyOrCookie() {
        registrationBasic(false);
    }

    private void registrationBasic(boolean valid) {
        ApiAuthenticator auth = new ApiAuthenticator();
        AccountLookup cookie = mock(AccountLookup.class), basic = mock(AccountLookup.class), registration = mock(AccountLookup.class);
        when(cookie.isConfigured()).thenReturn(true);
        when(basic.isConfigured()).thenReturn(true);
        when(basic.getName()).thenReturn("BasicAuth");
        when(registration.isConfigured()).thenReturn(true);
        when(registration.acceptsBasicUsername("registrationToken")).thenReturn(true);
        when(registration.getName()).thenReturn("RegistrationToken");
        ApiRequest request = basicRequest("registrationToken:fixture-registration-secret");
        Account account = mock(Account.class);
        when(registration.getAccount(request)).thenReturn(valid ? account : null);
        auth.accountLookups = List.of(cookie, basic, registration);
        assertSame(valid ? account : null, auth.getAccount(request));
        verify(registration, times(1)).getAccount(request);
        verify(cookie, never()).getAccount(any());
        verify(basic, never()).getAccount(any());
        assertEquals(valid ? "RegistrationToken" : null, request.getAttribute(AccountConstants.AUTH_TYPE));
        assertNull(ApiKeyCredentialContext.get(request));
    }

    @Test public void unclaimedBasicStillUsesOnlyTheKeyValidator() {
        ApiAuthenticator auth = new ApiAuthenticator();
        AccountLookup cookie = mock(AccountLookup.class), basic = mock(AccountLookup.class), registration = mock(AccountLookup.class);
        when(registration.isConfigured()).thenReturn(true);
        when(basic.isConfigured()).thenReturn(true);
        when(basic.getName()).thenReturn("BasicAuth");
        ApiRequest request = basicRequest("fixture-key:fixture-key-secret");
        Account account = mock(Account.class);
        when(basic.getAccount(request)).thenReturn(account);
        auth.accountLookups = List.of(cookie, registration, basic);
        assertSame(account, auth.getAccount(request));
        verify(registration, never()).getAccount(any());
        verify(cookie, never()).getAccount(any());
        verify(basic, times(1)).getAccount(request);
    }

    private ApiRequest basicRequest(String credential) {
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn("Basic " + java.util.Base64.getEncoder()
                .encodeToString(credential.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ApiServletContext servlet = mock(ApiServletContext.class);
        when(servlet.getRequest()).thenReturn(http);
        return new ApiRequest(servlet, null);
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
