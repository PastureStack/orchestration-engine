package io.cattle.platform.iaas.api.auth.ai.audit;

import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;

public class AiAuditQueryResourceManager extends AbstractNoOpResourceManager {
    @Inject AiAuditQueryService service;
    @Override public String[] getTypes() { return new String[] { "aiAuditQuery" }; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { AiAuditQuery.class }; }
    @Override protected Object createInternal(String type, ApiRequest request) {
        var result = service.read(request);
        return new ResourceImpl((String) result.get("id"), "aiAuditQuery", result);
    }
}
