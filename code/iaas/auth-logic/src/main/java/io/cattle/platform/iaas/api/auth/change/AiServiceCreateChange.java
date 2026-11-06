package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.object.util.DataAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The reviewed Service-create subset; raw launch configuration and environment stay outside it. */
final class AiServiceCreateChange {
    static final int MAX_PORTS = AiCreatePorts.MAX_PORTS;
    private static final Set<String> HEALTH_FIELDS = Set.of("port", "requestLine", "responseTimeout", "interval",
            "healthyThreshold", "unhealthyThreshold", "initializingTimeout", "reinitializingTimeout", "strategy");

    private AiServiceCreateChange() { }

    static List<String> ports(Object value) {
        return AiCreatePorts.parameters(value);
    }

    static Map<String, Object> healthCheck(Object value) {
        if (!(value instanceof Map<?, ?> input) || input.size() > HEALTH_FIELDS.size()) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String field) || !HEALTH_FIELDS.contains(field)) throw invalid();
            Object item = entry.getValue();
            if ("requestLine".equals(field)) {
                if (!(item instanceof String text) || text.length() > 1024 || text.indexOf('\0') >= 0
                        || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) throw invalid();
            } else if ("strategy".equals(field)) {
                if (!(item instanceof String text) || !Set.of("none", "recreate").contains(text)) throw invalid();
            } else {
                int minimum = Set.of("initializingTimeout", "reinitializingTimeout").contains(field) ? 0 : 1;
                int maximum = "port".equals(field) ? 65535 : Integer.MAX_VALUE;
                if (!(item instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != number.longValue() || number.longValue() < minimum
                        || number.longValue() > maximum) throw invalid();
                item = number.intValue();
            }
            result.put(field, item);
        }
        if (!result.containsKey("port")) throw invalid();
        result.putIfAbsent("strategy", "recreate"); // InstanceHealthCheck's native schema default.
        return java.util.Collections.unmodifiableMap(result);
    }

    static boolean complete(Service service, Map<String, Object> expected, long stackId, String imageUuid) {
        if (!"service".equals(service.getKind()) || service.getRemoved() != null || !"inactive".equals(service.getState())
                || !Objects.equals(service.getName(), expected.get("name"))
                || !Objects.equals(service.getDescription(), expected.get("description"))
                || !Objects.equals(service.getStackId(), stackId)
                || !sameNumber(DataAccessor.field(service, "scale", Object.class), expected.get("scale"))
                || !Boolean.FALSE.equals(DataAccessor.field(service, "startOnCreate", Object.class))) return false;
        if (!(expected.get("launchConfig") instanceof Map<?, ?> wanted)
                || !(DataAccessor.field(service, "launchConfig", Object.class) instanceof Map<?, ?> actual)
                || !Objects.equals(imageUuid, actual.get("imageUuid")) || !"bridge".equals(actual.get("networkMode"))) return false;
        for (String field : List.of("command", "entryPoint")) {
            Object value = actual.get(field), target = wanted.get(field);
            if (!Objects.equals(target, value)) return false;
        }
        try {
            return AiCreatePorts.assignedMatch(wanted.get("ports"), actual.get("ports"))
                    && Objects.equals(canonicalHealth(wanted.get("healthCheck")), canonicalHealth(actual.get("healthCheck")));
        } catch (IllegalArgumentException | io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalidReadback) {
            return false;
        }
    }

    private static boolean sameNumber(Object actual, Object expected) {
        return actual instanceof Number first && expected instanceof Number second
                && first.doubleValue() == first.longValue() && second.doubleValue() == second.longValue()
                && first.longValue() == second.longValue();
    }

    private static Map<String, Object> canonicalHealth(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> input)) throw invalid();
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid();
            if ("type".equals(key) && "instanceHealthCheck".equals(entry.getValue())) continue;
            if (entry.getValue() != null) fields.put(key, entry.getValue());
        }
        return healthCheck(fields);
    }

    private static io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid() {
        return AiResourceChangeDescriptor.error("ResourceChangeInputInvalid");
    }
}
