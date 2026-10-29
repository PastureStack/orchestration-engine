package io.cattle.platform.api.auth;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Map;

/** Proof-bearing Catalog transforms must authenticate before the early Scripts owner executes. */
public interface CatalogLaunchTransformAuthorization {
    String HEADER = "X-PastureStack-Catalog-Launch-Delegation";
    void authorize(ApiRequest request, Map<String, Object> inspect);
}
