package io.cattle.platform.iaas.api.port;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

/** Normal Instance-create port parser and live preflight for an already authorized request; no create dispatch. */
public interface InstanceCreatePortValidation {
    void validateCreatePorts(ApiRequest authorizedRequest);
}
