package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Ordinary/Catalog/system Stack closure through the existing native Stack action. */
final class AiStackUpgradeLifecycleChange {
    private static final Map<String, Set<String>> START = Map.of(
            "finish_upgrade", Set.of("upgraded"),
            "rollback", Set.of("upgrading", "upgraded", "canceled-upgrade"),
            "cancel_upgrade", Set.of("upgrading", "finishing-upgrade"));
    private static final List<String> STACK_FIELDS = List.of("externalId", "environment", "previousExternalId",
            "previousEnvironment", "dockerCompose", "rancherCompose", "answers", "templates", "binding");
    private static final List<String> CHILD_FIELDS = List.of("hostname", "externalIpAddresses", "lbConfig",
            "vip", "externalId", "system", "assignServiceIpAddress", "retainIp");

    private AiStackUpgradeLifecycleChange() { }

    static boolean supports(AiResourceChangeDescriptor descriptor) {
        return "stack".equals(descriptor.resourceType()) && START.containsKey(descriptor.operation());
    }

    static boolean binding(Map<String, Object> value) {
        return value != null && "stack".equals(value.get("resourceType")) && START.containsKey(value.get("operation"));
    }

    static String terminal(String operation) { return AiServiceUpgradeLifecycleChange.terminal(operation); }

    static Stack stack(Object target) {
        if (!(target instanceof Stack stack) || !"stack".equals(stack.getKind()) || stack.getRemoved() != null
                || stack.getId() == null || stack.getAccountId() == null || stack.getUuid() == null || stack.getUuid().isBlank()) {
            throw error(404, "NotFound");
        }
        return stack;
    }

    static void ready(AiResourceChangeDescriptor descriptor, Object target) {
        if (!START.get(descriptor.operation()).contains(Objects.toString(stack(target).getState(), ""))) {
            throw error(409, "ResourceChangeTargetBusy");
        }
    }

    /** Full private desired generation; no Compose text, child identity or configuration is public. */
    static Map<String, Object> snapshot(Object target, List<? extends Service> services, String projectUuid, JsonMapper json) {
        Stack stack = stack(target);
        if (projectUuid == null || projectUuid.isBlank()) { throw error(409, "ResourceChangePlanStale"); }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("projectUuid", projectUuid);
        result.put("id", stack.getId()); result.put("uuid", stack.getUuid()); result.put("accountId", stack.getAccountId());
        result.put("kind", stack.getKind()); result.put("name", stack.getName()); result.put("description", stack.getDescription());
        result.put("state", stack.getState()); result.put("system", stack.getSystem());
        for (String field : STACK_FIELDS) {
            result.put(field, "externalId".equals(field) ? stack.getExternalId() : DataAccessor.field(stack, field, Object.class));
        }
        List<Map<String, Object>> children = new ArrayList<>();
        Set<Long> ids = new java.util.HashSet<>();
        for (Service child : services) {
            if (child == null || child.getRemoved() != null || child.getId() == null || !ids.add(child.getId())
                    || child.getUuid() == null || child.getUuid().isBlank() || child.getKind() == null || child.getKind().isBlank()
                    || !Objects.equals(child.getStackId(), stack.getId()) || !Objects.equals(child.getAccountId(), stack.getAccountId())) {
                throw error(409, "ResourceChangePlanStale");
            }
            Map<String, Object> row = new LinkedHashMap<>(AiServiceUpgradeLifecycleChange.nativeSnapshot(child, json));
            for (String field : CHILD_FIELDS) {
                row.put(field, "externalId".equals(field) ? child.getExternalId() : DataAccessor.field(child, field, Object.class));
            }
            children.add(row);
        }
        children.sort(Comparator.comparingLong(row -> number(row.get("id")))); result.put("children", children);
        try { return json.readValue(json.writeValueAsString(result)); }
        catch (IOException invalid) { throw error(422, "ResourceChangeUnsupported"); }
    }

    static List<Map<String, Object>> changes(AiResourceChangeDescriptor descriptor, Object target, int count) {
        return List.of(change("operation", null, descriptor.operation()),
                change("state", stack(target).getState(), terminal(descriptor.operation())),
                change("affectedServiceCount", count, count),
                change("checkpointOnly", null, "cancel_upgrade".equals(descriptor.operation())));
    }

