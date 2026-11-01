package io.cattle.platform.servicediscovery.api.service;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

/** Native create validation for an already authorized request; performs no resource mutation. */
public interface ServiceCreateValidation {
    /** Reuses the normal Service create port parser and live port preflight. */
    void validatePorts(ApiRequest authorizedRequest);

    /** Uses the same image normalization as the final native Service create filter. */
    String imageUuid(String image);
}
