package io.cattle.platform.iaas.api.auth.ai.catalog;

import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;

public class AiCatalogLaunchOptionsResourceManager extends AbstractNoOpResourceManager {
    @Inject AiCatalogLaunchOptionsService service;
    @Override public String[] getTypes() { return new String[] { AiCatalogLaunchInput.OPTIONS }; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { AiCatalogStackLaunchOptions.class }; }
    @Override protected Object createInternal(String type, ApiRequest request) {
        var result = service.read(request);
        return new ResourceImpl((String) result.get("id"), AiCatalogLaunchInput.OPTIONS, result);
    }
}
