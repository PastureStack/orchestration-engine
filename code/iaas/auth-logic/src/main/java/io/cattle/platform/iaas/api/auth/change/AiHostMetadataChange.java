package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Host;
import io.cattle.platform.object.util.DataAccessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Objects;

/** Name/description only: agent configuration, credentials and host info never enter the plan. */
public final class AiHostMetadataChange {
    private AiHostMetadataChange() { }

    public static boolean supports(AiResourceChangeDescriptor descriptor) {
        return "host".equals(descriptor.resourceType()) && "update_metadata".equals(descriptor.operation());
    }

    public static boolean resets(AiResourceChangeDescriptor descriptor, Map<String, Object> parameters) {
        return supports(descriptor) && Boolean.TRUE.equals(parameters.get("resetName"));
    }

    /** Explicit intent maps to the same empty custom-name field saved by the native Host editor. */
    public static Map<String, Object> body(Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>(parameters);
        if (body.containsKey("resetName")) {
            if (!Boolean.TRUE.equals(body.remove("resetName")) || body.containsKey("name"))
                throw AiResourceChangeDescriptor.error("ResourceChangeInputInvalid");
            body.put("name", "");
        }
        return body;
    }

    public static List<Map<String, Object>> resetChanges(Host host, Map<String, Object> parameters) {
        String hostname = DataAccessor.fieldString(host, "hostname");
        if (hostname == null || hostname.isBlank() || hostname.length() > 1024 || hostname.indexOf('\0') >= 0)
            throw AiResourceChangeDescriptor.error("ResourceChangeUnsupported");
        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(change("name", host.getName(), ""));
        changes.add(change("hostname", hostname, hostname)); // Read-only result context, never a writable field.
        if (parameters.containsKey("description")) changes.add(change("description", host.getDescription(), parameters.get("description")));
        return changes;
    }

    private static Map<String, Object> change(String field, Object before, Object after) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("field", field); row.put("before", before); row.put("after", after); return row;
    }

    public static boolean resetComplete(Host host, Map<String, Object> parameters, Map<String, Object> before) {
        Map<String, Object> current = snapshot(host);
        for (String field : List.of("id", "uuid", "accountId", "kind", "state", "removed", "hostname")) {
            if (!before.containsKey(field)) return false;
            if (Set.of("id", "accountId").contains(field)) {
                long expected = AiAliasServiceChange.nativeId(before.get(field));
                if (expected < 1 || expected != AiAliasServiceChange.nativeId(current.get(field))) return false;
            } else if (!Objects.equals(before.get(field), current.get(field))) return false;
        }
        return body(parameters).entrySet().stream().allMatch(entry -> Objects.equals(entry.getValue(), current.get(entry.getKey())));
    }

    public static Map<String, Object> snapshot(Host host) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", host.getId()); result.put("uuid", host.getUuid());
        result.put("accountId", host.getAccountId()); result.put("kind", host.getKind());
        result.put("name", host.getName()); result.put("description", host.getDescription());
        result.put("state", host.getState()); result.put("removed", host.getRemoved());
        result.put("hostname", DataAccessor.fieldString(host, "hostname"));
        return result;
    }

    public static String displayName(Host host) {
        String name = host.getName(), hostname = DataAccessor.fieldString(host, "hostname");
        if (name == null || name.isBlank()) return Objects.toString(hostname, "");
        return hostname == null || hostname.isBlank() || name.equals(hostname) ? name : hostname + " (" + name + ")";
    }
}
