package io.cattle.platform.iaas.api.auth.apikey;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.cattle.platform.core.constants.CredentialConstants;

/** Secret-free identity of the credential that was actually verified. */
public record ApiKeyCredentialContext(long credentialId, long principalAccountId,
                                      String kind, long revision, ApiKeyPolicy policy) {
    private static final String ATTRIBUTE = ApiKeyCredentialContext.class.getName();

    public static ApiKeyCredentialContext get(ApiRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof ApiKeyCredentialContext ? (ApiKeyCredentialContext) value : null;
    }

    public static void attach(ApiRequest request, ApiKeyCredentialContext context) {
        request.setAttribute(ATTRIBUTE, context);
        Object id = ApiContext.getContext().getIdFormatter().formatId(CredentialConstants.TYPE, context.credentialId());
        request.setAttribute("apiKey.audit.keyId", String.valueOf(id));
        request.setAttribute("apiKey.audit.policyRevision", context.revision());
        // Only attached after the credential secret was verified. These values
        // let the completion sink record expiry/project-selection denial before
        // a scoped Policy exists; they never come from headers or request data.
        request.setAttribute("apiKey.audit.verifiedPrincipalAccountId", context.principalAccountId());
        request.setAttribute("apiKey.audit.verifiedAccountId", context.principalAccountId());
        request.setAttribute("apiKey.audit.operation", ApiKeyOperations.of(request).id());
        request.setAttribute("apiKey.audit.decision", "DENY");
        request.setAttribute("apiKey.audit.reason", "AuthenticationPending");
    }

    public boolean restricted() {
        return policy != null && (policy.getMode() != ApiKeyPolicy.Mode.FULL || policy.getExpiresAt() != null);
    }
}
