package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Host;
import io.cattle.platform.object.util.DataAccessor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Name/description only: agent configuration, credentials and host info never enter the plan. */
public final class AiHostMetadataChange {
    private AiHostMetadataChange() { }

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
