package io.cattle.platform.api.auth;

/** Built only after signature verification and authenticated agent/host binding. */
public record ApiKeyDelegatedAuditEvent(String eventId, String keyId, long principalAccountId,
        long accountId, long policyRevision, String operation, String targetType, String targetId,
        String requestId, String outcome, String failureCode, String hostUuid) { }
