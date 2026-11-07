package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Native Stack group text, with server-private identity and configuration CAS. */
public final class AiStackGroupChange {
    private AiStackGroupChange() { }

    public static boolean supports(AiResourceChangeDescriptor descriptor, Map<String, Object> parameters) {
        return "stack".equals(descriptor.resourceType()) && "update_metadata".equals(descriptor.operation())
                && parameters.containsKey("group");
    }

    public static void value(Object value) {
        if (value != null && (!(value instanceof String text) || text.length() > 255 || text.indexOf('\0') >= 0))
            throw AiResourceChangeDescriptor.error("ResourceChangeInputInvalid");
    }

    public static Stack ordinary(Object target) {
        if (!(target instanceof Stack stack) || stack.getId() == null || stack.getId() <= 0
                || stack.getAccountId() == null || stack.getAccountId() <= 0 || !"stack".equals(stack.getKind())
                || stack.getUuid() == null || stack.getUuid().isBlank() || stack.getRemoved() != null
                || !Boolean.FALSE.equals(stack.getSystem())
                || stack.getExternalId() != null && !stack.getExternalId().isEmpty())
            throw new ClientVisibleException(422, "ResourceChangeUnsupported");
        return stack;
    }

    public static Map<String, Object> snapshot(Object target, Account project, JsonMapper json) {
        Stack stack = ordinary(target);
        if (project == null || !Objects.equals(stack.getAccountId(), project.getId()) || !"project".equals(project.getKind())
                || project.getUuid() == null || project.getUuid().isBlank() || project.getRemoved() != null)
            throw new ClientVisibleException(403, "PermissionDenied");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", stack.getId()); result.put("uuid", stack.getUuid()); result.put("accountId", stack.getAccountId());
        result.put("kind", stack.getKind()); result.put("projectUuid", project.getUuid());
        result.put("name", stack.getName()); result.put("description", stack.getDescription()); result.put("group", stack.getGroup());
        result.put("state", stack.getState()); result.put("removed", stack.getRemoved());
        result.put("system", stack.getSystem()); result.put("externalId", stack.getExternalId());
        // Configuration can contain Compose/environment material. Persist only its CAS digest, never the material.
        try {
            result.put("dataSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(ordered(stack.getData())))));
            return json.readValue(json.writeValueAsBytes(result));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new ClientVisibleException(503, "ResourceChangeUnavailable");
        }
    }

    private static Object ordered(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new ClientVisibleException(503, "ResourceChangeUnavailable");
                sorted.put(key, ordered(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(AiStackGroupChange::ordered).toList();
        return value;
    }

    public static Map<String, Object> identity(Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "uuid", "accountId", "kind", "projectUuid")) result.put(field, snapshot.get(field));
        return result;
    }

    public static boolean complete(Object target, Map<String, Object> parameters, Map<String, Object> before,
            Account project, JsonMapper json) {
        Map<String, Object> current = snapshot(target, project, json), expected = new LinkedHashMap<>(before);
        expected.putAll(parameters);
        return current.equals(expected);
    }
}
