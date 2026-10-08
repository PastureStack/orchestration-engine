package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import java.util.List;

/** Authentication-boundary admission, using only server-generated identity and codes. */
public final class ApiKeyAuthenticationAudit {
    private ApiKeyAuthenticationAudit() { }

    public static void deny(ApiRequest request, List<ApiKeyAuditSink> sinks, String reason) {
        if (Boolean.TRUE.equals(request.getAttribute("apiKey.audit.admitted"))) return;
        String code = reason != null && reason.matches("[A-Za-z][A-Za-z0-9_]{0,63}") ? reason : "AuthenticationDenied";
        boolean verified = ApiKeyCredentialContext.get(request) != null;
        request.setAttribute("apiKey.audit.decision", "DENY");
        request.setAttribute("apiKey.audit.reason", code);
        if (!verified) request.setAttribute("apiKey.audit.authenticationFailed", Boolean.TRUE);
        try {
            if (sinks == null || sinks.isEmpty()) throw new IllegalStateException("Audit sink unavailable");
            for (ApiKeyAuditSink sink : sinks) {
                if (verified) sink.recordDecision(request, null);
                else sink.recordAuthenticationDenied(request, code);
            }
        } catch (RuntimeException unavailable) {
            throw new ClientVisibleException(ResponseCodes.SERVICE_UNAVAILABLE, "AuditUnavailable");
        }
    }
}
