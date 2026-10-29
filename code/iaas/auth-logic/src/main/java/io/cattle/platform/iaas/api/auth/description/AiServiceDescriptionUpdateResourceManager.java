package io.cattle.platform.iaas.api.auth.description;

import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;
import java.util.Map;

public class AiServiceDescriptionUpdateResourceManager extends AbstractNoOpResourceManager {
    public static final String TYPE = "aiServiceDescriptionUpdate";
    @Inject AiServiceDescriptionUpdateService service;
    @Override public String[] getTypes() { return new String[] { TYPE }; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { AiServiceDescriptionUpdate.class }; }
    @Override protected Object createInternal(String type, ApiRequest request) {
        Map<String, Object> result = service.update(request);
        request.setResponseCode(200);
        return new ResourceImpl((String) result.get("id"), TYPE, result);
    }
}
