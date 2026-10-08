package io.cattle.platform.iaas.api.auth.apikey;

import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;

public class ApiKeyDelegationCompletionResourceManager extends AbstractNoOpResourceManager {
    @Inject ApiKeyDelegationService delegations;
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[]{ApiKeyDelegationCompletion.class}; }
    @Override protected Object createInternal(String type, ApiRequest request) {
        return new ResourceImpl("completion", "apiKeyDelegationCompletion", delegations.recordCompletion(request));
    }
}
