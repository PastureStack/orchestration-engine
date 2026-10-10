package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.integration.internal.rancher.BasicAuthImpl;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyAuthenticationAuditTest {
    @Before public void setup() { ApiContext.newContext().setIdFormatter(new IdentityFormatter()); }
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void revokedProofPersistsTrustedDenialBeforeForbidden() {
        ApiKeyCredentialContext context = new ApiKeyCredentialContext(12, 77, "apiKey", 0, null);
        BasicAuthImpl lookup = lookup(new VerifiedApiCredential(null, context, "ApiKeyRevoked"));
        AtomicInteger records = new AtomicInteger();
        lookup.setAuditSinks(List.of((request, policy) -> {
            assertNull(policy);
            assertEquals(77L, request.getAttribute("apiKey.audit.verifiedPrincipalAccountId"));
            assertEquals("DENY", request.getAttribute("apiKey.audit.decision"));
            request.setAttribute("apiKey.audit.admitted", true);
            records.incrementAndGet();
        }));
        ApiRequest request = request(basic());
        ClientVisibleException denied = assertThrows(ClientVisibleException.class, () -> lookup.getAccount(request));
        assertEquals(403, denied.getStatus()); assertEquals("ApiKeyRevoked", denied.getCode());
        assertEquals(1, records.get()); assertEquals(context, ApiKeyCredentialContext.get(request));
    }

    @Test public void unknownOrWrongSecretRemainsAnonymousAndPreservesNullToUnauthorizedContract() {
        BasicAuthImpl lookup = lookup(null);
        AtomicInteger records = new AtomicInteger();
        lookup.setAuditSinks(List.of(new ApiKeyAuditSink() {
            @Override public void recordDecision(ApiRequest request, Policy policy) { fail("No proof means no Key decision"); }
            @Override public void recordAuthenticationDenied(ApiRequest request, String code) {
                assertEquals("InvalidApiCredential", code);
                assertNull(ApiKeyCredentialContext.get(request));
                assertNull(request.getAttribute("apiKey.audit.keyId"));
                assertNull(request.getAttribute("apiKey.audit.verifiedPrincipalAccountId"));
                request.setAttribute("apiKey.audit.admitted", true); records.incrementAndGet();
            }
        }));
        assertNull(lookup.getAccount(request(basic()))); assertEquals(1, records.get());
    }

    @Test public void malformedBasicVariantsAreAnonymousButAbsentAndBearerRemainUntouched() {
        BasicAuthImpl lookup = lookup(null);
        AtomicInteger records = new AtomicInteger();
        lookup.setAuditSinks(List.of(new ApiKeyAuditSink() {
            @Override public void recordDecision(ApiRequest request, Policy policy) { fail(); }
            @Override public void recordAuthenticationDenied(ApiRequest request, String code) {
                assertEquals("MalformedBasicCredentials", code); records.incrementAndGet();
            }
        }));
        for (String header : List.of("Basic", " basic\t!!! ", "BASIC invalid extra")) assertNull(lookup.getAccount(request(header)));
        assertEquals(3, records.get());
        assertNull(lookup.getAccount(request(null))); assertNull(lookup.getAccount(request("Bearer private-jwt")));
        assertEquals(3, records.get());
    }

    @Test public void unavailableOrMissingDurableSinkCannotReturnNormalAuthenticationFailure() {
        BasicAuthImpl lookup = lookup(null);
        ClientVisibleException unavailable = assertThrows(ClientVisibleException.class, () -> lookup.getAccount(request(basic())));
        assertEquals(503, unavailable.getStatus()); assertEquals("AuditUnavailable", unavailable.getCode());
        lookup.setAuditSinks(List.of(new ApiKeyAuditSink() {
            @Override public void recordDecision(ApiRequest request, Policy policy) { }
            @Override public void recordAuthenticationDenied(ApiRequest request, String code) { throw new IllegalStateException("private storage value"); }
        }));
        unavailable = assertThrows(ClientVisibleException.class, () -> lookup.getAccount(request(basic())));
        assertEquals(503, unavailable.getStatus()); assertNull(unavailable.getCause());
    }

    private static BasicAuthImpl lookup(VerifiedApiCredential result) {
        AuthDao dao = mock(AuthDao.class);
        when(dao.getVerifiedApiCredential(anyString(), anyString(), any())).thenReturn(result);
        BasicAuthImpl lookup = new BasicAuthImpl(); lookup.setAuthDao(dao); return lookup;
    }
    private static String basic() {
        return "Basic " + Base64.getEncoder().encodeToString("test-public:test-secret".getBytes(StandardCharsets.UTF_8));
    }
    private static ApiRequest request(String header) {
        HttpServletRequest servlet = mock(HttpServletRequest.class); when(servlet.getHeader("Authorization")).thenReturn(header);
        return new ApiRequest(new ApiServletContext(servlet, null, null), null);
    }
}
