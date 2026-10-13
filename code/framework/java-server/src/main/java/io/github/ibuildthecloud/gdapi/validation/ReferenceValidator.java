package io.github.ibuildthecloud.gdapi.validation;

import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

public interface ReferenceValidator {

    Object getById(String type, String id);

    Resource getResourceId(String type, String id);

    /** Availability of this exact POST action, separate from ordinary input references. */
    default Resource getActionResourceId(ApiRequest request) {
        return getResourceId(request.getType(), request.getId());
    }

    Object getByField(String type, String fieldName, Object value, String id);

}
