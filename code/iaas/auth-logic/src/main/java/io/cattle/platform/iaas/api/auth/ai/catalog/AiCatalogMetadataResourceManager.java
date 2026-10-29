package io.cattle.platform.iaas.api.auth.ai.catalog;

import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;
import java.util.Map;

public class AiCatalogMetadataResourceManager extends AbstractNoOpResourceManager {
    @Inject AiCatalogMetadataService service;
    @Override public String[] getTypes() { return new String[] { AiCatalogMetadataQuery.TYPE }; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { AiCatalogMetadataView.class }; }
    @Override protected Object createInternal(String type, ApiRequest request) {
        Map<String, Object> result = service.read(request);
        return new ResourceImpl((String) result.get("id"), AiCatalogMetadataQuery.TYPE, result);
    }
}
