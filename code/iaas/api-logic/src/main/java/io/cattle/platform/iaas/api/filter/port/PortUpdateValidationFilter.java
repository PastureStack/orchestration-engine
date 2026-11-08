package io.cattle.platform.iaas.api.filter.port;

import io.cattle.platform.iaas.api.filter.common.AbstractDefaultResourceManagerFilter;
import io.cattle.platform.iaas.api.port.PortUpdateService;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import jakarta.inject.Inject;
import java.util.Map;

/** Native UI and typed callers use this same validation/save boundary. */
public class PortUpdateValidationFilter extends AbstractDefaultResourceManagerFilter {
    @Inject PortUpdateService updates;
    @Inject ObjectManager objects;
    @Override public String[] getTypes() { return new String[] { "port" }; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { Port.class }; }

    @Override public Object update(String type, String id, ApiRequest request, ResourceManager next) {
        Map<String, Object> body = CollectionUtils.toMap(request.getRequestObject());
        if (!body.containsKey("publicPort")) return super.update(type, id, request, next);
        Port port = objects.loadResource(Port.class, id);
        if (port == null) throw new ClientVisibleException(404, "NotFound");
        return updates.update(request, port.getId(), body.get("publicPort"), () -> super.update(type, id, request, next));
    }
}
