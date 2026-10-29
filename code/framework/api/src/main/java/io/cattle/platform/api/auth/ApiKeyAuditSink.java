package io.cattle.platform.api.auth;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

/** Durable admission of an API-key authorization decision, before execution. */
public interface ApiKeyAuditSink {
    /** Canonical completion of the fixed audit-query owner, never a client report or a page admission. */
    default void recordAuditQueryOutcome(ApiRequest request, Policy policy, String queryId,
            String outcome, String reason, int count) {
        throw new IllegalStateException("Audit query outcome auditing is unavailable");
    }
    /** Catalog launch decisions contain identity/outcome metadata only, never answers, Compose or executor proof. */
    default void recordCatalogLaunchDecision(ApiRequest request, Policy policy, String action, String planId,
            Long stackId, boolean allowed, String reason) {
        throw new IllegalStateException("Catalog launch audit unavailable");
    }
    /** Fixed typed-resource decisions; never records parameters or private snapshots. */
    default void recordResourceChangeDecision(ApiRequest request, Policy policy, String action, String receiptId,
            String resourceType, Long resourceId, boolean allowed, String reason) {
        throw new IllegalStateException("Resource-change audit unavailable");
    }
    void recordDecision(ApiRequest request, Policy policy);
    default void recordClientToolEvent(Policy policy, ClientToolAuditEvent event) {
        throw new IllegalStateException("Client tool auditing is unavailable");
    }
    /** Request overload retains server-verified Key identity for client-reported evidence. */
    default void recordClientToolEvent(ApiRequest request, Policy policy, ClientToolAuditEvent event) {
        recordClientToolEvent(policy, event);
    }
    /** Collaboration admission; contains no launch configuration or credential payload. */
    default void recordServiceUpgradeDecision(ApiRequest request, Policy policy, String action,
            String planId, Long serviceId, boolean allowed, String reason) {
        throw new IllegalStateException("Service upgrade admission auditing is unavailable");
    }
    /** Description update decision; never records either description value. */
    default void recordServiceDescriptionDecision(ApiRequest request, Policy policy, String receiptId,
            Long serviceId, boolean allowed, String reason) {
        throw new IllegalStateException("Service description admission auditing is unavailable");
    }
    /** Unidentified authentication denial; never infer an owner from a public key. */
    default void recordAuthenticationDenied(ApiRequest request, String reason) {
        throw new IllegalStateException("Authentication denial auditing is unavailable");
    }
    default void recordDelegatedOutcome(ApiKeyDelegatedAuditEvent event) {
        throw new IllegalStateException("Delegated outcome auditing is unavailable");
    }
    default void recordSubscriptionTerminal(ApiKeySubscriptionAuditEvent event) {
        throw new IllegalStateException("Subscription terminal auditing is unavailable");
    }
}
