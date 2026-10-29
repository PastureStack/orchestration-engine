package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Set;

/** Server-verified purpose, independent of user-controlled channel headers. */
public final class ApiKeyAiBridgeGuard {
    public static final String EXPECTED_REVISION_HEADER = "X-PastureStack-Key-Expected-Revision";
    private ApiKeyAiBridgeGuard() { }

    public static void check(ApiRequest request, ApiKeyPolicy policy) {
        if (!isBridge(policy)) return;
        requireEnabled(policy);
        if (!allows(request)) denied("AiBridgeRouteDenied");
        String expected = request.getServletContext() == null ? null
                : request.getServletContext().getRequest().getHeader(EXPECTED_REVISION_HEADER);
        // Only discovery can run before a bridge pins the first verified revision.
        boolean bootstrap = "GET".equals(request.getMethod()) && "project".equals(request.getType())
                && request.getId() == null && request.getAction() == null && request.getLink() == null;
        if (expected == null && bootstrap) return;
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null || expected == null || !expected.equals(Long.toString(key.revision())))
            denied("ApiKeyPolicyChanged");
    }

    public static void requireEnabled(ApiKeyPolicy policy) {
        if (isBridge(policy) && !Boolean.TRUE.equals(ArchaiusUtil.getBooleanProperty("api.ai.bridge.enabled").get()))
            denied("AiBridgeDisabled");
    }

    private static boolean isBridge(ApiKeyPolicy policy) {
        return policy != null && policy.getPurpose() == ApiKeyPolicy.Purpose.AI_BRIDGE;
    }

    public static boolean allows(ApiRequest request) {
        if (request == null || request.getLink() != null) return false;
        String type = request.getType();
        if ("GET".equals(request.getMethod()) && request.getAction() == null) {
            return Set.of("project", "stack", "service").contains(type == null ? "" : type)
                    || "aiServiceUpgradePlan".equals(type) && request.getId() != null;
        }
        if (!"POST".equals(request.getMethod())) return false;
        if ("aiServiceUpgradePlan".equals(type))
            return request.getId() == null && request.getAction() == null
                    || request.getId() != null && "apply".equals(request.getAction());
        return request.getId() == null && request.getAction() == null
                && Set.of("aiToolCallEvent", "aiServiceDescriptionUpdate").contains(type == null ? "" : type);
    }

    private static void denied(String code) { throw new ClientVisibleException(403, code, code, null); }
}
