package io.cattle.platform.iaas.api.auth.apikey;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Locale;
import java.util.Set;

/** Reviewed business operations, not HTTP-method guesses for arbitrary actions. */
public final class ApiKeyOperations {
    public static final Set<String> OPERATIONS = Set.of("read", "create", "update", "upgrade", "delete", "exec", "logs", "export");
    private static final Set<String> UPGRADES = Set.of("upgrade", "finishupgrade", "cancelupgrade", "rollback");
    private static final Set<String> UPDATES = Set.of("activate", "deactivate", "start", "stop", "restart", "rollingrestart", "restore", "update");
    private static final Set<String> DELETES = Set.of("remove", "purge", "delete");
    private static final Set<String> EXPORTS = Set.of("exportconfig", "dockercomposeconfig", "ranchercomposeconfig", "composeconfig", "config", "pem", "certificate", "download", "downloadconfig", "secretvalues", "dbdump");

    private ApiKeyOperations() { }

    public record Operation(String id, boolean registered) { }

    public static Operation of(ApiRequest request) {
        return of(request.getMethod(), request.getAction(), request.getLink());
    }

    public static Operation of(String method, String action, String link) {
        String named = action != null ? action : link;
        if (named != null) {
            String name = named.toLowerCase(Locale.ROOT);
            if (name.equals("execute") || name.equals("exec")) return known("exec");
            if (name.equals("logs") || name.equals("log")) return known("logs");
            if (EXPORTS.contains(name)) return known("export");
            if (action != null) {
                if (UPGRADES.contains(name)) return known("upgrade");
                if (UPDATES.contains(name)) return known("update");
                if (DELETES.contains(name)) return known("delete");
                return new Operation("action:" + name, false);
            }
        }
        if (method == null) return new Operation("unknown", false);
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> known("read");
            case "POST" -> known("create");
            case "PUT", "PATCH" -> known("update");
            case "DELETE" -> known("delete");
            default -> new Operation("method:" + method.toLowerCase(Locale.ROOT), false);
        };
    }

    private static Operation known(String id) { return new Operation(id, true); }
}
