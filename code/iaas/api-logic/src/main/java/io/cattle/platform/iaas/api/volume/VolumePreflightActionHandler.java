package io.cattle.platform.iaas.api.volume;

import io.cattle.platform.api.action.ActionHandler;
import io.cattle.platform.core.addon.VolumePreflightInput;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import jakarta.inject.Inject;

public class VolumePreflightActionHandler implements ActionHandler {

    @Inject
    VolumePreflightService service;

    @Inject
    JsonMapper jsonMapper;

    @Override
    public Object perform(String name, Object obj, ApiRequest request) {
        if (!(obj instanceof Account)) {
            return null;
        }
        VolumePreflightInput input = jsonMapper.convertValue(request.getRequestObject(), VolumePreflightInput.class);
        return service.check((Account) obj, input);
    }

    @Override
    public String getName() {
        return "account." + VolumePreflightService.ACTION;
    }
}