    static boolean configurationComplete(AiResourceChangeDescriptor descriptor, Map<String, Object> before,
            Map<String, Object> current) {
        String operation = descriptor.operation();
        if (!terminal(operation).equals(current.get("state"))) { return false; }
        for (String field : List.of("id", "uuid", "accountId", "projectUuid", "kind", "name", "description", "system")) {
            if (!before.containsKey(field) || !Objects.equals(before.get(field), current.get(field))) { return false; }
        }
        for (String field : STACK_FIELDS) {
            Object expected = before.get(field);
            if (!before.containsKey(field)) { return false; }
            if (!"cancel_upgrade".equals(operation)) {
                if (Set.of("previousExternalId", "previousEnvironment").contains(field)) { expected = null; }
                if ("rollback".equals(operation) && "externalId".equals(field)
                        && before.get("previousExternalId") instanceof String previous && !previous.isEmpty()) { expected = previous; }
                if ("rollback".equals(operation) && "environment".equals(field)
                        && before.get("previousEnvironment") instanceof Map<?, ?> previous && !previous.isEmpty()) { expected = previous; }
            }
            if (!Objects.equals(expected, current.get(field))) { return false; }
        }
        List<Map<String, Object>> expected = children(before), actual = children(current);
        if (expected == null || actual == null || expected.size() != actual.size()) { return false; }
        for (int i = 0; i < expected.size(); i++) {
            if (!childComplete(operation, expected.get(i), actual.get(i))) { return false; }
        }
        return true;
    }

    private static boolean childComplete(String operation, Map<String, Object> before, Map<String, Object> current) {
        if (!before.keySet().equals(current.keySet())) { return false; }
        Map<String, Object> expected = new LinkedHashMap<>(before), actual = new LinkedHashMap<>(current);
        String state = Objects.toString(before.get("state"), "");
        if ("cancel_upgrade".equals(operation)) {
            // Native Stack cancellation checkpoints only the parent. Worker progress is not child completion.
            expected.remove("state"); actual.remove("state");
        } else {
            if (settles(operation, state)) { expected.put("state", "active"); }
            if (restores(operation, state) && before.get("upgrade") instanceof Map<?, ?> upgrade
                    && upgrade.get("inServiceStrategy") instanceof Map<?, ?> strategy
                    && (strategy.get("previousLaunchConfig") != null || strategy.get("previousSecondaryLaunchConfigs") != null)) {
                expected.put("launchConfig", Objects.requireNonNullElse(strategy.get("previousLaunchConfig"), Map.of()));
                expected.put("secondaryLaunchConfigs", strategy.get("previousSecondaryLaunchConfigs"));
            }
        }
        return expected.equals(actual);
    }

    /** Mirror the native worker's wait/reload followed by its action predicate, without writing any child. */
    static boolean restores(String operation, String state) {
        return "rollback".equals(operation) && Set.of("upgraded", "upgrading", "canceled-upgrade").contains(state);
    }

    static boolean settles(String operation, String state) {
        return !"cancel_upgrade".equals(operation) && (Set.of("upgrading", "upgraded", "finishing-upgrade", "rolling-back").contains(state)
                || restores(operation, state));
    }

    static void validate(AiResourceChangeDescriptor descriptor, Map<String, Object> snapshot) {
        for (Map<String, Object> child : Objects.requireNonNull(children(snapshot))) {
            String state = Objects.toString(child.get("state"), "");
            if ("cancel_upgrade".equals(descriptor.operation())) continue;
            if (!Set.of("active", "inactive", "upgrading", "upgraded", "canceled-upgrade", "finishing-upgrade", "rolling-back").contains(state))
                throw error(409, "ResourceChangeTargetBusy");
            if (!settles(descriptor.operation(), state)) continue;
            // Link-migration strategies require their own native completion contract; never claim configuration-only proof.
            if (child.get("upgrade") instanceof Map<?, ?> upgrade && upgrade.get("toServiceStrategy") != null)
                throw error(422, "ResourceChangeUnsupported");
        }
    }

    static List<Map<String, Object>> children(Map<String, Object> snapshot) {
        if (!(snapshot.get("children") instanceof List<?> rows)) { return null; }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> values)) { return null; }
            Map<String, Object> item = new LinkedHashMap<>();
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)) { return null; }
                item.put(key, entry.getValue());
            }
            result.add(item);
        }
        return result;
    }

    private static long number(Object value) { return value instanceof Number number ? number.longValue() : -1; }

    private static Map<String, Object> change(String field, Object before, Object after) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("field", field);
        result.put("before", before); result.put("after", after); return result;
    }

    private static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
