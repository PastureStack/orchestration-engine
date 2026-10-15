package io.cattle.platform.iaas.api.auth.apikey;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reviewed business operations, not HTTP-method guesses for arbitrary actions. */
public final class ApiKeyOperations {
    public static final Set<String> OPERATIONS = Set.of("read", "create", "update", "upgrade", "delete", "exec", "logs", "export");
    private static final Set<String> UPGRADES = Set.of("upgrade", "finishupgrade", "cancelupgrade", "rollback");
    private static final Set<String> UPDATES = Set.of("activate", "deactivate", "start", "stop", "restart", "rollingrestart", "restore", "update");
    private static final Set<String> DELETES = Set.of("remove", "purge", "delete");
    private static final Set<String> EXPORTS = Set.of("exportconfig", "dockercomposeconfig", "ranchercomposeconfig", "composeconfig", "config", "pem", "certificate", "download", "downloadconfig", "secretvalues", "dbdump");
    // Bounded persisted-model pairs. Unknown, security-management and multi-root
    // actions remain unsupported; a plugin cannot borrow a familiar action name.
    // Source/acceptance boundaries: scripts/api-key-operation-inventory.json.
    private static final Map<String, String> RESOURCE_ACTIONS = Map.ofEntries(
            Map.entry("stack.addoutputs", "update"),
            Map.entry("stack.create", "create"),
            Map.entry("stack.error", "update"),
            Map.entry("service.continueupgrade", "upgrade"),
            Map.entry("service.create", "create"),
            Map.entry("container.create", "create"),
            Map.entry("container.allocate", "update"),
            Map.entry("container.deallocate", "update"),
            Map.entry("container.error", "update"),
            Map.entry("container.updatehealthy", "update"),
            Map.entry("container.updatereinitializing", "update"),
            Map.entry("container.updateunhealthy", "update"),
            Map.entry("volume.create", "create"),
            Map.entry("volume.allocate", "update"),
            Map.entry("volume.deallocate", "update"));

    private ApiKeyOperations() { }

    public record Operation(String id, boolean registered) { }

    public static Operation of(ApiRequest request) {
        return of(request.getMethod(), request.getAction(), request.getLink(), request.getType());
    }

    public static Operation of(String method, String action, String link) {
        return of(method, action, link, null);
    }

    public static Operation of(String method, String action, String link, String resourceType) {
        String verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
        // Match ResourceManagerRequestHandler's dispatch, not a selector that
        // its actual sink ignores. PUT/DELETE are mutations even with a link
        // or action in the URL; a GET link wins over an ignored action query.
        boolean invokesAction = "POST".equals(verb) && action != null;
        boolean invokesLink = ("GET".equals(verb) || "HEAD".equals(verb)) && link != null;
        String named = invokesAction ? action : invokesLink ? link : null;
        if (named != null) {
            String name = named.toLowerCase(Locale.ROOT);
            if (name.equals("execute") || name.equals("exec")) return known("exec");
            if (name.equals("logs") || name.equals("log")) return known("logs");
            if (EXPORTS.contains(name)) return known("export");
            if (invokesAction) {
                if (UPGRADES.contains(name)) return known("upgrade");
                if (UPDATES.contains(name)) return known("update");
                if (DELETES.contains(name)) return known("delete");
                // Only the existing canonical aliases instance/container and
                // environment/stack participate; derived schema names are not inferred.
                String type = ApiKeyQueryScopes.canonical(resourceType == null ? null : resourceType.toLowerCase(Locale.ROOT));
                String operation = RESOURCE_ACTIONS.get(type + "." + name);
                if (operation != null) return known(operation);
                return new Operation("action:" + name, false);
            }
        }
        if (method == null) return new Operation("unknown", false);
        return switch (verb) {
            case "GET", "HEAD", "OPTIONS" -> known("read");
            case "POST" -> known("create");
            case "PUT", "PATCH" -> known("update");
            case "DELETE" -> known("delete");
            default -> new Operation("method:" + method.toLowerCase(Locale.ROOT), false);
        };
    }

    private static Operation known(String id) { return new Operation(id, true); }
}
