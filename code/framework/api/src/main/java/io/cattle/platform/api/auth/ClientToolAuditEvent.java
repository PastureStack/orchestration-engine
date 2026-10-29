package io.cattle.platform.api.auth;

import java.util.Set;

/** Bounded client evidence, never proof of authorization or business completion. */
public record ClientToolAuditEvent(String requestId, String command, String phase,
        String status, String reason, String correlationId) {
    public static final Set<String> COMMANDS = Set.of("find_stacks", "find_services", "get_service",
            "preview_service_upgrade", "apply_service_upgrade", "get_operation", "update_service_description", "unknown");
    public static final Set<String> STATUSES = Set.of("pending", "succeeded", "accepted", "running",
            "failed", "unknown", "cancelled", "rejected", "needs_user");
    public static final Set<String> REASONS = Set.of("NONE", "INVALID_INPUT", "UNAVAILABLE", "CONTEXT_CHANGED",
            "STALE_REFERENCE", "FORBIDDEN", "SESSION_REQUIRED", "CONFLICT", "NEEDS_USER", "INVALID_RESPONSE",
            "UNKNOWN_RESULT", "CANCELLED");
    public static boolean validId(String value) {
        return value != null && value.matches("aitool-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    public boolean valid() {
        return validId(requestId) && command != null && COMMANDS.contains(command)
                && status != null && STATUSES.contains(status) && reason != null && REASONS.contains(reason)
                && (("start".equals(phase) && "pending".equals(status) && correlationId == null)
                    || ("result".equals(phase) && !"pending".equals(status) && validId(correlationId)));
    }
}
