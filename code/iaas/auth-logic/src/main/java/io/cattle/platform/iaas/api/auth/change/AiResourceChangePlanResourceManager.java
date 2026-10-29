package io.cattle.platform.iaas.api.auth.change;

import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import jakarta.inject.Inject;
import java.util.Map;

public class AiResourceChangePlanResourceManager extends AbstractNoOpResourceManager {
    public static final String TYPE = "aiResourceChangePlan";
    @Inject AiResourceChangePlanService service;
    @Override public String[] getTypes() { return new String[] { TYPE }; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { AiResourceChangePlan.class }; }
    @Override protected Object createInternal(String type, ApiRequest request) { return resource(service.preview(request)); }
    @Override protected Object getByIdInternal(String type, String id, ListOptions options) {
        return resource(service.get(id, ApiContext.getContext().getApiRequest()));
    }
    @Override protected Object listInternal(SchemaFactory factory, String type, Map<Object, Object> criteria, ListOptions options) {
        return service.pending(ApiContext.getContext().getApiRequest()).stream().map(this::resource).toList();
    }
    @Override protected Object resourceActionInternal(Object object, ApiRequest request) {
        Map<String, Object> result = service.action(request.getId(), request);
        if ("apply".equals(request.getAction()) && "accepted".equals(result.get("status"))) request.setResponseCode(202);
        return resource(result);
    }
    private ResourceImpl resource(Map<String, Object> fields) {
        ResourceImpl resource = new ResourceImpl((String) fields.get("id"), TYPE, fields);
        for (String action : new String[] { "confirm", "apply", "cancel" })
            resource.getActions().put(action, ApiContext.getUrlBuilder().actionLink(resource, action));
        return resource;
    }
}
