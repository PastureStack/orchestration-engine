package io.cattle.platform.iaas.api.volume;

import io.cattle.platform.core.addon.VolumePreflightInput;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.util.type.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class VolumePreflightInputs {

    private VolumePreflightInputs() {
    }

    public static VolumePreflightInput fromInstance(List<?> specs, String driver,
            Long requestedHostId, Long instanceId) {
        VolumePreflightInput input = new VolumePreflightInput();
        input.setDataVolumes(stringList(specs));
        input.setVolumeDriver(driver);
        input.setRequestedHostId(requestedHostId);
        input.setInstanceId(instanceId);
        input.setScale(Integer.valueOf(1));
        input.setBatchSize(Integer.valueOf(1));
        input.setGlobal(Boolean.FALSE);
        input.setStartFirst(Boolean.FALSE);
        return input;
    }

    public static VolumePreflightInput fromService(Service service, Object launchConfigValue,
            Integer scale, boolean startFirst) {
        Map<String, Object> launchConfig = CollectionUtils.toMap(launchConfigValue);
        if (launchConfig.isEmpty()) {
            launchConfig = CollectionUtils.toMap(DataAccessor.field(service,
                    ServiceConstants.FIELD_LAUNCH_CONFIG, Object.class));
        }

        VolumePreflightInput input = fromInstance(
                valueList(launchConfig.get(InstanceConstants.FIELD_DATA_VOLUMES)),
                stringValue(launchConfig.get(InstanceConstants.FIELD_VOLUME_DRIVER)),
                longValue(launchConfig.get(InstanceConstants.FIELD_REQUESTED_HOST_ID)), null);
        input.setServiceId(service.getId());
        input.setStackId(service.getStackId());
        input.setScale(scale == null ? Integer.valueOf(1) : scale);
        input.setStartFirst(Boolean.valueOf(startFirst));
        input.setGlobal(Boolean.valueOf(isGlobal(launchConfig)));
        return input;
    }

    public static List<String> stringList(List<?> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (Object value : values) {
            String normalized = value == null ? "" : value.toString().trim();
            if (normalized.length() > 0) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static boolean isGlobal(Map<String, Object> launchConfig) {
        Map<String, Object> labels = CollectionUtils.toMap(launchConfig.get(InstanceConstants.FIELD_LABELS));
        Object value = labels.get(ServiceConstants.LABEL_SERVICE_GLOBAL);
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static List<?> valueList(Object value) {
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    private static Long longValue(Object value) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if (value == null || value.toString().trim().length() == 0) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
