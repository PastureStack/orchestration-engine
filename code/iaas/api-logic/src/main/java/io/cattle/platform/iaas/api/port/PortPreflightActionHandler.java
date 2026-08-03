package io.cattle.platform.iaas.api.port;

import io.cattle.platform.api.action.ActionHandler;
import io.cattle.platform.core.addon.PortPreflightInput;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import jakarta.inject.Inject;

public class PortPreflightActionHandler implements ActionHandler {

    @Inject
    PortPreflightService service;

    @Inject
    JsonMapper jsonMapper;

    @Override
    public Object perform(String name, Object obj, ApiRequest request) {
        if (!(obj instanceof Account)) {
            return null;
        }
        PortPreflightInput input = jsonMapper.convertValue(request.getRequestObject(), PortPreflightInput.class);
        return service.check((Account) obj, input);
    }

    @Override
    public String getName() {
        return "account." + PortPreflightService.ACTION;
    }
}
