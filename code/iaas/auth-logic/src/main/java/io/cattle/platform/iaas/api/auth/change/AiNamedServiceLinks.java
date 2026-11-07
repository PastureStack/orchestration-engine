package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.object.util.DataAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Named local ordinary-Service links only; native ServiceLink identity is (id, name). */
public final class AiNamedServiceLinks {
    private AiNamedServiceLinks() { }

    public static boolean supports(AiResourceChangeDescriptor descriptor, Map<String, Object> parameters) {
        return AiAliasServiceChange.ordinaryLinks(descriptor) && parameters.containsKey("links");
    }

    /** Same validChars and label limits as serviceLink.json / validateLinkName; no trimming or case folding. */
    public static boolean validName(Object value) {
        if (value == null) return true;
        if (!(value instanceof String name)) return false;
        if (name.isEmpty()) return true;
        if (name.length() > 253 || !name.matches("[a-zA-Z0-9._-]+") || name.startsWith(".") || name.endsWith(".") || name.contains("..")) return false;
        for (String part : name.split("\\."))
            if (part.isEmpty() || part.length() > 63 || part.startsWith("-") || part.endsWith("-") || part.contains("--")) return false;
        return true;
    }

    public static Map<String, Object> parameters(Object value) {
        if (!(value instanceof Map<?, ?> input) || !input.keySet().equals(Set.of("links"))) throw invalid();
        Object list = ((Map<?, ?>) value).get("links");
        if (!(list instanceof List<?> rows) || rows.size() > AiAliasServiceChange.MAX_TARGETS) throw invalid();
        List<Map<String, Object>> result = new ArrayList<>(); Set<List<String>> unique = new LinkedHashSet<>();
        for (Object item : (List<?>) list) {
            if (!(item instanceof Map<?, ?> row) || !row.keySet().equals(Set.of("serviceId", "name"))
                    || !(row.get("serviceId") instanceof String id) || id.isBlank() || id.length() > 128 || id.indexOf(0) >= 0
                    || !validName(row.get("name"))) throw invalid();
            if (!unique.add(List.of(id, canonicalName(row.get("name"))))) throw invalid();
            Map<String, Object> link = new LinkedHashMap<>(); link.put("serviceId", id); link.put("name", row.get("name"));
            result.add(Collections.unmodifiableMap(link));
        }
        return Map.of("links", List.copyOf(result));
    }

    public static List<Map<String, Object>> rows(Map<String, Object> parameters) {
        return (List<Map<String, Object>>) parameters(parameters).get("links");
    }

    /** References are unique targets; multiple aliases never bypass the same current target/Stack authorization. */
    public static List<String> targetIds(Map<String, Object> parameters) {
        if (!parameters.containsKey("links")) return (List<String>) parameters.get("serviceIds");
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> row : rows(parameters)) ids.add((String) row.get("serviceId"));
        return List.copyOf(ids);
    }

    public static String canonicalName(Object value) { return value == null ? "" : (String) value; }

    public static boolean localLink(ServiceConsumeMap map) {
        Object ports = DataAccessor.field(map, "ports", Object.class);
        return map.getConsumedServiceId() != null && map.getConsumedService() == null && validName(map.getName())
                && (ports == null || ports instanceof List<?> list && list.isEmpty());
    }

    public static boolean complete(Service service, Map<String, Object> parameters, Function<String, Long> resolve,
            List<? extends ServiceConsumeMap> maps) {
        if (!"service".equals(service.getKind()) || service.getRemoved() != null
                || !Set.of("active", "inactive").contains(Objects.toString(service.getState(), "")) || maps == null) return false;
        Set<List<Object>> expected = new LinkedHashSet<>(), actual = new LinkedHashSet<>();
        for (Map<String, Object> row : rows(parameters))
            if (!expected.add(List.of(resolve.apply((String) row.get("serviceId")), canonicalName(row.get("name"))))) return false;
        for (ServiceConsumeMap map : maps) {
            if (!Objects.equals(map.getServiceId(), service.getId()) || !Objects.equals(map.getAccountId(), service.getAccountId())) return false;
            if (map.getRemoved() != null) {
                if (!Set.of("removed", "purged").contains(Objects.toString(map.getState(), ""))) return false;
                continue;
            }
            if (!localLink(map) || !"active".equals(map.getState())
                    || !actual.add(List.of(map.getConsumedServiceId(), canonicalName(map.getName())))) return false;
        }
        return expected.equals(actual);
    }

    private static io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid() {
        return AiResourceChangeDescriptor.error("ResourceChangeInputInvalid");
    }
}
