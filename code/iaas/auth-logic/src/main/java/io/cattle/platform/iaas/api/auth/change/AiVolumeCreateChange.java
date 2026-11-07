package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Volume;
import io.cattle.platform.object.util.DataAccessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Only a new, unallocated native local Volume record; no Host or physical storage mutation. */
final class AiVolumeCreateChange {
    private AiVolumeCreateChange() { }
    static boolean supports(AiResourceChangeDescriptor descriptor) {
        return "volume".equals(descriptor.resourceType()) && descriptor.createsResource();
    }
    static Map<String, Object> parameters(Object value) {
        if (!(value instanceof Map<?, ?> input) || !Set.of("name", "description").containsAll(input.keySet())
                || !(input.get("name") instanceof String name) || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}")) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>(); result.put("name", name);
        if (input.containsKey("description")) {
            Object description = input.get("description");
            if (description != null && (!(description instanceof String text) || text.length() > 1024 || text.indexOf('\0') >= 0)) throw invalid();
            result.put("description", description);
        }
        return result;
    }
    static Map<String, Object> body(Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>(parameters(parameters));
        body.put("type", "volume"); body.put("driver", "local"); body.put("driverOpts", new LinkedHashMap<>());
        // isHostPath/isNative are native readonly fields with false defaults, never writable input.
        return body;
    }
    static boolean complete(Volume volume, Map<String, Object> parameters, long id, String uuid, long account) {
        if (id <= 0 || account <= 0 || uuid == null || uuid.isBlank() || !Objects.equals(volume.getId(), id)
                || !Objects.equals(volume.getUuid(), uuid) || !Objects.equals(volume.getAccountId(), account)
                || volume.getRemoved() != null || !"detached".equals(volume.getState()) || !"inactive".equals(volume.getAllocationState())
                || volume.getStorageDriverId() != null || volume.getHostId() != null || volume.getImageId() != null || volume.getInstanceId() != null
                || !Integer.valueOf(-1).equals(volume.getDeviceNumber()) || volume.getSizeMb() != null
                || !Objects.equals(parameters.get("name"), volume.getName()) || !Objects.equals(parameters.get("description"), volume.getDescription())
                || !Objects.equals(volume.getExternalId(), parameters.get("name")) || !Objects.equals(volume.getUri(), "local:///" + parameters.get("name"))
                || !"local".equals(DataAccessor.fieldString(volume, "driver"))) return false;
        for (String field : Set.of("isHostPath", "isNative")) {
            Object flag = DataAccessor.field(volume, field, Object.class);
            if (flag != null && !Boolean.FALSE.equals(flag)) return false;
        }
        Object options = DataAccessor.field(volume, "driverOpts", Object.class);
        return options == null || options instanceof Map<?, ?> map && map.isEmpty();
    }
    private static io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid() {
        return AiResourceChangeDescriptor.error("ResourceChangeInputInvalid");
    }
}
