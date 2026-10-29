package io.cattle.platform.iaas.api.auth.ai;

import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;
import java.util.Map;

public class AiToolCallEventResourceManager extends AbstractNoOpResourceManager {
    @Inject AiToolCallEventService service;
    @Override public String[] getTypes() { return new String[] { "aiToolCallEvent" }; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { AiToolCallEvent.class }; }
    @Override protected Object createInternal(String type, ApiRequest request) {
        Map<String, Object> result = service.report(request);
        request.setResponseCode(200);
        return new ResourceImpl((String) result.get("id"), "aiToolCallEvent", result);
    }
}
