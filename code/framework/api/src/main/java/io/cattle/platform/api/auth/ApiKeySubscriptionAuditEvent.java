package io.cattle.platform.api.auth;

/** Secret-free trace of an actual server-side subscription; no host ticket or client payload. */
public record ApiKeySubscriptionAuditEvent(String eventId, String keyId, long principalAccountId,
        long accountId, long policyRevision, String requestId, String closeReason, String authorizationFailureCode) { }
