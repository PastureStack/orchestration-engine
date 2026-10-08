package io.cattle.platform.iaas.api.auth.apikey;

import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;

public class ApiKeyPolicyPreviewResourceManager extends AbstractNoOpResourceManager {
    public static final String TYPE = "apiKeyPolicyPreview";
    @Inject ApiKeyPolicyManagementService policyManagement;
    @Inject ResourceManagerLocator locator;

    @Override public String[] getTypes() { return new String[] {TYPE}; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] {ApiKeyPolicyPreview.class}; }

    @Override
    protected Object createInternal(String type, ApiRequest request) {
        return new ResourceImpl("preview", TYPE,
                policyManagement.preview(request, locator.getResourceManagerByType("credential")));
    }
}
