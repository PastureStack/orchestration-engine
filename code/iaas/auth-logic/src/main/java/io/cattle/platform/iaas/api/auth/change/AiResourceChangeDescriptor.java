package io.cattle.platform.iaas.api.auth.change;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reviewed business adapters. Neither callers nor plugins supply a transport. */
public record AiResourceChangeDescriptor(String resourceType, String operation, String method,
        String action, String keyOperation, Set<String> parameterNames) {
    private static final Map<String, AiResourceChangeDescriptor> DESCRIPTORS = descriptors();

    private static Map<String, AiResourceChangeDescriptor> descriptors() {
        Map<String, AiResourceChangeDescriptor> result = new LinkedHashMap<>();
        for (String type : Set.of("stack", "service", "container")) {
            add(result, type, "start", "POST", "container".equals(type) ? "start"
                    : "stack".equals(type) ? "activateservices" : "activate", "update", Set.of());
            add(result, type, "stop", "POST", "container".equals(type) ? "stop"
                    : "stack".equals(type) ? "deactivateservices" : "deactivate", "update", Set.of());
            if (!"stack".equals(type)) add(result, type, "restart", "POST", "restart", "update", Set.of());
            add(result, type, "remove", "DELETE", null, "delete", Set.of());
            add(result, type, "update_metadata", "PUT", null, "update", Set.of("name", "description"));
            add(result, type, "create", "POST", null, "create", "stack".equals(type) ? Set.of("name", "description")
                    : "service".equals(type) ? Set.of("name", "description", "image", "scale", "stackId", "command", "entryPoint")
                    : Set.of("name", "description", "image", "command", "entryPoint"));
        }
        add(result, "service", "scale", "PUT", null, "update", Set.of("scale"));
        return Map.copyOf(result);
    }

    private static void add(Map<String, AiResourceChangeDescriptor> descriptors, String type, String operation,
            String method, String action, String keyOperation, Set<String> fields) {
        descriptors.put(type + ":" + operation,
                new AiResourceChangeDescriptor(type, operation, method, action, keyOperation, fields));
    }

    public static AiResourceChangeDescriptor require(String type, String operation) {
        AiResourceChangeDescriptor descriptor = DESCRIPTORS.get(type + ":" + operation);
        if (descriptor == null) throw error("ResourceChangeUnsupported");
        return descriptor;
    }

    public boolean createsResource() { return "create".equals(operation); }

    public Map<String, Object> parameters(Object value) {
        if (!(value instanceof Map<?, ?> input) || input.size() > parameterNames.size()) throw error("ResourceChangeInputInvalid");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String field) || !parameterNames.contains(field)) throw error("ResourceChangeInputInvalid");
            Object content = entry.getValue();
            switch (field) {
            case "name" -> string(content, 1, 255, false);
            case "description" -> { if (content != null) string(content, 0, 1024, true); }
            case "image" -> {
                string(content, 1, 512, false);
                String image = (String) content;
                if (!image.matches("[A-Za-z0-9][A-Za-z0-9._:/@-]*")
                        || image.contains("@") && !image.matches(".+@sha256:[0-9a-fA-F]{64}")) throw error("ResourceChangeInputInvalid");
            }
            case "stackId" -> string(content, 1, 128, false);
            case "command", "entryPoint" -> {
                if (!(content instanceof List<?> values) || values.size() > 64) throw error("ResourceChangeInputInvalid");
                List<String> arguments = new ArrayList<>(); int length = 0;
                for (Object argument : values) {
                    string(argument, 0, 4096, true);
                    length += ((String) argument).length();
                    if (length > 16384) throw error("ResourceChangeInputInvalid");
                    arguments.add((String) argument);
                }
                content = List.copyOf(arguments);
            }
            case "scale" -> {
                if (!(content instanceof Number number) || number.doubleValue() != number.longValue()
                        || number.longValue() < 0 || number.longValue() > 100) throw error("ResourceChangeInputInvalid");
                content = number.intValue();
            }
            default -> throw error("ResourceChangeInputInvalid");
            }
            result.put(field, content);
        }
        if (createsResource() && (!result.containsKey("name") || !"stack".equals(resourceType) && !result.containsKey("image")
                || "service".equals(resourceType) && !result.containsKey("stackId"))) throw error("ResourceChangeInputInvalid");
        if ("update_metadata".equals(operation) && result.isEmpty()
                || "scale".equals(operation) && !result.containsKey("scale")) throw error("ResourceChangeInputInvalid");
        return result;
    }

    private static void string(Object value, int minimum, int maximum, boolean empty) {
        if (!(value instanceof String text) || text.length() < minimum || text.length() > maximum
                || !empty && text.isBlank() || text.indexOf('\0') >= 0) throw error("ResourceChangeInputInvalid");
    }

    public Map<String, Object> body(Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>(parameters);
        if (createsResource()) {
            body.put("type", resourceType);
            if (!"stack".equals(resourceType)) {
                String image = (String) body.remove("image");
                image = image.startsWith("docker:") ? image : "docker:" + image;
                body.put("startOnCreate", false);
                if ("service".equals(resourceType)) {
                    body.putIfAbsent("scale", 1);
                    Map<String, Object> launch = new LinkedHashMap<>(Map.of("imageUuid", image, "networkMode", "bridge"));
                    for (String field : List.of("command", "entryPoint")) if (body.containsKey(field)) launch.put(field, body.remove(field));
                    body.put("launchConfig", launch);
                } else {
                    body.put("imageUuid", image); body.put("networkMode", "bridge");
                }
            }
        } else if ("service".equals(resourceType) && "restart".equals(operation)) {
            body.put("rollingRestartStrategy", new LinkedHashMap<>(Map.of("batchSize", 1)));
        }
        return body;
    }

    static ClientVisibleException error(String code) { return new ClientVisibleException(422, code, code, null); }
}
