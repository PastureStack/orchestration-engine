package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.StoragePool;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Registry state only. Credential records and their payloads are never operands. */
public final class AiRegistryLifecycleChange {
    private AiRegistryLifecycleChange() { }

    public static boolean supports(AiResourceChangeDescriptor descriptor) {
        return "registry".equals(descriptor.resourceType()) && Set.of("start", "stop").contains(descriptor.operation());
    }

    /** Return the actual endpoint unchanged, or unknown. Never strip a secret into a plausible host. */
    public static String address(Object value) {
        if (!(value instanceof String text) || text.isEmpty() || text.length() > 260 || text.endsWith(":")
                || !text.matches("[A-Za-z0-9.\\[\\]:-]+")) return null;
        try {
            URI parsed = new URI("registry://" + text);
            return parsed.getHost() != null && parsed.getRawUserInfo() == null && parsed.getRawQuery() == null
                    && parsed.getRawFragment() == null && "".equals(parsed.getRawPath())
                    && (parsed.getPort() == -1 || parsed.getPort() > 0 && parsed.getPort() <= 65535) ? text : null;
        } catch (URISyntaxException invalid) { return null; }
    }

    public static boolean registry(Object value) {
        return value instanceof StoragePool pool && "registry".equals(pool.getKind())
                && pool.getId() != null && pool.getId() > 0 && pool.getAccountId() != null && pool.getAccountId() > 0
                && pool.getUuid() != null && !pool.getUuid().isBlank() && pool.getRemoved() == null;
    }

    public static Map<String, Object> snapshot(StoragePool pool) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : java.util.List.of("id", "uuid", "accountId", "kind", "name", "description", "state", "removed", "data",
                "external", "agentId", "zoneId", "externalId", "driverName", "volumeAccessMode", "storageDriverId"))
            result.put(field, ObjectUtils.getPropertyIgnoreErrors(pool, field));
        return result;
    }

    public static Map<String, Object> configuration(Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<>(snapshot); result.remove("state"); return result;
    }

    public static void ready(AiResourceChangeDescriptor descriptor, Object target) {
        if (!registry(target) || address(DataAccessor.field(target, "serverAddress", Object.class)) == null)
            throw new ClientVisibleException(422, "ResourceChangeUnsupported");
        if (!("start".equals(descriptor.operation()) ? "inactive" : "active").equals(ObjectUtils.getState(target)))
            throw new ClientVisibleException(409, "ResourceChangeTargetBusy");
    }

    public static boolean complete(AiResourceChangeDescriptor descriptor, Object target, Map<String, Object> before,
            Map<String, Object> current) {
        return registry(target) && !before.isEmpty() && configuration(before).equals(configuration(current))
                && ("start".equals(descriptor.operation()) ? "active" : "inactive").equals(ObjectUtils.getState(target));
    }

    /** The native API subtype is registry, but the sole backing root process is storagepool. */
    public static boolean queuedRoot(Object root, Object child, LaunchConfiguration config, Map<String, Object> metadata) {
        String action = Objects.toString(metadata.get("requestAction"), "");
        return registry(root) && registry(child) && root instanceof StoragePool selected && child instanceof StoragePool actual
                && Objects.equals(selected.getId(), actual.getId()) && Objects.equals(selected.getUuid(), actual.getUuid())
                && Objects.equals(selected.getAccountId(), actual.getAccountId()) && "storagePool".equals(config.getResourceType())
                && selected.getId().toString().equals(config.getResourceId()) && Set.of("activate", "deactivate").contains(action)
                && ("storagepool." + action).equals(config.getProcessName()) && "update".equals(metadata.get("operation"))
                && "POST".equals(metadata.get("requestMethod")) && Boolean.FALSE.equals(metadata.get("requestCollection"))
                && metadata.get("requestLink") == null;
    }
}
