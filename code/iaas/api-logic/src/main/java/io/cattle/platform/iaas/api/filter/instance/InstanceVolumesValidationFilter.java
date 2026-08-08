package io.cattle.platform.iaas.api.filter.instance;

import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.addon.VolumePreflightInput;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.iaas.api.filter.common.AbstractDefaultResourceManagerFilter;
import io.cattle.platform.iaas.api.volume.VolumePreflightInputs;
import io.cattle.platform.iaas.api.volume.VolumePreflightService;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

public class InstanceVolumesValidationFilter extends AbstractDefaultResourceManagerFilter {

    @Inject
    VolumePreflightService volumePreflightService;

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
        List<?> dataVolumes = DataUtils.getFieldFromRequest(request,
                InstanceConstants.FIELD_DATA_VOLUMES, List.class);
        if (dataVolumes != null && !dataVolumes.isEmpty()) {
            assertVolumesAvailable(request, dataVolumes, null);
        }
        return super.create(type, request, next);
    }

    @Override
    public Object update(String type, String id, ApiRequest request, ResourceManager next) {
        Instance instance = objectManager.loadResource(Instance.class, id);
        Map<String, Object> data = CollectionUtils.toMap(request.getRequestObject());
        boolean volumesChanged = data.containsKey(InstanceConstants.FIELD_DATA_VOLUMES);
        boolean driverChanged = data.containsKey(InstanceConstants.FIELD_VOLUME_DRIVER);
        boolean hostChanged = data.containsKey(InstanceConstants.FIELD_REQUESTED_HOST_ID);
        if (instance != null && (volumesChanged || driverChanged || hostChanged)) {
            List<?> dataVolumes = volumesChanged
                    ? DataUtils.getFieldFromRequest(request, InstanceConstants.FIELD_DATA_VOLUMES, List.class)
                    : DataAccessor.fieldStringList(instance, InstanceConstants.FIELD_DATA_VOLUMES);
            if (dataVolumes != null && !dataVolumes.isEmpty()) {
                assertVolumesAvailable(request, dataVolumes, instance);
            }
        }
        return super.update(type, id, request, next);
    }

    private void assertVolumesAvailable(ApiRequest request, List<?> dataVolumes, Instance instance) {
        Map<String, Object> data = CollectionUtils.toMap(request.getRequestObject());
        String driver = DataUtils.getFieldFromRequest(request,
                InstanceConstants.FIELD_VOLUME_DRIVER, String.class);
        Long requestedHostId = DataUtils.getFieldFromRequest(request,
                InstanceConstants.FIELD_REQUESTED_HOST_ID, Long.class);
        if (instance != null) {
            if (!data.containsKey(InstanceConstants.FIELD_VOLUME_DRIVER)) {
                driver = DataAccessor.fieldString(instance, InstanceConstants.FIELD_VOLUME_DRIVER);
            }
            if (!data.containsKey(InstanceConstants.FIELD_REQUESTED_HOST_ID)) {
                requestedHostId = DataAccessor.fieldLong(instance,
                        InstanceConstants.FIELD_REQUESTED_HOST_ID);
            }
        }

        VolumePreflightInput input = VolumePreflightInputs.fromInstance(dataVolumes, driver,
                requestedHostId, instance == null ? null : instance.getId());
        Account account = objectManager.loadResource(Account.class, ApiUtils.getPolicy().getAccountId());
        volumePreflightService.assertAvailable(account, input);
    }
}
