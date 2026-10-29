package io.cattle.platform.api.auth;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

/** Durable admission of an API-key authorization decision, before execution. */
public interface ApiKeyAuditSink {
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
