package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ApiKeySubscriptionAuditEvent;
import io.cattle.platform.api.pubsub.subscribe.SubscriptionAuthorization;
import io.cattle.platform.api.pubsub.subscribe.SubscriptionCloseReason;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.inject.Inject;

/** Restricted streams recheck live authorization; terminal observers retain no Key secret or token. */
public class ApiKeySubscriptionAuthorization implements SubscriptionAuthorization {
    @Inject ApiKeyDelegationService delegations;
    @Inject List<ApiKeyAuditSink> auditSinks;
    private static final String TRACE = ApiKeySubscriptionAuthorization.class.getName() + ".trace";
    private static final Set<String> LIVE_FAILURES = Set.of("ApiKeyRevoked", "ApiKeyExpired", "ApiKeyPolicyChanged",
            "OwnerPermissionDenied", "KeyPolicyDenied");

    @Override public Lifecycle captureLifecycle(ApiRequest request) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null) return null;
        if (request.getAttribute(TRACE) instanceof Trace existing) return existing;
        Object rawPolicy = ApiContext.getContext().getPolicy();
        Object rawKeyId = request.getAttribute("apiKey.audit.keyId");
        Object rawRequestId = request.getAttribute("apiKey.audit.requestId");
        // Observability must not add a new FULL/legacy authorization restriction.
        // Only server-attached, verified metadata is retained, never the request.
        if (!(rawPolicy instanceof Policy owner) || owner.getAccountId() <= 0 || key.principalAccountId() <= 0
                || key.revision() < 0 || !(rawKeyId instanceof String keyId) || !keyId.matches("[A-Za-z0-9]{1,64}")
                || !(rawRequestId instanceof String requestId) || !uuid(requestId)) return null;
        Trace trace = new Trace(keyId, key.principalAccountId(), owner.getAccountId(), key.revision(), requestId,
                auditSinks == null ? List.of() : List.copyOf(auditSinks));
        request.setAttribute(TRACE, trace);
        return trace;
    }

    @Override public Session capture(ApiRequest request) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null || !key.restricted()) return null;
        Policy owner = (Policy) ApiContext.getContext().getPolicy();
        Map<String, Object> grant = Map.copyOf(delegations.metadata(key, owner.getAccountId(), request).entrySet().stream()
                .filter(entry -> entry.getValue() != null).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        Trace trace = request.getAttribute(TRACE) instanceof Trace captured ? captured : null;
        current(grant, trace); // Before any WebSocket/SSE writer upgrades.
        return event -> {
            var live = current(grant, trace);
            if ("ping".equals(event.getName())) return live.authorization().policy();
            if (event.getResourceType() == null || event.getResourceId() == null) return null;
            try {
                Object resource = delegations.load(event.getResourceType(), event.getResourceId());
                delegations.check(live, event.getResourceType(), resource, "read");
                return live.authorization().policy();
            } catch (io.github.ibuildthecloud.gdapi.exception.ClientVisibleException hidden) {
                return null; // A denied row never reaches event post-processors/body.
            }
        };
    }

    private ApiKeyDelegationService.LiveGrant current(Map<String, Object> grant, Trace trace) {
        try { return delegations.current(grant); }
        catch (ClientVisibleException denied) {
            if (trace != null) trace.authorizationFailed(denied.getCode());
            throw denied;
        }
    }

    private static boolean uuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static final class Trace implements Lifecycle {
        private final String keyId, requestId, eventId;
        private final long principalId, accountId, revision;
        private final List<ApiKeyAuditSink> sinks;
        private String failureCode;
        private ApiKeySubscriptionAuditEvent terminal;
        private boolean persisted;

        Trace(String keyId, long principalId, long accountId, long revision, String requestId, List<ApiKeyAuditSink> sinks) {
            this.keyId = keyId; this.principalId = principalId; this.accountId = accountId;
            this.revision = revision; this.requestId = requestId; this.sinks = sinks;
            try {
                eventId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(("subscription-terminal|" + requestId + "|" + keyId).getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Subscription audit identity is unavailable"); }
        }

        synchronized void authorizationFailed(String code) {
            if (failureCode == null && code != null && LIVE_FAILURES.contains(code)) failureCode = code;
        }

        @Override public synchronized void closed(SubscriptionCloseReason reason) {
            if (persisted) return;
            if (terminal == null) {
                terminal = new ApiKeySubscriptionAuditEvent(eventId, keyId, principalId, accountId, revision, requestId,
                        reason.name(), reason == SubscriptionCloseReason.AUTHORIZATION_FAILED ? failureCode : null);
            }
            if (sinks.isEmpty()) throw new IllegalStateException("Subscription audit sink is unavailable");
            // Persist the same first result on a retry; a later callback cannot
            // replace its cause, even if the first storage attempt failed.
            for (ApiKeyAuditSink sink : sinks) sink.recordSubscriptionTerminal(terminal);
            persisted = true;
        }
    }
}
