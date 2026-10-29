package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyCredentialContextTest {
    @Before public void context() { ApiContext.newContext().setIdFormatter(new IdentityFormatter()); }
    @After public void clear() { ApiContext.remove(); }

    @Test public void generalPurposeUsesOnlyTheVerifiedCredential() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ApiRequest request = request(response);
        request.setRequestObject(Map.of("purpose", "ai-bridge", "revision", 999, "secret", "never-return"));
        ApiKeyCredentialContext verified = key(ApiKeyPolicy.Purpose.GENERAL, 7);
        ApiKeyCredentialContext.attach(request, verified);
        assertSame(verified, ApiKeyCredentialContext.get(request));
        verify(response).setHeader("X-PastureStack-Key-Purpose", "general");
        verify(response).setHeader("X-PastureStack-Key-Revision", "7");
        verifyNoMoreInteractions(response);
    }

    @Test public void dedicatedAiKeyAdvertisesItsVerifiedPurposeAndRevision() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ApiKeyCredentialContext.attach(request(response), key(ApiKeyPolicy.Purpose.AI_BRIDGE, 12));
        verify(response).setHeader("X-PastureStack-Key-Purpose", "ai-bridge");
        verify(response).setHeader("X-PastureStack-Key-Revision", "12");
        verifyNoMoreInteractions(response);
    }

    @Test public void legacyKeyIsExplicitlyGeneral() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ApiKeyCredentialContext.attach(request(response), new ApiKeyCredentialContext(10, 42, "apiKey", 0, null));
        verify(response).setHeader("X-PastureStack-Key-Purpose", "general");
        verify(response).setHeader("X-PastureStack-Key-Revision", "0");
        verifyNoMoreInteractions(response);
    }

    @Test public void absentServletResponseSafelyPreservesServerContext() {
        ApiKeyCredentialContext verified = key(ApiKeyPolicy.Purpose.AI_BRIDGE, 3);
        for (ApiRequest request : List.of(new ApiRequest(null, null), request(null))) {
            ApiKeyCredentialContext.attach(request, verified);
            assertSame(verified, ApiKeyCredentialContext.get(request));
            assertEquals(3L, request.getAttribute("apiKey.audit.policyRevision"));
        }
    }

    private ApiRequest request(HttpServletResponse response) {
        ApiRequest request = new ApiRequest(new ApiServletContext(mock(HttpServletRequest.class), response, null), null);
        request.setMethod("GET"); request.setType("service"); return request;
    }
    private ApiKeyCredentialContext key(ApiKeyPolicy.Purpose purpose, long revision) {
        return new ApiKeyCredentialContext(10, 42, "apiKey", revision,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), purpose));
    }
}
