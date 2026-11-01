package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.util.PortBindingAddress;
import io.cattle.platform.core.util.PortSpec;
import io.cattle.platform.object.util.DataAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The reviewed Service-create subset; raw launch configuration and environment stay outside it. */
final class AiServiceCreateChange {
    static final int MAX_PORTS = 64;
    private static final Set<String> HEALTH_FIELDS = Set.of("port", "requestLine", "responseTimeout", "interval",
            "healthyThreshold", "unhealthyThreshold", "initializingTimeout", "reinitializingTimeout", "strategy");

    private AiServiceCreateChange() { }

    static List<String> ports(Object value) {
        if (!(value instanceof List<?> values) || values.size() > MAX_PORTS) throw invalid();
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isEmpty() || text.length() > 128 || text.indexOf('\0') >= 0) throw invalid();
            port(text); // Preserve the native parser's specific, safe validation code.
            result.add(text);
        }
        return List.copyOf(result);
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
            return portsMatch(wanted.get("ports"), actual.get("ports"))
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

    private record BoundPort(String bind, Integer publicPort, int privatePort, String protocol) { }

    private static boolean portsMatch(Object expected, Object actual) {
        List<BoundPort> wanted = new ArrayList<>(canonicalPorts(expected));
        List<BoundPort> remaining = new ArrayList<>(canonicalPorts(actual));
        if (wanted.size() != remaining.size()) return false;
        for (int i = 0; i < remaining.size(); i++) for (int j = i + 1; j < remaining.size(); j++) {
            BoundPort left = remaining.get(i), right = remaining.get(j);
            if (left.publicPort() != null && left.publicPort().equals(right.publicPort())
                    && left.protocol().equals(right.protocol()) && PortBindingAddress.overlaps(left.bind(), right.bind())) return false;
        }
        // ServiceCreate retains fixed mappings first, then allocates every unspecified public port.
        // Match fixed entries first so a random request cannot consume a specifically approved mapping.
        wanted.sort(java.util.Comparator.comparing(port -> port.publicPort() == null));
        for (BoundPort port : wanted) {
            int match = -1;
            for (int i = 0; i < remaining.size(); i++) {
                BoundPort candidate = remaining.get(i);
                if (candidate.publicPort() != null && candidate.publicPort() >= 1 && candidate.publicPort() <= 65535
                        && (port.publicPort() == null || port.publicPort().equals(candidate.publicPort()))
                        && port.privatePort() == candidate.privatePort() && port.protocol().equals(candidate.protocol())
                        && port.bind().equals(candidate.bind())) { match = i; break; }
            }
            if (match < 0) return false;
            remaining.remove(match);
        }
        return remaining.isEmpty();
    }

    private static List<BoundPort> canonicalPorts(Object value) {
        if (value == null) return List.of();
        List<BoundPort> result = new ArrayList<>();
        for (String text : ports(value)) {
            PortSpec spec = port(text);
            result.add(new BoundPort(PortBindingAddress.normalize(spec.getIpAddress()), spec.getPublicPort(),
                    spec.getPrivatePort(), spec.getProtocol()));
        }
        return result;
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

    private static PortSpec port(String text) {
        try { return new PortSpec(text); }
        catch (NumberFormatException invalidNativeNumber) { throw AiResourceChangeDescriptor.error(PortSpec.WRONG_FORMAT); }
    }

    private static io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid() {
        return AiResourceChangeDescriptor.error("ResourceChangeInputInvalid");
    }
}
