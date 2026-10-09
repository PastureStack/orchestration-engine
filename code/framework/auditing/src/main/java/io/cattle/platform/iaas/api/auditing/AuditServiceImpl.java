package io.cattle.platform.iaas.api.auditing;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ApiKeyDelegatedAuditEvent;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.constants.ContainerEventConstants;
import io.cattle.platform.core.constants.ExternalEventConstants;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.engine.process.ProcessAuthorizationHook;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ExitReason;
import io.cattle.platform.engine.process.ProcessResult;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.ApiRequestCompletionSink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import io.cattle.platform.util.type.InitializationTask;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuditServiceImpl implements AuditService, ApiKeyAuditSink, ApiRequestCompletionSink, ProcessAuthorizationHook, InitializationTask {

    public static final String API_KEY_AUDIT_PREFIX = "apiKey.audit.";
    public static final Set<String> API_KEY_AUDIT_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "eventId", "keyId", "decision", "outcome", "httpStatus", "responseCode", "requestId", "actor",
            "targetType", "targetId", "operation", "policyRevision", "reason", "phase", "preview", "processId", "processName", "hostUuid", "failureCode")));
    volatile ApiKeyAuditOutbox apiKeyOutbox;

    @Inject
    ScheduledExecutorService executor;

    @Override
    public void start() {
        executor.scheduleWithFixedDelay(() -> {
            try {
                outbox().replayPending(this::deliverApiKeyEvent);
            } catch (RuntimeException unavailable) {
                // Deliberately omit cause/value data from logs.
                log.warn("API-key audit replay remains unavailable");
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    public static final Set<String> BLACK_LIST_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "publish",
                    "configContent".toLowerCase(),
                    "externalHandler".toLowerCase(),
                    "externalService".toLowerCase(),
                    "hostApiProxyToken".toLowerCase(),
                    "token",
                    "scripts",
                    "serviceEvent".toLowerCase(),
                    "userPreference".toLowerCase(),
                    "dynamicSchema".toLowerCase(),
                    ContainerEventConstants.CONTAINER_EVENT_KIND.toLowerCase(),
                    ExternalEventConstants.KIND_EXTERNAL_EVENT.toLowerCase(),
                    ExternalEventConstants.KIND_SERVICE_EVENT.toLowerCase(),
                    ExternalEventConstants.KIND_VOLUME_EVENT.toLowerCase(),
                    ExternalEventConstants.KIND_STORAGE_POOL_EVENT.toLowerCase()
            )));

    @Inject
    AuditLogDao auditLogDao;

    @Inject
    JsonMapper jsonMapper;

    @Inject
    EventService eventService;

    @Inject
    ObjectManager objectManager;

    @Inject
    IdFormatter idFormatter;

    private static final Logger log = LoggerFactory.getLogger(AuditLogsRequestHandler.class);

    @Override
    public void logRequest(ApiRequest request, Policy policy) {
        if (isAuthenticationFailure(request)) {
            if (!Boolean.TRUE.equals(request.getAttribute(API_KEY_AUDIT_PREFIX + "responseRecorded"))) {
                logAuthenticationDenied(request, "response");
                request.setAttribute(API_KEY_AUDIT_PREFIX + "responseRecorded", Boolean.TRUE);
            }
            return;
        }
        if (isApiKeyRequest(request)) {
            if (!Boolean.TRUE.equals(request.getAttribute(API_KEY_AUDIT_PREFIX + "responseRecorded"))) {
                logApiKeyEvent(request, policy, "response");
                request.setAttribute(API_KEY_AUDIT_PREFIX + "responseRecorded", Boolean.TRUE);
            }
            return;
        }
        if (policy == null || request == null || request.getType() == null) {
            return;
        }

        if (isApiKeyGovernanceRequest(request)) {
            if (!Boolean.TRUE.equals(request.getAttribute(API_KEY_AUDIT_PREFIX + "governanceRecorded"))) {
                logApiKeyGovernance(request, policy);
                request.setAttribute(API_KEY_AUDIT_PREFIX + "governanceRecorded", Boolean.TRUE);
            }
            return;
        }

        if (Schema.Method.GET.isMethod(request.getMethod()) ||
                BLACK_LIST_TYPES.contains(request.getType().toLowerCase())) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        if (!"apikey".equalsIgnoreCase(request.getType()) && !"apikeyrestricted".equalsIgnoreCase(request.getType())
                && !StringUtils.startsWithIgnoreCase(request.getType(), "apiKeyDelegation")) {
            putInAsString(data, request.getType(), "requestObject", "Failed to convert request object to json.", request.getRequestObject());
            putInAsString(data, request.getType(), "responseObject", "Failed to convert response object to json.", request.getResponseObject());
        }
        data.put("responseCode", request.getResponseCode());
        Identity user = auditIdentity(policy.getIdentities());
        long runtime = ((long) request.getAttribute("requestEndTime")) - ((long)request.getAttribute("requestStartTime"));
        String authType = (String) request.getAttribute(AccountConstants.AUTH_TYPE);
        String resourceId =request.getResponseObject() instanceof Resource ? ((Resource) request.getResponseObject()).getId(): null;
        String resourceType = request.getType();
        String eventType = "api." + convertResourceType(resourceType) + "." + (StringUtils.isNotBlank(request.getAction()) ?
                request.getAction() :convertToAction(request.getMethod()));
        auditLogDao.create(resourceType, parseId(resourceId), data, user,
                policy.getAccountId(), policy.getAuthenticatedAsAccountId(), eventType, authType, runtime, null,
                request.getClientIp());
    }

    public static boolean isApiKeyRequest(ApiRequest request) {
        return request != null && request.getAttribute(API_KEY_AUDIT_PREFIX + "keyId") instanceof String;
    }

    public static boolean isApiKeyGovernanceRequest(ApiRequest request) {
        return request != null && ("apiKey".equalsIgnoreCase(request.getType())
                || "apiKeyRestricted".equalsIgnoreCase(request.getType()))
                && (Schema.Method.POST.isMethod(request.getMethod()) || Schema.Method.PUT.isMethod(request.getMethod())
                || Schema.Method.DELETE.isMethod(request.getMethod()));
    }

    private void logApiKeyGovernance(ApiRequest request, Policy policy) {
        // This is the managed Key, not a credential used to authenticate the caller.
        // Never infer it from an input payload (including a forged accountId/id).
        String targetId = metadataValue(request.getId(), "");
        if (request.getResponseObject() instanceof Resource resource
                && ("apiKey".equalsIgnoreCase(resource.getType()) || "apiKeyRestricted".equalsIgnoreCase(resource.getType()))) {
            targetId = metadataValue(resource.getId(), targetId);
        }
        String requestId = metadataValue(request.getAttribute(API_KEY_AUDIT_PREFIX + "requestId"), "");
        if (requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(API_KEY_AUDIT_PREFIX + "requestId", requestId);
        }
        int status = request.getResponseCode();
        boolean denied = status == 401 || status == 403;
        String operation = metadataValue(request.getAction(), "");
        if (operation.isEmpty()) operation = convertToAction(request.getMethod()).name();
        Map<String, Object> data = new HashMap<>();
        data.put("eventId", UUID.randomUUID().toString());
        if (!targetId.isEmpty()) data.put("keyId", targetId);
        data.put("requestId", requestId);
        data.put("actor", formattedAccountId(policy.getAuthenticatedAsAccountId()));
        data.put("targetType", "apiKey");
        data.put("targetId", targetId);
        if (request.getResponseObject() instanceof Resource resource
                && resource.getFields().get("apiKeyPolicyRevision") instanceof Number revision && revision.longValue() >= 0) {
            data.put("policyRevision", revision.longValue());
        }
        data.put("operation", operation);
        data.put("phase", "response");
        data.put("decision", denied ? "DENY" : "ALLOW");
        data.put("outcome", denied ? "DENIED" : status == 202 ? "ACCEPTED"
                : status >= 200 && status < 400 ? "SUCCEEDED" : "FAILED");
        String actualOutcome = metadataValue(request.getAttribute(API_KEY_AUDIT_PREFIX + "actualOutcome"), "");
        if (!denied && Set.of("FAILED", "CANCELED").contains(actualOutcome)) data.put("outcome", actualOutcome);
        data.put("reason", denied ? "KeyGovernanceDenied" : status >= 200 && status < 400
                ? "KeyGovernanceCompleted" : "KeyGovernanceFailed");
        data.put("httpStatus", status);
        data.put("responseCode", status);
        data.put("preview", false);
        Map<String, Object> event = new HashMap<>();
        event.put("resourceType", request.getType());
        event.put("resourceId", parseId(targetId));
        event.put("accountId", governanceAccount(targetId, policy));
        event.put("authenticatedAsAccountId", policy.getAuthenticatedAsAccountId());
        event.put("eventType", "api.apiKey." + operation);
        event.put("authType", metadataValue(request.getAttribute(AccountConstants.AUTH_TYPE), ""));
        event.put("runtime", runtime(request));
        event.put("clientIp", metadataValue(request.getClientIp(), ""));
        event.put("data", data);
        outbox().persist(event, this::deliverApiKeyEvent);
    }

    private long governanceAccount(String targetId, Policy policy) {
        Long credentialId = parseId(targetId);
        if (credentialId != null && objectManager != null) {
            Credential credential = objectManager.loadResource(Credential.class, credentialId);
            if (credential != null && credential.getAccountId() != null && credential.getAccountId() > 0
                    && (CredentialConstants.KIND_API_KEY.equals(credential.getKind())
                    || CredentialConstants.KIND_API_KEY_RESTRICTED.equals(credential.getKind()))) {
                // The target's durable account governs audit visibility; the
                // authenticated actor remains separate. Never trust an input
                // accountId or widen the viewer's account authorization.
                return credential.getAccountId();
            }
        }
        return policy.getAccountId();
    }

    private static boolean isAuthenticationFailure(ApiRequest request) {
        return request != null && Boolean.TRUE.equals(request.getAttribute(API_KEY_AUDIT_PREFIX + "authenticationFailed"));
    }

    @Override
    public void recordAuthenticationDenied(ApiRequest request, String reason) {
        if (request == null || reason == null || !reason.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalStateException("Invalid authentication denial metadata");
        }
        if (Boolean.TRUE.equals(request.getAttribute(API_KEY_AUDIT_PREFIX + "admitted"))) return;
        request.setAttribute(API_KEY_AUDIT_PREFIX + "authenticationFailed", Boolean.TRUE);
        request.setAttribute(API_KEY_AUDIT_PREFIX + "reason", reason);
        logAuthenticationDenied(request, "decision");
        request.setAttribute(API_KEY_AUDIT_PREFIX + "admitted", Boolean.TRUE);
    }

    private void logAuthenticationDenied(ApiRequest request, String phase) {
        String requestId = metadataValue(request.getAttribute(API_KEY_AUDIT_PREFIX + "requestId"), "");
        if (requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(API_KEY_AUDIT_PREFIX + "requestId", requestId);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("eventId", UUID.randomUUID().toString());
        data.put("requestId", requestId);
        data.put("actor", "anonymous");
        data.put("decision", "DENY");
        data.put("outcome", "AUTHENTICATION_DENIED");
        data.put("phase", phase);
        data.put("reason", metadataValue(request.getAttribute(API_KEY_AUDIT_PREFIX + "reason"), "AuthenticationDenied"));
        data.put("operation", "authenticate");
        data.put("targetType", "authentication");
        data.put("targetId", "");
        int status = "decision".equals(phase) ? 0 : request.getResponseCode();
        data.put("httpStatus", status);
        data.put("responseCode", status);
        data.put("preview", false);
        Map<String, Object> event = new HashMap<>();
        event.put("resourceType", "authentication");
        event.put("eventType", "api.authentication.denied");
        event.put("authType", "BasicAuth");
        event.put("runtime", runtime(request));
        event.put("clientIp", "");
        event.put("data", data);
        try {
            outbox().persist(event, this::deliverApiKeyEvent);
        } catch (RuntimeException unavailable) {
            request.setAttribute(API_KEY_AUDIT_PREFIX + "persistenceFailed", Boolean.TRUE);
            throw unavailable;
        }
    }

    @Override
    public void recordDecision(ApiRequest request, Policy policy) {
        if (!isApiKeyRequest(request) || Boolean.TRUE.equals(request.getAttribute(API_KEY_AUDIT_PREFIX + "admitted"))) {
            return;
        }
        logApiKeyEvent(request, policy, "decision");
        request.setAttribute(API_KEY_AUDIT_PREFIX + "admitted", Boolean.TRUE);
    }

    @Override
    public void complete(ApiRequest request, Throwable failure) {
        if (!isApiKeyRequest(request) && !isAuthenticationFailure(request) && !isApiKeyGovernanceRequest(request)) {
            return;
        }
        request.setAttribute("requestEndTime", System.currentTimeMillis());
        if (failure != null) {
            request.setAttribute(API_KEY_AUDIT_PREFIX + "actualOutcome", failure instanceof EOFException ? "CANCELED" : "FAILED");
        }
        Policy policy = ApiContext.getContext() == null ? null : (Policy) ApiContext.getContext().getPolicy();
        logRequest(request, policy);
    }

    @Override
    public void recordDelegatedOutcome(ApiKeyDelegatedAuditEvent terminal) {
        if (terminal == null || terminal.eventId() == null || !terminal.eventId().matches("[a-f0-9]{64}")
                || terminal.principalAccountId() <= 0 || terminal.accountId() <= 0 || terminal.policyRevision() < 0
                || !Set.of("exec", "logs", "read").contains(terminal.operation())
                || !Set.of("SUCCEEDED", "FAILED", "CANCELLED", "CANCELED").contains(terminal.outcome())) {
            throw new IllegalStateException("Invalid delegated outcome metadata");
        }
        if (terminal.failureCode() != null && !Set.of("HandshakeDenied", "DockerFailure", "StreamFailed", "AuditUnavailable", "BackendAuditCapabilityUnavailable", "DelegationRouteDenied",
                "AuthorizationRevoked", "ClientDisconnected", "StreamCancelled").contains(terminal.failureCode())) {
            throw new IllegalStateException("Invalid delegated outcome code");
        }
        boolean routeDenied = "DelegationRouteDenied".equals(terminal.failureCode());
        boolean blockedHandshake = routeDenied || "BackendAuditCapabilityUnavailable".equals(terminal.failureCode());
        if (blockedHandshake && !"FAILED".equals(terminal.outcome())) {
            throw new IllegalStateException("Invalid blocked handshake outcome");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("eventId", terminal.eventId());
        data.put("keyId", requiredMetadata(terminal.keyId()));
        data.put("requestId", requiredMetadata(terminal.requestId()));
        data.put("operation", terminal.operation());
        data.put("targetType", requiredMetadata(terminal.targetType()));
        data.put("targetId", requiredMetadata(terminal.targetId()));
        data.put("hostUuid", requiredMetadata(terminal.hostUuid()));
        data.put("policyRevision", terminal.policyRevision());
        data.put("actor", formattedAccountId(terminal.principalAccountId()));
        // Keep ordinary operational failure distinct from authorization denial.
        // A wrong-route handshake is denied; the original ticket admission remains
        // its own ALLOW event and is never rewritten as execution or success.
        data.put("decision", routeDenied ? "DENY" : "ALLOW");
        data.put("outcome", "CANCELLED".equals(terminal.outcome()) ? "CANCELED" : terminal.outcome());
        data.put("phase", blockedHandshake ? "handshake" : "completion");
        data.put("reason", terminal.failureCode() == null ? "StreamCompleted" : terminal.failureCode());
        if (terminal.failureCode() != null) data.put("failureCode", terminal.failureCode());
        data.put("preview", false);
        int handshakeStatus = routeDenied ? 403 : blockedHandshake ? 503 : 0;
        data.put("httpStatus", handshakeStatus); // Verified proxy status, otherwise a host event.
        data.put("responseCode", handshakeStatus);
        Map<String, Object> event = new HashMap<>();
        event.put("delegatedEventId", terminal.eventId());
        event.put("resourceType", data.get("targetType"));
        event.put("resourceId", parseId(terminal.targetId()));
        event.put("accountId", terminal.accountId());
        event.put("authenticatedAsAccountId", terminal.principalAccountId());
        event.put("eventType", "api." + terminal.targetType() + "." + terminal.operation());
        event.put("authType", "BasicAuth");
        event.put("runtime", 0);
        event.put("clientIp", "");
        event.put("data", data);
        outbox().persist(event, this::deliverApiKeyEvent);
    }

    private static String requiredMetadata(String value) {
        String safe = metadataValue(value, "");
        if (safe.isEmpty()) throw new IllegalStateException("Invalid delegated outcome metadata");
        return safe;
    }

    @Override
    public boolean recordsCompletion() { return true; }

    @Override
    public void afterExecution(LaunchConfiguration config, Map<String, Object> metadata, ExitReason reason) {
        if (metadata.isEmpty() || reason == null || reason == ExitReason.SCHEDULED) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        for (String name : Arrays.asList("keyId", "requestId", "operation", "targetType", "targetId", "policyRevision")) {
            data.put(name, metadataValue(metadata.get(name), ""));
        }
        String targetId = String.valueOf(data.get("targetId"));
        if (targetId.matches("[0-9]+"))
            data.put("targetId", String.valueOf(idFormatter.formatId(String.valueOf(data.get("targetType")), targetId)));
        Number principal = (Number) metadata.get("principalAccountId");
        Number account = (Number) metadata.get("accountId");
        if (principal == null || account == null) {
            throw new IllegalStateException("Process audit principal is unavailable");
        }
        data.put("eventId", UUID.randomUUID().toString());
        data.put("actor", formattedAccountId(principal.longValue()));
        boolean denied = reason == ExitReason.AUTHORIZATION_DENIED;
        data.put("decision", denied ? "DENY" : "ALLOW");
        data.put("reason", reason.name());
        data.put("phase", reason == ExitReason.CHAIN ? "continuation" : reason.isTerminating() ? "completion" : "attempt");
        data.put("outcome", denied ? "DENIED" : reason == ExitReason.CHAIN ? "ACCEPTED"
                : reason.getResult() == ProcessResult.SUCCESS ? "SUCCEEDED" : "FAILED");
        data.put("preview", Boolean.TRUE.equals(metadata.get("preview")));
        data.put("httpStatus", 0);
        data.put("responseCode", 0);
        data.put("processId", config instanceof ProcessRecord record && record.getId() != null ? record.getId().toString() : "");
        data.put("processName", metadataValue(config.getProcessName(), ""));
        Map<String, Object> event = new HashMap<>();
        event.put("resourceType", data.get("targetType"));
        event.put("resourceId", parseId(String.valueOf(data.get("targetId"))));
        event.put("accountId", account.longValue());
        event.put("authenticatedAsAccountId", principal.longValue());
        event.put("eventType", "api." + data.get("targetType") + "." + data.get("operation"));
        event.put("authType", "BasicAuth");
        event.put("runtime", 0);
        event.put("clientIp", "");
        event.put("data", data);
        outbox().persist(event, this::deliverApiKeyEvent);
    }

    protected void logApiKeyEvent(ApiRequest request, Policy policy, String phase) {
        Number verifiedPrincipal = verifiedAccount(request, "verifiedPrincipalAccountId");
        Number verifiedAccount = verifiedAccount(request, "verifiedAccountId");
        if (policy == null && (verifiedPrincipal == null || verifiedAccount == null
                || "ALLOW".equals(request.getAttribute(API_KEY_AUDIT_PREFIX + "decision")))) {
            throw new IllegalStateException("API-key audit principal is unavailable");
        }
        Map<String, Object> data = apiKeyAuditData(request, policy, phase);
        String type = String.valueOf(data.get("targetType"));
        Map<String, Object> event = new HashMap<>();
        event.put("resourceType", type);
        event.put("resourceId", parseId(String.valueOf(data.get("targetId"))));
        event.put("accountId", policy == null ? verifiedAccount.longValue() : policy.getAccountId());
        event.put("authenticatedAsAccountId", policy == null ? verifiedPrincipal.longValue() : policy.getAuthenticatedAsAccountId());
        event.put("eventType", "api." + convertResourceType(type) + "." + data.get("operation"));
        event.put("authType", metadataValue(request.getAttribute(AccountConstants.AUTH_TYPE), "BasicAuth"));
        event.put("runtime", runtime(request));
        event.put("clientIp", metadataValue(request.getClientIp(), ""));
        event.put("data", data);
        try {
            outbox().persist(event, this::deliverApiKeyEvent);
        } catch (RuntimeException unavailable) {
            request.setAttribute(API_KEY_AUDIT_PREFIX + "persistenceFailed", Boolean.TRUE);
            throw unavailable;
        }
    }

    protected Map<String, Object> apiKeyAuditData(ApiRequest request, Policy policy, String phase) {
        Map<String, Object> data = new HashMap<>();
        data.put("eventId", UUID.randomUUID().toString());
        for (String name : Arrays.asList("keyId", "decision", "operation", "targetType", "targetId", "policyRevision", "reason")) {
            data.put(name, metadataValue(request.getAttribute(API_KEY_AUDIT_PREFIX + name), ""));
        }
        if (policy == null || String.valueOf(data.get("decision")).isEmpty()) {
            data.put("decision", "DENY");
            if (String.valueOf(data.get("reason")).isEmpty()) {
                data.put("reason", "authorization_not_completed");
            }
        }
        if (String.valueOf(data.get("targetType")).isEmpty()) {
            data.put("targetType", metadataValue(request.getType(), "unknown"));
        }
        if (String.valueOf(data.get("targetId")).isEmpty()) {
            // A create has no request resource ID. The response Resource is a
            // server-produced envelope; take only its ID, never its payload.
            Object targetId = request.getId();
            if (!"decision".equals(phase) && targetId == null && "POST".equals(request.getMethod())
                    && request.getAction() == null && request.getResponseCode() >= 200 && request.getResponseCode() < 300
                    && request.getResponseObject() instanceof Resource resource
                    && String.valueOf(data.get("targetType")).equals(resource.getType())) {
                targetId = resource.getId();
            }
            data.put("targetId", metadataValue(targetId, ""));
        }
        String requestId = metadataValue(request.getAttribute(API_KEY_AUDIT_PREFIX + "requestId"), "");
        if (requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(API_KEY_AUDIT_PREFIX + "requestId", requestId);
        }
        data.put("requestId", requestId);
        Number verifiedPrincipal = verifiedAccount(request, "verifiedPrincipalAccountId");
        data.put("actor", formattedAccountId(policy == null ? verifiedPrincipal.longValue() : policy.getAuthenticatedAsAccountId()));
        data.put("phase", phase);
        boolean preview = Boolean.TRUE.equals(request.getAttribute(API_KEY_AUDIT_PREFIX + "preview"));
        data.put("preview", preview);
        int status = "decision".equals(phase) ? 0 : request.getResponseCode();
        data.put("httpStatus", status);
        data.put("responseCode", status);
        String outcome = "decision".equals(phase) ? "PENDING" : status == 202 ? "ACCEPTED"
                : status >= 200 && status < 400 ? "SUCCEEDED" : "FAILED";
        if (!"decision".equals(phase)) {
            String actualOutcome = metadataValue(request.getAttribute(API_KEY_AUDIT_PREFIX + "actualOutcome"), "");
            if ("FAILED".equals(actualOutcome) || "CANCELED".equals(actualOutcome) || "ACCEPTED".equals(actualOutcome)) {
                outcome = actualOutcome;
            }
        }
        if ("DENY".equals(data.get("decision"))) {
            outcome = "DENIED";
        } else if (preview) {
            outcome = "NOT_EXECUTED";
        }
        data.put("outcome", outcome);
        return data;
    }

    private static Number verifiedAccount(ApiRequest request, String name) {
        Object value = request.getAttribute(API_KEY_AUDIT_PREFIX + name);
        return value instanceof Number number && number.longValue() > 0 ? number : null;
    }

    private String formattedAccountId(long accountId) {
        IdFormatter formatter = ApiContext.getContext() != null ? ApiContext.getContext().getIdFormatter() : idFormatter;
        return formatter == null ? String.valueOf(accountId) : String.valueOf(formatter.formatId("account", accountId));
    }

    private static String metadataValue(Object value, String fallback) {
        if (!(value instanceof String) && !(value instanceof Number)) {
            return fallback;
        }
        String text = value.toString();
        // These are server-issued identifiers/codes, never headers or payloads.
        if (text.length() > 512 || text.chars().anyMatch(character -> character < 32 || character == 127)) {
            return fallback;
        }
        return text;
    }

    private long runtime(ApiRequest request) {
        Object start = request.getAttribute("requestStartTime");
        Object end = request.getAttribute("requestEndTime");
        long startTime = start instanceof Number ? ((Number) start).longValue() : request.getStartTime();
        long endTime = end instanceof Number ? ((Number) end).longValue() : System.currentTimeMillis();
        return Math.max(0, endTime - startTime);
    }

    private ApiKeyAuditOutbox outbox() {
        if (apiKeyOutbox == null) {
            synchronized (this) {
                if (apiKeyOutbox == null) {
                    apiKeyOutbox = new ApiKeyAuditOutbox(Paths.get(System.getProperty("pasturestack.audit.outbox.dir",
                            "data/api-key-audit-outbox")), jsonMapper);
                }
            }
        }
        return apiKeyOutbox;
    }

    @SuppressWarnings("unchecked")
    private void deliverApiKeyEvent(Map<String, Object> event) {
        Map<String, Object> data = new HashMap<>();
        Map<?, ?> storedData = (Map<?, ?>) event.get("data");
        for (String field : API_KEY_AUDIT_FIELDS) {
            Object value = storedData.get(field);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                data.put(field, value);
            }
        }
        Number resourceId = (Number) event.get("resourceId");
        if (event.get("delegatedEventId") instanceof String eventId) {
            auditLogDao.createDelegatedOnce(eventId, (String) event.get("resourceType"), resourceId == null ? null : resourceId.longValue(), data, null,
                    ((Number) event.get("accountId")).longValue(), ((Number) event.get("authenticatedAsAccountId")).longValue(),
                    (String) event.get("eventType"), (String) event.get("authType"), ((Number) event.get("runtime")).longValue(),
                    null, (String) event.get("clientIp"));
        } else {
            Number accountId = (Number) event.get("accountId");
            Number principalId = (Number) event.get("authenticatedAsAccountId");
            auditLogDao.createApiKeyEvent((String) event.get("resourceType"), resourceId == null ? null : resourceId.longValue(), data, null,
                    accountId == null ? null : accountId.longValue(), principalId == null ? null : principalId.longValue(),
                    (String) event.get("eventType"), (String) event.get("authType"), ((Number) event.get("runtime")).longValue(),
                    null, (String) event.get("clientIp"));
        }
    }

    Identity auditIdentity(Set<Identity> identities) {
        if (identities == null || identities.isEmpty()) {
            return null;
        }
        for (Identity identity : identities) {
            if (identity != null && identity.getExternalIdType() != null
                    && identity.getExternalIdType().contains("user")) {
                return identity;
            }
        }
        return identities.size() == 1 ? identities.iterator().next() : null;
    }

    private String convertResourceType(String type) {
        switch (StringUtils.lowerCase(type)) {
            case "environment":
                return "stack";
            case "project":
                return "environment";
            default:
                return type;
        }
    }

    private Long parseId(String resourceId) {
        Long parsedResourceId;
        if (resourceId == null || resourceId.isEmpty()){
            parsedResourceId = null;
        } else try {
            if (ApiContext.getContext() != null && ApiContext.getContext().getIdFormatter() != null) {
                parsedResourceId = Long.valueOf(ApiContext.getContext().getIdFormatter().parseId(resourceId));
            } else if (idFormatter != null) {
                parsedResourceId = Long.valueOf(idFormatter.parseId(resourceId));
            } else {
                parsedResourceId = Long.valueOf(resourceId);
            }
        } catch (NumberFormatException e) {
            try {
                parsedResourceId = Long.valueOf(resourceId);
            } catch (NumberFormatException e1) {
                parsedResourceId = null;
            }
        }
        return parsedResourceId;
    }

    private AuditEventType convertToAction(String method) {
        switch (Schema.Method.valueOf(method)){
            case DELETE:
                return AuditEventType.delete;
            case POST:
                return AuditEventType.create;
            case PUT:
                return AuditEventType.update;
            default:
                return AuditEventType.UNKNOWN;
        }
    }

    private void putInAsString(Map<String, Object> data, String type, String fieldForObject, String errMsg, Object objectToPlace) {
        if (objectToPlace == null) {
            return;
        }

        Map<String, Object> obj = sanitizedAuditObject(objectToPlace, type);

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try {
            jsonMapper.writeValue(os, obj);
            data.put(fieldForObject, os.toString());
        } catch (IOException e) {
            log.error("Failed to log [{}]", errMsg, e);
        }
    }

    protected Map<String, Object> sanitizedAuditObject(Object objectToPlace, String type) {
        Map<String, Object> obj = auditObjectMap(objectToPlace);

        if ("secret".equals(type)) {
            obj.remove("value");
        }

        obj.remove("secretValue");
        obj.remove("password");
        obj.remove("newSecret");
        obj.remove("oldSecret");
        obj.remove("adminAccountPassword");
        obj.remove("serviceAccountPassword");
        obj.remove("identityProof");
        obj.remove("providerSwitchCode");
        obj.remove("localPassword");
        obj.remove("mfaCode");
        obj.remove("recoveryCode");
        obj.remove("verificationCode");
        obj.remove("webAuthnResponse");
        obj.remove("challengeId");
        obj.remove("totpSecret");
        obj.remove("totpProvisioningUri");
        obj.remove("publicKey");
        obj.remove("recoveryCodes");
        obj.remove("smtpPassword");
        obj.remove("emailCode");
        obj.remove("email");
        obj.remove("testRecipient");
        obj.remove("securityConfirmation");
        obj.remove("purposeDigest");
        obj.remove("token");
        obj.remove("backendToken");
        Iterator<Map.Entry<String, Object>> iter = obj.entrySet().iterator();
        while (iter.hasNext()) {
            if (iter.next().getKey().endsWith("Config")) {
                iter.remove();
            }
        }
        return obj;
    }

    protected Map<String, Object> auditObjectMap(Object objectToPlace) {
        Map<?, ?> converted = jsonMapper.convertValue(objectToPlace, Map.class);
        Map<String, Object> result = new HashMap<String, Object>(converted.size());
        for (Map.Entry<?, ?> entry : converted.entrySet()) {
            result.put(String.class.cast(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
