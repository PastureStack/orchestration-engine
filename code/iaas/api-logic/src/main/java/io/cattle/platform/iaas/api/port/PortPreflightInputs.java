package io.cattle.platform.iaas.api.port;

import io.cattle.platform.core.addon.PortPreflightInput;
import io.cattle.platform.core.addon.PortPreflightPort;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.util.PortSpec;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.util.type.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class PortPreflightInputs {

    private PortPreflightInputs() {
    }

    public static PortPreflightInput fromInstance(List<?> portSpecs, String networkMode,
            Long requestedHostId, Long instanceId) {
        PortPreflightInput input = new PortPreflightInput();
        input.setPorts(toPorts(portSpecs, networkMode));
        input.setNetworkMode(networkMode);
        input.setRequestedHostId(requestedHostId);
        input.setInstanceId(instanceId);
        input.setScale(Integer.valueOf(1));
        input.setRuntimeProbe(Boolean.TRUE);
        return input;
    }

    public static PortPreflightInput fromService(Service service, Object launchConfigValue,
            Integer scale, boolean startFirst) {
        Map<String, Object> launchConfig = CollectionUtils.toMap(launchConfigValue);
        if (launchConfig.isEmpty()) {
            launchConfig = CollectionUtils.toMap(DataAccessor.field(service,
                    ServiceConstants.FIELD_LAUNCH_CONFIG, Object.class));
        }

        PortPreflightInput input = new PortPreflightInput();
        String networkMode = stringValue(launchConfig.get(DockerInstanceConstants.FIELD_NETWORK_MODE));
        input.setPorts(toPorts(valueList(launchConfig.get(InstanceConstants.FIELD_PORTS)), networkMode));
        input.setNetworkMode(networkMode);
        input.setRequestedHostId(longValue(launchConfig.get(InstanceConstants.FIELD_REQUESTED_HOST_ID)));
        input.setServiceId(service.getId());
        input.setStackId(service.getStackId());
        input.setScale(scale == null ? Integer.valueOf(1) : scale);
        input.setBatchSize(Integer.valueOf(1));
        input.setGlobal(Boolean.valueOf(isGlobal(launchConfig)));
        input.setStartFirst(Boolean.valueOf(startFirst));
        input.setRuntimeProbe(Boolean.TRUE);
        return input;
    }

    public static PortPreflightInput fromService(Service service, Object launchConfigValue,
            List<?> secondaryLaunchConfigs, Integer scale, boolean startFirst) {
        PortPreflightInput input = fromService(service, launchConfigValue, scale, startFirst);
        List<PortPreflightPort> ports = new ArrayList<PortPreflightPort>(input.getPorts());
        if (secondaryLaunchConfigs != null) {
            for (Object value : secondaryLaunchConfigs) {
                Map<String, Object> launchConfig = CollectionUtils.toMap(value);
                String networkMode = stringValue(launchConfig.get(DockerInstanceConstants.FIELD_NETWORK_MODE));
                ports.addAll(toPorts(valueList(launchConfig.get(InstanceConstants.FIELD_PORTS)), networkMode));
            }
        }
        input.setPorts(ports);
        return input;
    }

    public static List<PortPreflightPort> toPorts(List<?> portSpecs) {
        return toPorts(portSpecs, null);
    }

    public static List<PortPreflightPort> toPorts(List<?> portSpecs, String networkMode) {
        if (portSpecs == null) {
            return Collections.emptyList();
        }
        List<PortPreflightPort> result = new ArrayList<PortPreflightPort>();
        for (Object value : portSpecs) {
            if (value == null) {
                continue;
            }
            PortSpec spec = new PortSpec(value.toString());
            PortPreflightPort port = new PortPreflightPort();
            port.setBindAddress(spec.getIpAddress());
            port.setPublicPort(spec.getPublicPort());
            port.setPrivatePort(Integer.valueOf(spec.getPrivatePort()));
            port.setProtocol(spec.getProtocol());
            port.setNetworkMode(networkMode);
            result.add(port);
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
        return value == null ? "managed" : value.toString();
    }
}
