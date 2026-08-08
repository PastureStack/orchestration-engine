package io.cattle.platform.iaas.api.filter.instance;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.util.PortSpec;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.core.addon.PortPreflightInput;
import io.cattle.platform.iaas.api.port.PortPreflightInputs;
import io.cattle.platform.iaas.api.port.PortPreflightService;
import io.cattle.platform.iaas.api.filter.common.AbstractDefaultResourceManagerFilter;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.exception.ValidationErrorException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

public class InstancePortsValidationFilter extends AbstractDefaultResourceManagerFilter {

    @Inject
    PortPreflightService portPreflightService;

    @Inject
    ObjectManager objectManager;

    @Override
    public String[] getTypes() {
        return new String[] { "container", "virtualMachine" };
    }

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[] { Instance.class };
    }

    @Override
    public Object create(String type, ApiRequest request, ResourceManager next) {
        List<?> ports = DataUtils.getFieldFromRequest(request, InstanceConstants.FIELD_PORTS, List.class);
        if (ports != null) {
            for (Object port : ports) {
                if (port == null) {
                    throw new ValidationErrorException(ValidationErrorCodes.MISSING_REQUIRED, InstanceConstants.FIELD_PORTS);
                }

                /* This will parse the PortSpec and throw an error */
                new PortSpec(port.toString());
            }
            assertPortsAvailable(request, ports, null);
        }

        return super.create(type, request, next);
    }

    @Override
    public Object update(String type, String id, ApiRequest request, ResourceManager next) {
        Instance instance = objectManager.loadResource(Instance.class, id);
        Map<String, Object> data = CollectionUtils.toMap(request.getRequestObject());
        boolean portsChanged = data.containsKey(InstanceConstants.FIELD_PORTS);
        boolean networkChanged = data.containsKey(DockerInstanceConstants.FIELD_NETWORK_MODE);
        boolean hostChanged = data.containsKey(InstanceConstants.FIELD_REQUESTED_HOST_ID);
        if (instance != null && (portsChanged || networkChanged || hostChanged)) {
            List<?> ports = portsChanged
                    ? DataUtils.getFieldFromRequest(request, InstanceConstants.FIELD_PORTS, List.class)
                    : DataAccessor.fieldStringList(instance, InstanceConstants.FIELD_PORTS);
            assertPortsAvailable(request, ports, instance);
        }
        return super.update(type, id, request, next);
    }

    private void assertPortsAvailable(ApiRequest request, List<?> ports, Instance instance) {
        Map<String, Object> data = CollectionUtils.toMap(request.getRequestObject());
        String networkMode = DataUtils.getFieldFromRequest(request,
                DockerInstanceConstants.FIELD_NETWORK_MODE, String.class);
        Long requestedHostId = DataUtils.getFieldFromRequest(request,
                InstanceConstants.FIELD_REQUESTED_HOST_ID, Long.class);
        if (instance != null) {
            if (!data.containsKey(DockerInstanceConstants.FIELD_NETWORK_MODE)) {
                networkMode = DataAccessor.fieldString(instance, DockerInstanceConstants.FIELD_NETWORK_MODE);
            }
            if (!data.containsKey(InstanceConstants.FIELD_REQUESTED_HOST_ID)) {
                requestedHostId = DataAccessor.fieldLong(instance, InstanceConstants.FIELD_REQUESTED_HOST_ID);
            }
        }

        PortPreflightInput input = PortPreflightInputs.fromInstance(ports, networkMode,
                requestedHostId, instance == null ? null : instance.getId());
        if (input.getPorts().isEmpty()) {
            return;
        }
        Account account = objectManager.loadResource(Account.class, ApiUtils.getPolicy().getAccountId());
        portPreflightService.assertAvailable(account, input);
    }

}
