package io.cattle.platform.iaas.api.auth.apikey;

import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;

public class ApiKeyDelegationCompletionResourceManager extends AbstractNoOpResourceManager {
    @Inject ApiKeyDelegationService delegations;
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[]{ApiKeyDelegationCompletion.class}; }
    @Override protected Object createInternal(String type, ApiRequest request) {
        java.util.Map<String, Object> result = delegations.recordCompletion(request);
        // This acknowledges durable evidence; it does not create a credential.
        // The generic CREATE handler initially chooses 201, whereas the host
        // receipt contract deliberately requires 200 with accepted=true.
        request.setResponseCode(200);
        return new ResourceImpl("completion", "apiKeyDelegationCompletion", result);
    }
}
