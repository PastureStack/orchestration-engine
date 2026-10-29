package io.cattle.platform.iaas.api.auth.ai.view;

import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;
import java.util.Map;

public class AiResourceViewResourceManager extends AbstractNoOpResourceManager {
    @Inject AiResourceViewService service;
    @Override public String[] getTypes() { return new String[] { "aiResourceView" }; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { AiResourceView.class }; }
    @Override protected Object createInternal(String type, ApiRequest request) {
        Map<String, Object> result = service.read(request);
        return new ResourceImpl((String) result.get("id"), "aiResourceView", result);
    }
}
