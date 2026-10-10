package io.cattle.platform.iaas.api.filter.apikey;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Date;
import java.util.Map;

/** Trusted signing boundary; null retains the non-Key token contract. */
public interface ApiKeyDelegationTokenProvider {
    String token(ApiRequest request, Map<String, Object> payload, Date expiration);
}
