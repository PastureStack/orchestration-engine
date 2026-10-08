package io.github.ibuildthecloud.gdapi.request.handler;

import static org.junit.Assert.*;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.List;
import org.junit.Test;

public class ApiKeyExceptionPrivacyTest {
    @Test public void keyAndAnonymousErrorsNeverSerializeRequestOrPrivateCauseButKeepStatus() throws Exception {
        for (String variant : List.of("key", "anonymous", "apiKeyDelegationCompletion", "apiKeyDelegation")) {
            ApiRequest request = privateRequest(variant);
            ExceptionHandler handler = new ExceptionHandler();
            assertTrue(handler.handleException(request, privateFailure()));
            assertEquals(500, request.getResponseCode()); assertNotNull(request.getResponseObject());
        }
    }
    @Test public void rethrowModePreservesOriginalFailureWithoutLoggingSensitiveDetails() {
        ExceptionHandler handler = new ExceptionHandler(); handler.setThrowUnknownErrors(true);
        RuntimeException failure = privateFailure();
        assertSame(failure, assertThrows(RuntimeException.class, () -> handler.handleException(privateRequest("anonymous"), failure)));
    }
    private static ApiRequest privateRequest(String variant) {
        ApiRequest request = new ApiRequest(null, null) {
            @Override public String toString() { throw new AssertionError("Private request URL must not be serialized"); }
        };
        if ("key".equals(variant)) request.setAttribute("apiKey.audit.keyId", "1a12");
        else if ("anonymous".equals(variant)) request.setAttribute("apiKey.audit.authenticationFailed", true);
        else request.setType(variant);
        request.setRequestUrl("/v2-beta/apiKeyDelegation?token=private-JWT"); return request;
    }
    private static RuntimeException privateFailure() {
        return new RuntimeException() {
            @Override public String toString() { throw new AssertionError("Private cause must not be serialized"); }
            @Override public StackTraceElement[] getStackTrace() { throw new AssertionError("Private cause must not be logged"); }
        };
    }
}
