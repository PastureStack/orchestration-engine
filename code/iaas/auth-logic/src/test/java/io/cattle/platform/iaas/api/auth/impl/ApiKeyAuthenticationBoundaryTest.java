package io.cattle.platform.iaas.api.auth.impl;

import static org.junit.Assert.*;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyAuthenticationBoundaryTest {
    @Before public void setup() { ApiContext.newContext().setIdFormatter(new IdentityFormatter()); }
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void ownerProjectOrSchemaDenialPersistsBeforeResponseWithoutChangingRbacResult() {
        for (String code : List.of("OwnerPermissionDenied", "Forbidden", "InvalidFormat")) {
            ApiAuthenticator auth = failing(code); AtomicInteger records = new AtomicInteger();
            auth.auditSinks = List.of((request, policy) -> {
                assertNull(policy); assertEquals("DENY", request.getAttribute("apiKey.audit.decision"));
                assertEquals(77L, request.getAttribute("apiKey.audit.verifiedPrincipalAccountId"));
                request.setAttribute("apiKey.audit.admitted", true); records.incrementAndGet();
            });
            ApiRequest request = new ApiRequest(null, null);
            ClientVisibleException denied = assertThrows(ClientVisibleException.class, () -> auth.handle(request));
            assertEquals(403, denied.getStatus()); assertEquals(code, denied.getCode()); assertEquals(1, records.get());
            assertNull(request.getAttribute("apiKey.audit.authenticationFailed"));
            assertThrows(ClientVisibleException.class, () -> auth.handle(request)); assertEquals(1, records.get());
        }
    }
    @Test public void earlyDenialBecomesAuditUnavailableOnlyWhenDurableAdmissionFails() {
        ApiAuthenticator auth = failing("OwnerPermissionDenied");
        auth.auditSinks = List.of((request, policy) -> { throw new IllegalStateException("private database value"); });
        ClientVisibleException failure = assertThrows(ClientVisibleException.class, () -> auth.handle(new ApiRequest(null, null)));
        assertEquals(503, failure.getStatus()); assertEquals("AuditUnavailable", failure.getCode()); assertNull(failure.getCause());
    }
    @Test public void unverifiedFailureProofRecordsAnonymousDenialNeverClaimsAKeyOrOwner() {
        ApiAuthenticator auth = new ApiAuthenticator(){
            @Override protected void authenticate(ApiRequest request){throw new ClientVisibleException(403,"DelegatedAuditAgentRequired");}
        };
        AtomicInteger records = new AtomicInteger();
        auth.auditSinks = List.of(new ApiKeyAuditSink(){
            public void recordDecision(ApiRequest request,Policy policy){fail("Unverified report is not a Key decision");}
            public void recordAuthenticationDenied(ApiRequest request,String code){
                assertEquals("DelegatedAuditAgentRequired",code);assertNull(ApiKeyCredentialContext.get(request));
                assertNull(request.getAttribute("apiKey.audit.keyId"));assertNull(request.getAttribute("apiKey.audit.verifiedPrincipalAccountId"));
                request.setAttribute("apiKey.audit.admitted",true);records.incrementAndGet();
            }
        });
        ApiRequest request = new ApiRequest(null,null);request.setType("apiKeyDelegationFailure");
        assertEquals(403,assertThrows(ClientVisibleException.class,()->auth.handle(request)).getStatus());
        assertThrows(ClientVisibleException.class,()->auth.handle(request));assertEquals(1,records.get());
        assertNull(ApiContext.getContext().getPolicy());
    }
    private static ApiAuthenticator failing(String code) {
        return new ApiAuthenticator() {
            @Override protected void authenticate(ApiRequest request) {
                ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(12, 77, "apiKey", 0, null));
                throw new ClientVisibleException(403, code);
            }
        };
    }
}
