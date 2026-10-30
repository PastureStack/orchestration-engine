package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.object.util.ObjectUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Closed Alias adapter over the normal DNS Service and set-service-links owners. */
public final class AiAliasServiceChange {
    public static final int MAX_TARGETS = 16;
    private AiAliasServiceChange() { }

    public static Map<String, Object> parameters(String operation, Set<String> allowed, Object value) {
        if (!(value instanceof Map<?, ?> input) || !allowed.containsAll(input.keySet())) invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : ((Map<?, ?>) value).entrySet()) {
            String field = (String) entry.getKey(); Object item = entry.getValue();
            switch (field) {
            case "name" -> text(item, 1, 255);
            case "description" -> { if (item != null) text(item, 0, 1024); }
            case "stackId" -> text(item, 1, 128);
            case "serviceIds" -> {
                if (!(item instanceof List<?> ids) || ids.size() > MAX_TARGETS) invalid();
                List<String> ids = new ArrayList<>();
                for (Object id : (List<?>) item) { text(id, 1, 128); ids.add((String) id); }
                if (new LinkedHashSet<>(ids).size() != ids.size()) invalid();
                item = List.copyOf(ids);
            }
            default -> invalid();
            }
            result.put(field, item);
        }
        if ("create".equals(operation) && (!result.containsKey("name") || !result.containsKey("stackId"))
                || "update_targets".equals(operation) && !result.containsKey("serviceIds")
                || "update_metadata".equals(operation) && result.isEmpty()) invalid();
        return result;
    }

    public static Map<String, Object> body(String operation, Map<String, Object> parameters) {
        if ("update_targets".equals(operation)) {
            List<Map<String, Object>> links = new ArrayList<>();
            for (Object id : (List<?>) parameters.get("serviceIds")) links.add(Map.of("serviceId", id));
            return new LinkedHashMap<>(Map.of("serviceLinks", links));
        }
        Map<String, Object> result = new LinkedHashMap<>(parameters);
        if ("create".equals(operation)) { result.put("type", "dnsService"); result.put("startOnCreate", false); }
        return result;
    }

    public static boolean simpleLink(ServiceConsumeMap map) {
        Object ports = io.cattle.platform.object.util.DataAccessor.field(map, "ports", Object.class);
        return map.getConsumedServiceId() != null && map.getConsumedService() == null && map.getName() == null
                && (ports == null || ports instanceof List<?> list && list.isEmpty());
    }

    public static boolean complete(String operation, Service service, Map<String, Object> parameters,
            Set<Long> expected, List<? extends ServiceConsumeMap> maps) {
        if (!"dnsService".equals(service.getKind()) || maps == null) return false;
        Set<Long> active = new LinkedHashSet<>(); boolean settled = true;
        for (ServiceConsumeMap map : maps) {
            if (!Objects.equals(map.getServiceId(), service.getId()) || !Objects.equals(map.getAccountId(), service.getAccountId())) return false;
            if (map.getRemoved() != null) {
                if (!Set.of("removed", "purged").contains(String.valueOf(map.getState()))) return false;
                continue;
            }
            if (!simpleLink(map) || !active.add(map.getConsumedServiceId())) return false;
            if (!"active".equals(map.getState())) settled = false;
        }
        if ("remove".equals(operation)) return service.getRemoved() != null
                && Set.of("removed", "purged").contains(String.valueOf(service.getState())) && active.isEmpty();
        if (service.getRemoved() != null) return false;
        return switch (operation) {
        case "create" -> "inactive".equals(service.getState()) && active.isEmpty();
        case "start" -> "active".equals(service.getState()) && settled;
        case "stop" -> "inactive".equals(service.getState()) && settled;
        case "update_targets" -> Set.of("active", "inactive").contains(String.valueOf(service.getState()))
                && settled && expected != null && expected.equals(active);
        case "update_metadata" -> parameters.entrySet().stream().allMatch(entry ->
                Objects.equals(entry.getValue(), ObjectUtils.getPropertyIgnoreErrors(service, entry.getKey())));
        default -> false;
        };
    }

    private static void text(Object value, int minimum, int maximum) {
        if (!(value instanceof String text) || text.length() < minimum || text.length() > maximum || text.indexOf(0) >= 0
                || minimum > 0 && text.isBlank()) invalid();
    }
    private static void invalid() { throw AiResourceChangeDescriptor.error("ResourceChangeInputInvalid"); }
}
