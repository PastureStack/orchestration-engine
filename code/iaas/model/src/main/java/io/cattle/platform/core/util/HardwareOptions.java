package io.cattle.platform.core.util;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

/** Validate the shared LaunchConfig contract before creating/upgrading work. */
public final class HardwareOptions {
    private HardwareOptions() { }

    public static void validate(Map<?, ?> config) {
        String error = error(config);
        if (error != null) {
            ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_OPTION, error);
        }
    }

    public static String error(Map<?, ?> config) {
        Object shm = config.get("shmSize");
        Object ipc = config.get("ipcMode");
        if (shm != null && (!(shm instanceof Number) || ((Number) shm).longValue() < 0 ||
                ((Number) shm).doubleValue() != ((Number) shm).longValue())) {
            return "shmSize must be non-negative bytes";
        }
        if (shm instanceof Number && ((Number) shm).longValue() > 0 && ipc != null &&
                !"".equals(ipc) && !"private".equals(ipc) && !"shareable".equals(ipc)) {
            return "shmSize requires private or shareable IPC";
        }
        Object runtime = config.get("runtime");
        if (runtime != null && (!(runtime instanceof String) || !runtime.toString().matches("[A-Za-z0-9_.-]*"))) {
            return "runtime must be a registered runtime name";
        }
        for (String field : new String[]{"pidsLimit", "cpuQuota", "cpuPeriod"}) {
            Object value = config.get(field);
            if (value == null) { continue; }
            if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()) ||
                    ((Number) value).doubleValue() != ((Number) value).longValue()) {
                return field + " must be an integer";
            }
            long number = ((Number) value).longValue();
            if ((field.equals("pidsLimit") && number < -1) ||
                    (field.equals("cpuQuota") && number != -1 && number != 0 && number < 1000) ||
                    (field.equals("cpuPeriod") && number != 0 && (number < 1000 || number > 1000000))) {
                return field + " is outside the Docker-supported range";
            }
        }
        for (String field : new String[]{"tmpfs", "sysctls"}) {
            Object value = config.get(field);
            if (value == null) { continue; }
            if (!(value instanceof Map<?, ?>)) { return field + " must be a string map"; }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String) ||
                        entry.getKey().toString().trim().isEmpty()) { return field + " requires string keys and values"; }
                String key = entry.getKey().toString();
                if (field.equals("tmpfs") && (!key.startsWith("/") || key.indexOf('\0') >= 0 ||
                        java.util.Arrays.asList(key.split("/")).contains(".."))) { return "tmpfs requires an absolute mount path"; }
                if (field.equals("sysctls") && (!key.matches("[A-Za-z0-9_.]+") || entry.getValue().toString().trim().isEmpty())) {
                    return "sysctls requires a kernel parameter name and non-empty value";
                }
            }
        }
        Object rawLimits = config.get("ulimits");
        if (rawLimits != null) {
            if (!(rawLimits instanceof List<?>)) { return "ulimits must be a list"; }
            Set<Object> names = new HashSet<>();
            Set<String> allowed = Set.of("as", "core", "cpu", "data", "fsize", "locks", "memlock", "msgqueue", "nice", "nofile", "nproc", "rss", "rtprio", "rttime", "sigpending", "stack");
            for (Object item : (List<?>) rawLimits) {
                if (!(item instanceof Map<?, ?>)) { return "ulimits must contain objects"; }
                Map<?, ?> limit = (Map<?, ?>) item;
                if (!(limit.get("name") instanceof String) || !allowed.contains(limit.get("name")) || !names.add(limit.get("name"))) {
                    return "ulimits names must be supported and unique";
                }
                for (String field : new String[]{"soft", "hard"}) {
                    Object value = limit.get(field);
                    if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()) ||
                            ((Number) value).doubleValue() != ((Number) value).longValue() || ((Number) value).longValue() < -1) {
                        return "ulimits requires integer soft and hard limits >= -1";
                    }
                }
                long soft = ((Number) limit.get("soft")).longValue(), hard = ((Number) limit.get("hard")).longValue();
                if (hard != -1 && (soft == -1 || soft > hard)) { return "ulimits soft cannot exceed hard"; }
            }
        }
        Object raw = config.get("deviceRequests");
        if (raw == null) { return null; }
        if (!(raw instanceof List<?>)) { return "deviceRequests must be a list"; }
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map<?, ?>)) { return "deviceRequests must contain objects"; }
            Map<?, ?> request = (Map<?, ?>) item;
            Object rawCount = request.get("count");
            if (rawCount != null && (!(rawCount instanceof Number) || ((Number) rawCount).doubleValue() != ((Number) rawCount).longValue())) {
                return "deviceRequests count must be an integer";
            }
            long count = rawCount == null ? 0 : ((Number) rawCount).longValue();
            Object ids = request.get("deviceIds");
            if (ids != null && !(ids instanceof List<?>)) { return "deviceIds must be a list"; }
            List<?> devices = ids == null ? java.util.Collections.emptyList() : (List<?>) ids;
            if (count < -1 || count > Integer.MAX_VALUE || (count != 0) == !devices.isEmpty()) {
                return "Choose a GPU count or deviceIds, not both";
            }
            Set<String> unique = new HashSet<>();
            for (Object id : devices) {
                if (!(id instanceof String) || ((String) id).trim().isEmpty() || !unique.add((String) id)) {
                    return "deviceIds must be unique non-empty strings";
                }
            }
            Object groups = request.get("capabilities");
            if (!(groups instanceof List<?>) || ((List<?>) groups).isEmpty()) { return "Device capabilities are required"; }
            for (Object group : (List<?>) groups) {
                if (!(group instanceof List<?>) || ((List<?>) group).isEmpty()) { return "Capability groups cannot be empty"; }
                for (Object capability : (List<?>) group) {
                    if (!(capability instanceof String) || ((String) capability).trim().isEmpty()) { return "Capabilities must be non-empty strings"; }
                }
            }
        }
        return null;
    }
}
