package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.object.util.DataAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** TCP service-reference rules over the existing native load-balancer create owner. */
final class AiLoadBalancerCreateChange {
    static final int MAX_RULES = 16;
    private static final Set<String> RULE_FIELDS = Set.of("protocol", "access", "sourcePort", "targetPort", "serviceId");
    private AiLoadBalancerCreateChange() { }

    static boolean supports(AiResourceChangeDescriptor descriptor) {
        return "loadBalancerService".equals(descriptor.resourceType()) && descriptor.createsResource();
    }

    static Map<String, Object> parameters(Set<String> allowed, Object value) {
        if (!(value instanceof Map<?, ?> input) || !allowed.containsAll(input.keySet())) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : ((Map<?, ?>) value).entrySet()) {
            String field = (String) entry.getKey(); Object item = entry.getValue();
            switch (field) {
            case "name", "stackId" -> text(item, 1, "name".equals(field) ? 255 : 128);
            case "description" -> { if (item != null) text(item, 0, 1024); }
            case "startOnCreate" -> { if (!(item instanceof Boolean)) throw invalid(); }
            case "portRules" -> item = rules(item);
            default -> throw invalid();
            }
            result.put(field, item);
        }
        if (!result.keySet().containsAll(Set.of("name", "stackId", "startOnCreate", "portRules"))) throw invalid();
        return result;
    }

    static List<Map<String, Object>> rules(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_RULES) throw invalid();
        List<Map<String, Object>> result = new ArrayList<>(); Map<Integer, String> listeners = new LinkedHashMap<>();
        for (Object row : (List<?>) value) {
            if (!(row instanceof Map<?, ?> rule) || !rule.keySet().equals(RULE_FIELDS)
                    || !"tcp".equals(rule.get("protocol")) || !Set.of("public", "internal").contains(rule.get("access"))) throw invalid();
            int source = port(rule.get("sourcePort")), target = port(rule.get("targetPort"));
            if (source == 42) throw invalid(); // Reserved by the native ServiceValidationFilter.
            String access = (String) rule.get("access"); text(rule.get("serviceId"), 1, 128);
            if (listeners.putIfAbsent(source, access) instanceof String before && !before.equals(access)) throw invalid();
            result.add(Map.of("protocol", "tcp", "access", access, "sourcePort", source,
                    "targetPort", target, "serviceId", rule.get("serviceId")));
        }
        return List.copyOf(result); // Keep order and duplicate backend rules exactly; only listener bindings deduplicate.
    }

    static Map<String, Object> body(Map<String, Object> parameters) {
        List<Map<String, Object>> rules = rules(parameters.get("portRules"));
        List<Map<String, Object>> nativeRules = new ArrayList<>();
        Set<String> ports = new LinkedHashSet<>(), expose = new LinkedHashSet<>();
        for (Map<String, Object> rule : rules) {
            Map<String, Object> nativeRule = new LinkedHashMap<>(rule); nativeRule.remove("access");
            nativeRule.put("priority", nativeRules.size() + 1); nativeRules.add(nativeRule);
            String spec = rule.get("sourcePort") + ":" + rule.get("sourcePort") + "/tcp";
            ("public".equals(rule.get("access")) ? ports : expose).add(spec);
        }
        String image = ArchaiusUtil.getStringProperty("lb.instance.image").get();
        if (image == null || image.isBlank() || image.length() > 512 || !image.matches("[A-Za-z0-9][A-Za-z0-9._:/@-]*"))
            throw AiResourceChangeDescriptor.error("ResourceChangePreflightUnavailable");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("name", "description", "stackId", "startOnCreate"))
            if (parameters.containsKey(field)) result.put(field, parameters.get(field));
        result.put("type", "loadBalancerService"); result.put("scale", 1);
        result.put("launchConfig", Map.of("imageUuid", image.startsWith("docker:") ? image : "docker:" + image,
                "restartPolicy", Map.of("name", "always"), "ports", List.copyOf(ports), "expose", List.copyOf(expose)));
        result.put("lbConfig", Map.of("portRules", nativeRules));
        return result;
    }

    static boolean submittedMatches(Map<String, Object> approved, Map<String, Object> actual,
            Function<String, Long> serviceId, Function<String, Long> stackId) {
        try {
            if (actual.containsKey("type") && !"loadBalancerService".equals(actual.get("type"))) return false;
            if (!Objects.equals(approved.get("name"), actual.get("name")) || !Objects.equals(approved.get("description"), actual.get("description"))
                    || !Objects.equals(approved.get("startOnCreate"), actual.get("startOnCreate"))
                    || !(actual.get("scale") instanceof Number scale) || scale.doubleValue() != 1
                    || actual.get("stackId") == null || !Objects.equals(stackId.apply((String) approved.get("stackId")),
                            nativeReferenceId(actual.get("stackId"), stackId))
                    || !(approved.get("launchConfig") instanceof Map<?, ?> expectedLaunch)
                    || !(actual.get("launchConfig") instanceof Map<?, ?> launch)
                    || !Objects.equals(expectedLaunch.get("imageUuid"), launch.get("imageUuid"))
                    || !restartMatches(launch.get("restartPolicy"))) return false;
            return configurationsMatch(approved, launch, actual.get("lbConfig"), serviceId);
        } catch (IllegalArgumentException | io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid) { return false; }
    }

    static boolean configurationMatches(Service service, Map<String, Object> approved, long stackId,
            String normalizedImage, Function<String, Long> serviceId) {
        if (!"loadBalancerService".equals(service.getKind()) || service.getRemoved() != null
                || !Objects.equals(service.getStackId(), stackId) || !Objects.equals(service.getName(), approved.get("name"))
                || !Objects.equals(service.getDescription(), approved.get("description"))
                || !Objects.equals(DataAccessor.field(service, "startOnCreate", Object.class), approved.get("startOnCreate"))
                || !(DataAccessor.field(service, "scale", Object.class) instanceof Number scale) || scale.doubleValue() != 1) return false;
        if (!(approved.get("launchConfig") instanceof Map<?, ?> expected)
                || !(DataAccessor.field(service, "launchConfig", Object.class) instanceof Map<?, ?> launch)
                || !Objects.equals(launch.get("imageUuid"), normalizedImage) || !restartMatches(launch.get("restartPolicy"))) return false;
        return configurationsMatch(approved, launch, DataAccessor.field(service, "lbConfig", Object.class), serviceId);
    }

    private static boolean restartMatches(Object value) {
        if (!(value instanceof Map<?, ?> restart) || !"always".equals(restart.get("name"))
                || restart.get("type") != null && !"restartPolicy".equals(restart.get("type"))) return false;
        for (var entry : restart.entrySet()) {
            if (Set.of("name", "type").contains(entry.getKey())) continue;
            if (!"maximumRetryCount".equals(entry.getKey()) || !(entry.getValue() instanceof Number retries) || retries.doubleValue() != 0) return false;
        }
        return true;
    }

    private static boolean configurationsMatch(Map<String, Object> approved, Map<?, ?> launch, Object rawConfig, Function<String, Long> serviceId) {
        try {
            Map<?, ?> expected = (Map<?, ?>) approved.get("launchConfig");
            if (!AiCreatePorts.configuredMatch(expected.get("ports"), launch.get("ports"))
                    || !AiCreatePorts.configuredMatch(expected.get("expose"), launch.get("expose"))) return false;
            if (!(approved.get("lbConfig") instanceof Map<?, ?> expectedConfig)
                    || !(rawConfig instanceof Map<?, ?> actual)) return false;
            if (actual.get("type") != null && !"lbConfig".equals(actual.get("type"))) return false;
            for (var entry : actual.entrySet()) {
                if (Set.of("type", "portRules").contains(entry.getKey())) continue;
                if (entry.getValue() != null && !("certificateIds".equals(entry.getKey()) && List.of().equals(entry.getValue()))
                        && !("config".equals(entry.getKey()) && "".equals(entry.getValue()))) return false;
            }
            return nativeRules(expectedConfig.get("portRules"), serviceId).equals(nativeRules(actual.get("portRules"), serviceId));
        } catch (IllegalArgumentException | io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid) { return false; }
    }

    private static List<Map<String, Object>> nativeRules(Object raw, Function<String, Long> serviceId) {
        if (!(raw instanceof List<?> rules) || rules.isEmpty() || rules.size() > MAX_RULES) throw invalid();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rules) {
            if (!(row instanceof Map<?, ?> rule)) throw invalid();
            for (var entry : rule.entrySet())
                if (!Set.of("type", "protocol", "sourcePort", "targetPort", "serviceId", "priority").contains(entry.getKey())
                        && entry.getValue() != null) throw invalid();
            if (!"tcp".equals(rule.get("protocol")) || rule.get("type") != null && !"portRule".equals(rule.get("type"))) throw invalid();
            int priority = port(rule.get("priority"));
            if (priority != result.size() + 1) throw invalid();
            result.add(Map.of("protocol", "tcp", "sourcePort", port(rule.get("sourcePort")), "targetPort", port(rule.get("targetPort")),
                    "priority", priority, "serviceId", nativeReferenceId(rule.get("serviceId"), serviceId)));
        }
        return result;
    }

    private static long nativeReferenceId(Object value, Function<String, Long> parser) {
        final Long parsed;
        if (value instanceof String text) {
            // Strings still belong to the canonical wire-ID parser. Only the
            // native framework's numeric reference values bypass that parser.
            text(text, 1, 128); parsed = parser.apply(text);
        } else if (value instanceof Number number) {
            // This path reads native conversion/persistence, never model input.
            if ((number instanceof Double || number instanceof Float)
                    && (!Double.isFinite(number.doubleValue()) || Math.abs(number.doubleValue()) > 9007199254740991D)) throw invalid();
            try { parsed = new java.math.BigDecimal(number.toString()).longValueExact(); }
            catch (ArithmeticException | NumberFormatException unsafe) { throw invalid(); }
        } else throw invalid();
        if (parsed == null || parsed <= 0) throw invalid();
        return parsed;
    }

    private static int port(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.longValue()
                || number.longValue() < 1 || number.longValue() > 65535) throw invalid();
        return ((Number) value).intValue();
    }
    private static void text(Object value, int minimum, int maximum) {
        if (!(value instanceof String text) || text.length() < minimum || text.length() > maximum || text.indexOf(0) >= 0
                || minimum > 0 && text.isBlank()) throw invalid();
    }
    private static io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid() {
        return AiResourceChangeDescriptor.error("ResourceChangeInputInvalid");
    }
}
