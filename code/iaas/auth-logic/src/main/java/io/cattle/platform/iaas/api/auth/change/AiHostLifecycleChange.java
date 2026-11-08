package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Native Host scheduling state only. No evacuation, daemon stop or agent action. */
public final class AiHostLifecycleChange {
    private AiHostLifecycleChange() { }

    public static boolean supports(AiResourceChangeDescriptor descriptor) {
        return "host".equals(descriptor.resourceType()) && Set.of("start", "stop").contains(descriptor.operation());
    }

    public static boolean host(Object value) {
        return value instanceof Host host && host.getId() != null && host.getId() > 0
                && host.getAccountId() != null && host.getAccountId() > 0 && host.getRemoved() == null
                && host.getUuid() != null && !host.getUuid().isBlank() && host.getKind() != null && !host.getKind().isBlank();
    }

    public static void ready(AiResourceChangeDescriptor descriptor, Object target) {
        if (!host(target)) throw new ClientVisibleException(422, "ResourceChangeUnsupported");
        if (!("start".equals(descriptor.operation()) ? "inactive" : "active").equals(ObjectUtils.getState(target)))
            throw new ClientVisibleException(409, "ResourceChangeTargetBusy");
    }

    public static Map<String, Object> snapshot(Object target, Account project, JsonMapper json) {
        if (!host(target)) throw new ClientVisibleException(422, "ResourceChangeUnsupported");
        Host host = (Host) target;
        if (project == null || !Objects.equals(project.getId(), host.getAccountId()) || !"project".equals(project.getKind())
                || project.getUuid() == null || project.getUuid().isBlank() || project.getRemoved() != null)
            throw new ClientVisibleException(403, "PermissionDenied");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "uuid", "accountId", "kind", "name", "description", "state", "removed",
                "physicalHostId", "zoneId", "agentId")) result.put(field, ObjectUtils.getPropertyIgnoreErrors(host, field));
        result.put("projectUuid", project.getUuid());
        // Native read-only observations change independently of scheduling state.
        // Everything else, including unknown configuration, remains a private CAS
        // operand. Never persist the raw Host/agent configuration in a plan.
        Map<String, Object> data = host.getData() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(host.getData());
        if (data.get("fields") instanceof Map<?, ?> fields) {
            Map<String, Object> stable = new LinkedHashMap<>();
            for (var entry : fields.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new ClientVisibleException(503, "ResourceChangeUnavailable");
                if (!Set.of("info", "agentState", "agentIpAddress", "publicEndpoints", "instanceIds").contains(key))
                    stable.put(key, entry.getValue());
            }
            data.put("fields", stable);
        }
        try {
            result.put("configurationSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(ordered(data)))));
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
        if (value instanceof List<?> list) return list.stream().map(AiHostLifecycleChange::ordered).toList();
        return value;
    }

    public static Map<String, Object> configuration(Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<>(snapshot); result.remove("state"); return result;
    }

    public static Map<String, Object> identity(Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "uuid", "accountId", "kind", "projectUuid")) result.put(field, snapshot.get(field));
        return result;
    }

    public static boolean complete(AiResourceChangeDescriptor descriptor, Object target, Map<String, Object> before,
            Account project, JsonMapper json) {
        if (!host(target) || before.isEmpty()) return false;
        Map<String, Object> current = snapshot(target, project, json);
        return configuration(before).equals(configuration(current))
                && ("start".equals(descriptor.operation()) ? "active" : "inactive").equals(ObjectUtils.getState(target));
    }

    public static boolean binding(Map<String, Object> binding) {
        return binding != null && "host".equals(binding.get("resourceType")) && "POST".equals(binding.get("method"))
                && "update".equals(binding.get("keyOperation"))
                && ("start".equals(binding.get("operation")) && "activate".equals(binding.get("action"))
                    || "stop".equals(binding.get("operation")) && "deactivate".equals(binding.get("action")));
    }

    public static boolean queuedRoot(Map<String, Object> binding, Object root, LaunchConfiguration config) {
        return binding(binding) && host(root) && root instanceof Host selected
                && Objects.equals(binding.get("rootUuid"), selected.getUuid())
                && AiAliasServiceChange.nativeId(binding.get("rootId")) == selected.getId()
                && AiAliasServiceChange.nativeId(binding.get("accountId")) == selected.getAccountId()
                && "host".equals(config.getResourceType()) && selected.getId().toString().equals(config.getResourceId())
                && ("host." + binding.get("action")).equals(config.getProcessName())
                && ("start".equals(binding.get("operation")) ? Set.of("inactive", "activating") : Set.of("active", "deactivating"))
                        .contains(Objects.toString(selected.getState(), ""));
    }
}
