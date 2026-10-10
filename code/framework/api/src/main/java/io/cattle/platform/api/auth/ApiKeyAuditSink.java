package io.cattle.platform.api.auth;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

/** Durable admission of an API-key authorization decision, before execution. */
public interface ApiKeyAuditSink {
    void recordDecision(ApiRequest request, Policy policy);
    /** Unidentified authentication denial; never infer an owner from a public key. */
    default void recordAuthenticationDenied(ApiRequest request, String reason) {
        throw new IllegalStateException("Authentication denial auditing is unavailable");
    }
    default void recordDelegatedOutcome(ApiKeyDelegatedAuditEvent event) {
        throw new IllegalStateException("Delegated outcome auditing is unavailable");
    }
}
