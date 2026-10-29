package io.cattle.platform.iaas.api.auth.ai.catalog;

import io.cattle.platform.iaas.api.filter.common.AbstractDefaultResourceManagerFilter;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

/** Verify the actual normal-manager result, closing the interval after authentication's frozen-body check. */
public class AiCatalogLaunchResponseFilter extends AbstractDefaultResourceManagerFilter {
    @Inject AiCatalogLaunchDelegation delegation;
    @Override public String[] getTypes() { return new String[] { "stack", "service", "volumeTemplate", "secret" }; }
    @Override public Object create(String type, ApiRequest request, ResourceManager next) {
        Object result = next.create(type, request);
        delegation.managerCompleted(request, result);
        return result;
    }
    @Override public Object resourceAction(String type, ApiRequest request, ResourceManager next) {
        Object result = next.resourceAction(type, request);
        delegation.managerCompleted(request, result);
        return result;
    }
    @Override public Object getById(String type, String id, ListOptions options, ResourceManager next) {
        Object result = next.getById(type, id, options);
        delegation.validateResponse(ApiContext.getContext().getApiRequest(), result == null ? List.of() : List.of(result));
        return result;
    }
    @Override public Object list(String type, ApiRequest request, ResourceManager next) {
        Object result = next.list(type, request);
        if (result instanceof List<?> rows) delegation.validateResponse(request, rows);
        return result;
    }
    @Override public List<?> list(String type, Map<Object, Object> criteria, ListOptions options, ResourceManager next) {
        List<?> result = next.list(type, criteria, options);
        delegation.validateResponse(ApiContext.getContext().getApiRequest(), result);
        return result;
    }
}
