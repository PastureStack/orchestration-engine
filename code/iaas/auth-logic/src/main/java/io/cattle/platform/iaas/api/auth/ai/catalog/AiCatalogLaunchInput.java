package io.cattle.platform.iaas.api.auth.ai.catalog;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed transport and safe-output shapes. Question semantics remain with the Catalog owner. */
final class AiCatalogLaunchInput {
    static final String OPTIONS = "aiCatalogStackLaunchOptions";
    static final String PLAN = "aiCatalogStackLaunchPlan";
    static final String TEMPLATE = "ctm-[0-9a-f]{64}";
    static final String VERSION = "cvm-[0-9a-f]{64}";
    private static final Set<String> QUESTION_TYPES = Set.of("string", "int", "float", "boolean", "enum", "reference", "human");

    private AiCatalogLaunchInput() { }

    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw error(422, "CatalogLaunchInputInvalid");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw error(422, "CatalogLaunchInputInvalid");
            result.put(key, entry.getValue());
        }
        return result;
    }

    static Map<String, Object> closed(Object value, Set<String> fields) {
        Map<String, Object> result = map(value);
        if (!fields.containsAll(result.keySet())) throw error(422, "CatalogLaunchInputInvalid");
        return result;
    }

    static String text(Object value, int minimum, int maximum) {
        if (!(value instanceof String text) || text.length() < minimum || text.length() > maximum || text.indexOf('\0') >= 0)
            throw error(422, "CatalogLaunchInputInvalid");
        return text;
    }

    static String id(Object value, String pattern) {
        String id = text(value, 1, 128);
        if (!id.matches(pattern)) throw error(422, "CatalogLaunchInputInvalid");
        return id;
    }

    static Map<String, Object> primitives(Object value) {
        Map<String, Object> result = map(value); int units = 0;
        if (result.size() > 100) throw error(422, "CatalogLaunchInputInvalid");
        for (var entry : result.entrySet()) {
            text(entry.getKey(), 1, 128);
            Object item = entry.getValue();
            if (item instanceof String string) units += text(string, 0, 16384).length();
            else if (!(item instanceof Boolean) && (!(item instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || Math.abs(number.doubleValue()) > 9007199254740991D)) throw error(422, "CatalogLaunchInputInvalid");
        }
        if (units > 131072) throw error(422, "CatalogLaunchInputInvalid");
        return result;
    }

    static List<Map<String, Object>> questions(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 100) throw error(503, "CatalogLaunchUnavailable");
        List<Map<String, Object>> result = new ArrayList<>(); Set<String> names = new LinkedHashSet<>();
        try {
            for (Object item : list) {
                Map<String, Object> source = map(item), safe = new LinkedHashMap<>();
                String name = text(source.get("name"), 1, 128), type = text(source.get("type"), 1, 16);
                if (!names.add(name) || Set.of("__proto__", "prototype", "constructor").contains(name)
                        || !QUESTION_TYPES.contains(type) || !(source.get("required") instanceof Boolean)
                        || !(source.get("requiresHuman") instanceof Boolean)) throw error(503, "CatalogLaunchUnavailable");
                for (String field : List.of("name", "type", "required", "requiresHuman")) safe.put(field, source.get(field));
                safe.put("label", text(source.get("label"), 0, 256));
                safe.put("description", text(source.get("description"), 0, 1024));
                safe.put("sourceType", text(source.get("sourceType"), 0, 64));
                if ("reference".equals(type)) {
                    if (!Set.of("service", "certificate", "secret").contains(source.get("referenceType")))
                        throw error(503, "CatalogLaunchUnavailable");
                    safe.put("referenceType", source.get("referenceType"));
                }
                if ("human".equals(type) && !Boolean.TRUE.equals(source.get("requiresHuman"))) throw error(503, "CatalogLaunchUnavailable");
                if (source.get("options") instanceof List<?> choices) {
                    if (choices.size() > 50) throw error(503, "CatalogLaunchUnavailable");
                    safe.put("options", choices.stream().map(choice -> text(choice, 0, 512)).toList());
                }
                for (String bound : List.of("minLength", "maxLength", "min", "max")) {
                    Object number = source.get(bound);
                    if (number != null) {
                        if (!(number instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue()
                                || Math.abs(n.doubleValue()) > 9007199254740991D) throw error(503, "CatalogLaunchUnavailable");
                        safe.put(bound, number);
                    }
                }
                result.add(safe);
            }
        } catch (ClientVisibleException invalid) { throw error(503, "CatalogLaunchUnavailable"); }
        return result;
    }

    static Map<String, Object> variables(Object value, List<Map<String, Object>> schema) {
        try {
            Map<String, Object> result = primitives(value);
            Set<String> expected = new LinkedHashSet<>(); for (Map<String, Object> question : schema) expected.add((String) question.get("name"));
            if (!result.keySet().equals(expected) || result.values().stream().anyMatch(item -> !(item instanceof String)))
                throw error(503, "CatalogLaunchUnavailable");
            return result;
        } catch (ClientVisibleException invalid) { throw error(503, "CatalogLaunchUnavailable"); }
    }

    static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
