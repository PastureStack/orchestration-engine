package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.object.util.DataAccessor;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A closed adapter to the existing External Service owner, with no network I/O. */
public final class AiExternalServiceChange {
    private AiExternalServiceChange() { }

    public static Map<String, Object> parameters(String operation, Set<String> allowed, Object value) {
        if (!(value instanceof Map<?, ?> input) || !allowed.containsAll(input.keySet())) invalid();
        Map<?, ?> input = (Map<?, ?>) value;
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            String key = (String) entry.getKey(); Object content = entry.getValue();
            switch (key) {
            case "name" -> text(content, 1, 255, false);
            case "description" -> { if (content != null) text(content, 0, 1024, true); }
            case "stackId" -> text(content, 1, 128, false);
            case "hostname" -> { if (!(content instanceof String host) || !hostname(host)) invalid(); }
            case "externalIpAddresses" -> content = addresses(content);
            default -> invalid();
            }
            result.put(key, content);
        }
        if ("create".equals(operation) && (!result.containsKey("name") || !result.containsKey("stackId"))) invalid();
        if (Set.of("create", "update_targets").contains(operation)
                && result.containsKey("hostname") == result.containsKey("externalIpAddresses")) invalid();
        if ("update_metadata".equals(operation) && result.isEmpty()) invalid();
        return result;
    }

    public static Map<String, Object> body(String operation, Map<String, Object> parameters) {
        Map<String, Object> result = new LinkedHashMap<>(parameters);
        if (Set.of("create", "update_targets").contains(operation)) {
            // The normal form clears the other mode. Omission must not preserve an old target.
            result.putIfAbsent("hostname", null); result.putIfAbsent("externalIpAddresses", null);
        }
        if ("create".equals(operation)) {
            result.put("type", "externalService"); result.put("startOnCreate", false);
            result.put("launchConfig", new LinkedHashMap<>());
        }
        return result;
    }

    public static boolean hostname(String value) {
        if (value == null || value.length() > 253 || value.isEmpty()) return false;
        String host = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
        if (host.isEmpty()) return false;
        for (String label : host.split("\\.", -1))
            if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) return false;
        return true;
    }

    public static boolean address(String value) {
        if (value == null || value.length() > 45) return false;
        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) return false;
            for (String part : parts)
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
            return true;
        }
        // Numeric-only first: InetAddress never receives a name or scope/URL and cannot do DNS.
        if (!value.matches("[0-9A-Fa-f:.]+") || value.chars().filter(c -> c == ':').count() < 2) return false;
        if (value.contains(".") && !address(value.substring(value.lastIndexOf(':') + 1))) return false;
        try { InetAddress.getByName(value); return true; }
        catch (UnknownHostException invalid) { return false; }
    }

    public static List<String> addresses(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 16) invalid();
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String text) || !address(text)) invalid();
            if (result.contains(item)) invalid();
            result.add((String) item);
        }
        return List.copyOf(result);
    }

    /** Safe scalar representation for the existing typed-view/tool DTO. */
    public static Map<String, Object> summary(Map<String, Object> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object host = fields.get("hostname"), ips = fields.get("externalIpAddresses");
        if (host instanceof String text && hostname(text) && (ips == null || ips instanceof List<?> list && list.isEmpty())) {
            result.put("hostname", host); result.put("externalIpAddresses", null);
        } else if (host == null || "".equals(host)) {
            try {
                List<String> valid = addresses(ips);
                result.put("hostname", null); result.put("externalIpAddresses", jsonAddresses(valid));
            } catch (io.github.ibuildthecloud.gdapi.exception.ClientVisibleException ignored) {
                // Existing ordinary API data may be outside the typed safe subset. Never echo it.
            }
        }
        return result;
    }

    private static String jsonAddresses(List<String> values) { return "[\"" + String.join("\",\"", values) + "\"]"; }

    public static boolean complete(String operation, Service service, Map<String, Object> parameters,
            List<? extends ServiceExposeMap> maps) {
        if (!"externalService".equals(service.getKind()) || maps == null) return false;
        Set<String> active = new LinkedHashSet<>();
        boolean settled = true;
        for (ServiceExposeMap map : maps) {
            if (!Objects.equals(map.getServiceId(), service.getId()) || !Objects.equals(map.getAccountId(), service.getAccountId())
                    || map.getInstanceId() != null || !Boolean.TRUE.equals(map.getManaged()) || Boolean.TRUE.equals(map.getUpgrade())) return false;
            if (map.getRemoved() != null) {
                if (!Set.of("removed", "purged").contains(map.getState())) return false;
                continue;
            }
            if (!"active".equals(map.getState())) settled = false;
            String target = endpoint(map);
            if (target == null || !active.add(target)) return false;
        }
        if ("remove".equals(operation)) return service.getRemoved() != null && Set.of("removed", "purged").contains(service.getState())
                && active.isEmpty();
        if (service.getRemoved() != null) return false;
        if ("update_metadata".equals(operation)) return parameters.entrySet().stream()
                .allMatch(entry -> Objects.equals(entry.getValue(),
                        io.cattle.platform.object.util.ObjectUtils.getPropertyIgnoreErrors(service, entry.getKey())));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hostname", DataAccessor.fieldString(service, "hostname"));
        fields.put("externalIpAddresses", DataAccessor.field(service, "externalIpAddresses", Object.class));
        if (Set.of("create", "update_targets").contains(operation)) {
            for (String field : List.of("hostname", "externalIpAddresses")) {
                Object expected = parameters.get(field), actual = fields.get(field);
                if (!Objects.equals(expected, actual)) return false;
            }
        }
        if ("create".equals(operation) || "stop".equals(operation)) return "inactive".equals(service.getState()) && active.isEmpty();
        if ("update_targets".equals(operation) && "inactive".equals(service.getState())) return active.isEmpty();
        Set<String> expected = expected(fields);
        return "active".equals(service.getState()) && settled && expected != null && expected.equals(active);
    }

    public static String endpoint(ServiceExposeMap map) {
        if (map.getHostName() != null && hostname(map.getHostName()) && map.getIpAddress() == null) return "dns:" + map.getHostName();
        if (map.getHostName() == null && address(map.getIpAddress())) return "ip:" + map.getIpAddress();
        return null;
    }

    public static boolean matchesTarget(Service service, ServiceExposeMap map) {
        if (!"externalService".equals(service.getKind())) return false;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hostname", DataAccessor.fieldString(service, "hostname"));
        fields.put("externalIpAddresses", DataAccessor.field(service, "externalIpAddresses", Object.class));
        Set<String> targets = expected(fields);
        String endpoint = endpoint(map);
        return targets != null && endpoint != null && targets.contains(endpoint);
    }

    private static Set<String> expected(Map<String, Object> fields) {
        Map<String, Object> safe = summary(fields);
        if (safe.isEmpty()) return null;
        if (safe.get("hostname") instanceof String hostname) return Set.of("dns:" + hostname);
        Set<String> result = new LinkedHashSet<>();
        for (String address : addresses(fields.get("externalIpAddresses"))) result.add("ip:" + address);
        return result;
    }

    private static void text(Object value, int min, int max, boolean blank) {
        if (!(value instanceof String text) || text.length() < min || text.length() > max || text.indexOf(0) >= 0
                || !blank && text.isBlank()) invalid();
    }

    private static void invalid() { throw AiResourceChangeDescriptor.error("ResourceChangeInputInvalid"); }
}
