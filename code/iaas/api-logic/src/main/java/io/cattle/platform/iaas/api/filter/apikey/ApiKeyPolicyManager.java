package io.cattle.platform.iaas.api.filter.apikey;

import io.cattle.platform.core.model.Credential;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;

import java.util.Map;

/** Keeps auth-owned policy validation out of the lower-level API module. */
public interface ApiKeyPolicyManager {
    void prepareCreate(Credential credential, ApiRequest request);

    Object update(String type, String id, ApiRequest request, ResourceManager next);

    Map<String, Object> output(Credential credential);
}
