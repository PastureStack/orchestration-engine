package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Existing ordinary-Service upgrade actions; no caller-supplied strategy or transport. */
final class AiServiceUpgradeLifecycleChange {
    private static final Map<String, Set<String>> START = Map.of(
            "finish_upgrade", Set.of("upgraded"),
            "rollback", Set.of("upgrading", "upgraded", "canceled-upgrade"),
            "cancel_upgrade", Set.of("upgrading", "finishing-upgrade"));

    private AiServiceUpgradeLifecycleChange() { }

    static boolean supports(AiResourceChangeDescriptor descriptor) {
        return "service".equals(descriptor.resourceType()) && START.containsKey(descriptor.operation());
    }

    static String terminal(String operation) {
        if (!START.containsKey(operation)) throw error(422, "ResourceChangeUnsupported");
        return "cancel_upgrade".equals(operation) ? "canceled-upgrade" : "active";
    }

    static Service service(Object target) {
        if (!(target instanceof Service service) || !"service".equals(service.getKind()) || service.getRemoved() != null
                || service.getUuid() == null || service.getUuid().isBlank()) throw error(404, "NotFound");
        return service;
    }

    static void ready(AiResourceChangeDescriptor descriptor, Object target, JsonMapper json) {
        Service service = service(target);
        if (!START.get(descriptor.operation()).contains(Objects.toString(service.getState(), "")))
            throw error(409, "ResourceChangeTargetBusy");
        Map<String, Object> snapshot = snapshot(service, json);
        Map<String, Object> upgrade = map(snapshot.get("upgrade"));
        if (upgrade.get("toServiceStrategy") != null || !(upgrade.get("inServiceStrategy") instanceof Map<?, ?>))
            throw error(422, "ResourceChangeUnsupported");
        Map<String, Object> strategy = map(upgrade.get("inServiceStrategy"));
        // Native legacy configurations need not carry a version. The complete
        // persisted strategy and configurations below form the private CAS generation.
        if (strategy.get("launchConfig") == null && list(strategy.get("secondaryLaunchConfigs")).isEmpty())
            throw error(422, "ResourceChangeUnsupported");
        if (strategy.get("launchConfig") != null) map(strategy.get("launchConfig"));
        for (Object value : list(strategy.get("secondaryLaunchConfigs"))) map(value);
        if ("rollback".equals(descriptor.operation()) && strategy.get("previousLaunchConfig") == null
                && strategy.get("previousSecondaryLaunchConfigs") == null) throw error(422, "ResourceChangeUnsupported");
    }

    /** Server-private CAS operands; never expose configuration, versions or fingerprints. */
    static Map<String, Object> snapshot(Object target, JsonMapper json) {
        Service service = service(target);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", service.getId()); result.put("uuid", service.getUuid());
        result.put("accountId", service.getAccountId()); result.put("kind", service.getKind());
        result.put("stackId", service.getStackId()); result.put("name", service.getName());
        result.put("description", service.getDescription()); result.put("state", service.getState());
        result.put("scale", DataAccessor.field(service, "scale", Object.class));
        result.put("selectorContainer", service.getSelectorContainer()); result.put("selectorLink", service.getSelectorLink());
        result.put("upgrade", DataAccessor.fieldMap(service, "upgrade"));
        result.put("launchConfig", DataAccessor.fieldMap(service, "launchConfig"));
        result.put("secondaryLaunchConfigs", DataAccessor.field(service, "secondaryLaunchConfigs", Object.class));
        try {
            String encoded = json.writeValueAsString(result);
            if (encoded.length() > 1024 * 1024) throw error(422, "ResourceChangeUnsupported");
            return map(json.readValue(encoded));
        } catch (IOException invalid) { throw error(422, "ResourceChangeUnsupported"); }
    }

    static List<Map<String, Object>> changes(AiResourceChangeDescriptor descriptor, Object target) {
        return List.of(change("operation", null, descriptor.operation()), change("state", service(target).getState(), terminal(descriptor.operation())));
    }

    static boolean configurationComplete(AiResourceChangeDescriptor descriptor, Object target, Map<String, Object> before, JsonMapper json) {
        Service service = service(target);
        if (!terminal(descriptor.operation()).equals(service.getState())) return false;
        Map<String, Object> current = snapshot(service, json);
        for (String field : List.of("id", "uuid", "accountId", "kind", "stackId", "name", "description", "scale",
                "selectorContainer", "selectorLink", "upgrade"))
            if (!Objects.equals(before.get(field), current.get(field))) return false;
        Map<String, Object> strategy = map(map(before.get("upgrade")).get("inServiceStrategy"));
        Object primary = "rollback".equals(descriptor.operation())
                ? Objects.requireNonNullElse(strategy.get("previousLaunchConfig"), Map.of()) : before.get("launchConfig");
        Object secondary = "rollback".equals(descriptor.operation())
                ? strategy.get("previousSecondaryLaunchConfigs") : before.get("secondaryLaunchConfigs");
        return Objects.equals(primary, current.get("launchConfig")) && Objects.equals(secondary, current.get("secondaryLaunchConfigs"));
    }

    private static List<?> list(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values)) throw error(422, "ResourceChangeUnsupported");
        return values;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) throw error(422, "ResourceChangeUnsupported");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var item : source.entrySet()) {
            if (!(item.getKey() instanceof String field)) throw error(422, "ResourceChangeUnsupported");
            result.put(field, item.getValue());
        }
        return result;
    }

    private static Map<String, Object> change(String field, Object before, Object after) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("field", field); result.put("before", before); result.put("after", after); return result;
    }

    private static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
