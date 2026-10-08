package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ApiKeyDelegatedAuditEvent;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.dao.AgentDao;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.constants.HostConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.filter.apikey.ApiKeyDelegationTokenProvider;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.token.TokenService;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import jakarta.inject.Inject;

/** Short, target-bound grants. Verification always reconstructs current owner RBAC. */
public class ApiKeyDelegationService implements ApiKeyDelegationTokenProvider {
    public static final String CLAIM = "apiKeyDelegation";
    public static final String AUDIT_CLAIM = "apiKeyAudit";
    public static final int MAX_TOKEN_BYTES = 16 * 1024;
    public static final long MAX_LIFETIME_SECONDS = 300;
    private static final ConfigProperty<Long> AUDIT_RETENTION_SECONDS = ArchaiusUtil.getLongProperty("audit_log.purge.after.seconds");
    @Inject ObjectManager objectManager;
    @Inject TokenService tokenService;
    @Inject JsonMapper jsonMapper;
    @Inject ApiAuthenticator authenticator;
    @Inject ApiKeyTargetResolver targets;
    @Inject AgentDao agentDao;
    @Inject List<ApiKeyAuditSink> auditSinks;
    Clock clock = Clock.systemUTC();
    Supplier<Long> auditRetentionSeconds = AUDIT_RETENTION_SECONDS::get;
    private final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
    private final ApiKeyPolicyEvaluator evaluator = new ApiKeyPolicyEvaluator();

    public record LiveGrant(ApiKeyCredentialContext key, ApiAuthenticator.CurrentAuthorization authorization,
                            ApiRequest original, Map<String, Object> envelope) { }

    @Override public String token(ApiRequest request, Map<String, Object> payload, Date expiration) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null) return null;
        Policy owner = (Policy) ApiContext.getContext().getPolicy();
        if (owner == null || !"ALLOW".equals(request.getAttribute("apiKey.audit.decision"))) denied("KeyPolicyDenied");
        if (!key.restricted()) {
            // Non-enforcing trace only. Legacy principal, claims, default TTL
            // and downstream authentication behavior are preserved.
            Map<String, Object> traced = new LinkedHashMap<>(payload);
            traced.put(AUDIT_CLAIM, metadata(key, owner.getAccountId(), request));
            return expiration == null ? tokenService.generateToken(traced) : tokenService.generateToken(traced, expiration);
        }
        long expiry = Math.min(clock.instant().getEpochSecond() + MAX_LIFETIME_SECONDS,
                key.policy().getExpiresAt() == null ? Long.MAX_VALUE : key.policy().getExpiresAt().getEpochSecond());
        if (expiration != null) expiry = Math.min(expiry, expiration.toInstant().getEpochSecond());
        Map<String, Object> grant = metadata(key, owner.getAccountId(), request);
        grant.put("expiresAt", expiry);
        // Convert POJO statistics targets to bounded plain JSON before signing.
        try { grant.put("payload", jsonMapper.readValue(jsonMapper.writeValueAsString(payload))); }
        catch (IOException invalid) { denied("ApiKeyDelegationInvalid"); }
        verify(grant, 0);
        String token = tokenService.generateToken(Map.of(CLAIM, grant), Date.from(java.time.Instant.ofEpochSecond(expiry)));
        if (token.length() > MAX_TOKEN_BYTES) denied("ApiKeyDelegationTooLarge");
        return token;
    }

    Map<String, Object> metadata(ApiKeyCredentialContext key, long accountId, ApiRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("keyId", key.credentialId());
        result.put("principalId", key.principalAccountId());
        result.put("accountId", accountId);
        result.put("revision", key.revision());
        result.put("issuedAt", clock.instant().getEpochSecond());
        Object requestId = request.getAttribute("apiKey.audit.requestId");
        result.put("requestId", requestId instanceof String id && id.matches("[0-9a-fA-F-]{36}")
                ? id : java.util.UUID.randomUUID().toString());
        result.put("targetType", request.getType());
        result.put("targetId", request.getId());
        result.put("operation", ApiKeyOperations.of(request).id());
        result.put("requestMethod", request.getMethod());
        if (request.getAction() != null) result.put("requestAction", request.getAction());
        if (request.getLink() != null) result.put("requestLink", request.getLink());
        return result;
    }

    /** Called by ApiAuthenticator before normal account lookup, not a configurable handler list. */
    public boolean handleIntrospection(ApiRequest request) throws IOException {
        if (!"apiKeyDelegation".equals(request.getType())) return false;
        request.setAttribute("apiKey.audit.preview", true);
        if (!"POST".equals(request.getMethod()) || !"introspect".equals(request.getId())
                || request.getAction() != null || request.getLink() != null
                || !(request.getRequestObject() instanceof Map<?, ?> body)
                || body.size() != 1 || !(body.get("token") instanceof String token)) denied("ApiKeyDelegationInvalid");
        String token = (String) ((Map<?, ?>) request.getRequestObject()).get("token");
        Map<String, Object> signed = signedEnvelope(token);
        // Signature-verified ticket metadata may only record a DENY until the
        // live credential/RBAC check succeeds. It never installs a Policy.
        request.setAttribute("apiKey.audit.keyId", String.valueOf(ApiContext.getContext().getIdFormatter()
                .formatId(CredentialConstants.TYPE, number(signed, "keyId"))));
        request.setAttribute("apiKey.audit.policyRevision", number(signed, "revision"));
        request.setAttribute("apiKey.audit.verifiedPrincipalAccountId", number(signed, "principalId"));
        request.setAttribute("apiKey.audit.verifiedAccountId", number(signed, "accountId"));
        request.setAttribute("apiKey.audit.operation", text(signed, "operation"));
        request.setAttribute("apiKey.audit.targetType", text(signed, "targetType"));
        request.setAttribute("apiKey.audit.targetId", text(signed, "targetId"));
        request.setAttribute("apiKey.audit.decision", "DENY");
        request.setAttribute("apiKey.audit.requestId", java.util.UUID.randomUUID().toString());
        LiveGrant live;
        try { live = verify(signed, 0); }
        catch (ClientVisibleException failure) {
            request.setAttribute("apiKey.audit.reason", failure.getCode());
            throw failure;
        }
        ApiContext.getContext().setPolicy(live.authorization().policy());
        request.setSchemaFactory(live.authorization().schemas());
        ApiKeyCredentialContext.attach(request, live.key());
        request.setAttribute("apiKey.audit.operation", live.envelope().get("operation"));
        request.setAttribute("apiKey.audit.targetType", live.envelope().get("targetType"));
        request.setAttribute("apiKey.audit.targetId", live.envelope().get("targetId"));
        request.setAttribute("apiKey.audit.decision", "ALLOW");
        request.setAttribute("apiKey.audit.reason", "ApiKeyDelegationLive");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("allowed", true);
        for (String field : List.of("keyId", "revision", "targetType", "targetId", "operation", "expiresAt"))
            response.put(field, live.envelope().get(field));
        response.put("tokenDigest", digest(token));
        request.setResponseContentType("application/json");
        request.getServletContext().getResponse().setHeader("Cache-Control", "no-store");
        request.getOutputStream().write(jsonMapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8));
        return true;
    }

    public LiveGrant introspect(String token) {
        try { return verify(signedEnvelope(token), 0); }
        catch (ClientVisibleException failure) { throw failure; }
        catch (RuntimeException invalid) { denied("ApiKeyDelegationInvalid"); return null; }
    }

    /** Dual server-signed proof can record only fixed, pre-execution failures.
     * It never installs an agent Policy or authorizes a Docker operation. */
    public boolean handleProxyFailure(ApiRequest request) throws IOException {
        if (!"apiKeyDelegationFailure".equals(request.getType())) return false;
        if (!"POST".equals(request.getMethod()) || !"report".equals(request.getId())
                || request.getAction() != null || request.getLink() != null) denied("ApiKeyDelegationInvalid");
        Map<String, Object> response = recordProxyFailure(request);
        request.setResponseCode(200);
        request.setResponseContentType("application/json");
        request.getServletContext().getResponse().setHeader("Cache-Control", "no-store");
        request.getOutputStream().write(jsonMapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8));
        return true;
    }

    Map<String, Object> recordProxyFailure(ApiRequest request) {
        Map<String, Object> body = map(request.getRequestObject());
        boolean routeDenied = body.keySet().equals(Set.of("token", "backendToken", "failureCode", "attemptId"))
                && "DelegationRouteDenied".equals(body.get("failureCode"));
        if (!routeDenied && !body.keySet().equals(Set.of("token", "backendToken"))) denied("ApiKeyDelegationInvalid");
        String attemptId = routeDenied ? text(body, "attemptId") : null;
        if (routeDenied && !attemptId.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            denied("ApiKeyDelegationInvalid");
        Map<String, Object> backend = auditClaims(text(body, "backendToken"));
        if (!"host-api-backend-v1".equals(backend.get("purpose"))) denied("DelegatedAuditAgentRequired");
        checkReceiptAge(number(backend, "issuedAt"));
        long agentId = number(backend, "agentId");
        Agent agent = objectManager.loadResource(Agent.class, agentId);
        if (agent == null || agent.getRemoved() != null || !"active".equals(agent.getState())) denied("DelegatedAuditAgentRequired");
        // The Proxy may report this bounded observation but cannot select an
        // identity, resource, successful outcome, or arbitrary failure code.
        // Both identities and the original operation come from signed tickets.
        return recordEvidence(text(body, "token"), "FAILED",
                routeDenied ? "DelegationRouteDenied" : "BackendAuditCapabilityUnavailable", agentId,
                routeDenied ? "apiKey-stream-route-denial-v1|" + attemptId + "|" : "apiKey-stream-handshake-failure-v1|",
                text(backend, "reportedUuid"));
    }

    /** Authenticated host evidence, never an alternative authorization path. */
    public Map<String, Object> recordCompletion(ApiRequest request) {
        Policy agentPolicy = (Policy) ApiContext.getContext().getPolicy();
        String agentId = agentPolicy == null ? null : agentPolicy.getOption(Policy.AGENT_ID);
        if (agentId == null || !agentId.matches("[0-9]+")) denied("DelegatedAuditAgentRequired");
        Map<String, Object> body = map(request.getRequestObject());
        if (!Set.of("token", "outcome", "failureCode").containsAll(body.keySet())) denied("ApiKeyDelegationInvalid");
        String token = text(body, "token");
        String outcome = text(body, "outcome");
        Object rawCode = body.get("failureCode");
        String failureCode = rawCode == null ? null : text(body, "failureCode");
        Set<String> failures = Set.of("HandshakeDenied", "DockerFailure", "StreamFailed", "AuditUnavailable");
        Set<String> cancellations = Set.of("AuthorizationRevoked", "ClientDisconnected", "StreamCancelled");
        if (!(outcome.equals("SUCCEEDED") && failureCode == null)
                && !(outcome.equals("FAILED") && failures.contains(failureCode == null ? "" : failureCode))
                && !(outcome.equals("CANCELLED") && cancellations.contains(failureCode == null ? "" : failureCode))) denied("ApiKeyDelegationInvalid");
        return recordEvidence(token, outcome, failureCode, Long.parseLong(agentId), "apiKey-stream-terminal-v1|", null);
    }

    private Map<String, Object> auditClaims(String token) {
        try { return tokenService.getAuditSignaturePayload(token); }
        catch (io.cattle.platform.token.TokenException | RuntimeException invalid) {
            denied("ApiKeyDelegationInvalid"); return null;
        }
    }

    private void checkReceiptAge(long issuedAt) {
        long now = clock.instant().getEpochSecond();
        Long retention = auditRetentionSeconds.get();
        long maxAge = retention == null ? 0 : Math.min(7 * 24 * 3600L, retention);
        if (maxAge <= 0 || issuedAt <= 0 || issuedAt > now + 30 || now - issuedAt > maxAge) denied("ApiKeyDelegationInvalid");
    }

    private Map<String, Object> recordEvidence(String token, String outcome, String failureCode, long agentId,
                                              String eventPrefix, String backendHostUuid) {
        Map<String, Object> claims = auditClaims(token);
        boolean scoped = claims.containsKey(CLAIM);
        Map<String, Object> grant = map(claims.get(scoped ? CLAIM : AUDIT_CLAIM));
        long issuedAt = number(grant, "issuedAt");
        // Cleanup does not treat zero/negative retention as disabled. Do not accept
        // a new receipt after its persisted dedupe row could already be purged.
        checkReceiptAge(issuedAt);
        if (number(grant, "version") != 1
                || number(grant, "keyId") < 1 || number(grant, "principalId") < 1 || number(grant, "accountId") < 1) denied("ApiKeyDelegationInvalid");
        if (scoped && (number(grant, "expiresAt") != number(claims, "exp")
                || number(grant, "expiresAt") <= issuedAt || number(grant, "expiresAt") - issuedAt > MAX_LIFETIME_SECONDS)) denied("ApiKeyDelegationInvalid");
        Map<String, Object> payload = scoped ? map(grant.get("payload")) : claims;
        String hostUuid = text(payload, "hostUuid");
        Host host = agentDao.getHosts(agentId).get(hostUuid);
        if (host == null || (backendHostUuid != null && !hostUuid.equals(backendHostUuid))) denied("DelegatedAuditHostMismatch");
        String operation = text(grant, "operation");
        if (!Set.of("exec", "logs", "read").contains(operation)) denied("ApiKeyDelegationInvalid");
        String requestId = text(grant, "requestId");
        if (!requestId.matches("[0-9a-fA-F-]{36}")) denied("ApiKeyDelegationInvalid");
        String eventId = digest(eventPrefix + token);
        String keyId = String.valueOf(ApiContext.getContext().getIdFormatter().formatId(CredentialConstants.TYPE, number(grant, "keyId")));
        ApiKeyDelegatedAuditEvent event = new ApiKeyDelegatedAuditEvent(eventId, keyId,
                number(grant, "principalId"), number(grant, "accountId"), number(grant, "revision"), operation,
                text(grant, "targetType"), text(grant, "targetId"), requestId, outcome, failureCode, hostUuid);
        if (auditSinks == null || auditSinks.isEmpty()) throw new ClientVisibleException(503, "AuditUnavailable");
        try { for (ApiKeyAuditSink sink : auditSinks) sink.recordDelegatedOutcome(event); }
        catch (RuntimeException unavailable) { throw new ClientVisibleException(503, "AuditUnavailable"); }
        return Map.of("accepted", true, "eventId", eventId);
    }

    private Map<String, Object> signedEnvelope(String token) {
        try {
            if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_BYTES) denied("ApiKeyDelegationInvalid");
            Map<String, Object> claims = tokenService.getJsonPayload(token, false);
            // No legacy sink may mistake a new grant for an unrestricted token.
            for (String field : List.of("hostUuid", "exec", "logs", "resourceId", "containerIds", "project", "service"))
                if (claims.containsKey(field)) denied("ApiKeyDelegationInvalid");
            Map<String, Object> envelope = map(claims.get(CLAIM));
            if (number(claims, "exp") != number(envelope, "expiresAt")) denied("ApiKeyDelegationInvalid");
            return envelope;
        } catch (ClientVisibleException failure) { throw failure; }
        catch (Exception invalid) { denied("ApiKeyDelegationInvalid"); return null; }
    }

    LiveGrant current(Map<String, Object> grant) {
        if (number(grant, "version") != 1) denied("ApiKeyDelegationInvalid");
        Credential credential = objectManager.loadResource(Credential.class, number(grant, "keyId"));
        if (credential == null || credential.getRemoved() != null || !"active".equals(credential.getState())
                || !Set.of(CredentialConstants.KIND_API_KEY, CredentialConstants.KIND_API_KEY_RESTRICTED).contains(credential.getKind()))
            denied("ApiKeyRevoked");
        if (credential.getAccountId() != number(grant, "principalId")) denied("OwnerPermissionDenied");
        ApiKeyPolicy policy = codec.read(credential);
        if (policy == null || codec.revision(credential) != number(grant, "revision")) denied("ApiKeyPolicyChanged");
        if (policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) denied("ApiKeyExpired");
        if (policy.getMode() == ApiKeyPolicy.Mode.CLOSED) denied("KeyPolicyDenied");
        ApiRequest original = new ApiRequest(null, objectManager.getSchemaFactory());
        original.setSchemaVersion("v2-beta");
        original.setType(text(grant, "targetType"));
        original.setId((String) grant.get("targetId"));
        original.setMethod(text(grant, "requestMethod"));
        original.setAction((String) grant.get("requestAction"));
        original.setLink((String) grant.get("requestLink"));
        if (!ApiKeyOperations.of(original).id().equals(text(grant, "operation"))) denied("ApiKeyDelegationInvalid");
        var authorization = authenticator.currentAuthorization(credential.getAccountId(), number(grant, "accountId"), original);
        if (!schemaAllows(authorization.schemas().getSchema(original.getType()), original)) denied("OwnerPermissionDenied");
        return new LiveGrant(new ApiKeyCredentialContext(credential.getId(), credential.getAccountId(), credential.getKind(),
                codec.revision(credential), policy), authorization, original, grant);
    }

    LiveGrant verify(Map<String, Object> grant, int depth) {
        long expiry = number(grant, "expiresAt");
        if (expiry <= clock.instant().getEpochSecond()) denied("ApiKeyExpired");
        if (expiry > clock.instant().getEpochSecond() + MAX_LIFETIME_SECONDS || depth > 1) denied("ApiKeyDelegationInvalid");
        LiveGrant live = current(grant);
        if (live.key().policy().getExpiresAt() != null && expiry > live.key().policy().getExpiresAt().getEpochSecond()) denied("ApiKeyExpired");
        if (!Set.of("exec", "logs", "read").contains(text(grant, "operation"))) denied("ApiKeyDelegationInvalid");
        ApiRequest original = live.original();
        if (original.getId() == null) denied("ApiKeyDelegationInvalid");
        Object root = load(original.getType(), original.getId());
        check(live, original.getType(), root, text(grant, "operation"));
        Map<String, Object> payload = map(grant.get("payload"));
        if (payload.containsKey("project") || payload.containsKey("service")) {
            if (depth != 0 || !"read".equals(text(grant, "operation")) || payload.size() != 1) denied("ApiKeyDelegationInvalid");
            Object raw = payload.get(root instanceof Service ? "service" : "project");
            if (!(root instanceof Service || root instanceof Account) || !(raw instanceof List<?> entries)
                    || entries.isEmpty() || entries.size() > 256) denied("ApiKeyDelegationInvalid");
            for (Object entry : (List<?>) raw) {
                Map<String, Object> item = map(entry);
                LiveGrant child = introspectNested(text(item, "token"), depth + 1);
                for (String field : List.of("keyId", "principalId", "accountId", "revision", "targetType", "targetId", "operation", "requestMethod", "requestAction", "requestLink"))
                    if (!Objects.equals(grant.get(field), child.envelope().get(field))) denied("ApiKeyDelegationInvalid");
            }
            return live;
        }
        String hostUuid = text(payload, "hostUuid");
        if (payload.containsKey("exec") || payload.containsKey("logs")) {
            String operation = text(grant, "operation");
            if (!(root instanceof Instance instance) || !Set.of("exec", "logs").contains(operation) || payload.size() != 2) denied("ApiKeyDelegationInvalid");
            Instance instance = (Instance) root;
            if (!identifier(instance).equals(text(map(payload.get(operation)), "Container"))) denied("ApiKeyDelegationInvalid");
            hostFor(instance, hostUuid, live.authorization().policy());
        } else if (payload.containsKey("resourceId")) {
            if (!"read".equals(text(grant, "operation")) || payload.size() != 2) denied("ApiKeyDelegationInvalid");
            Host host = (Host) load("host", text(payload, "resourceId"));
            if (!(root instanceof Host && ((Host) root).getId().equals(host.getId()))
                    && !(root instanceof Account && ((Account) root).getId().equals(host.getAccountId()))) denied("ApiKeyDelegationInvalid");
            hostMatches(host, hostUuid);
            check(live, "host", host, "read");
        } else if (payload.containsKey("containerIds")) {
            if (!"read".equals(text(grant, "operation")) || payload.size() != 2) denied("ApiKeyDelegationInvalid");
            Map<String, Object> containers = map(payload.get("containerIds"));
            if (containers.isEmpty() || containers.size() > 256) denied("ApiKeyDelegationInvalid");
            for (Map.Entry<String, Object> entry : containers.entrySet()) {
                Instance instance = (Instance) load("container", entry.getValue().toString());
                if (!entry.getKey().equals(identifier(instance))) denied("ApiKeyDelegationInvalid");
                Host host = hostFor(instance, hostUuid, live.authorization().policy());
                if (!(root instanceof Instance && ((Instance) root).getId().equals(instance.getId()))
                        && !(root instanceof Service && ((Service) root).getId().equals(instance.getServiceId()))
                        && !(root instanceof Host && ((Host) root).getId().equals(host.getId()))) denied("ApiKeyDelegationInvalid");
                check(live, "container", instance, "read");
            }
        } else denied("ApiKeyDelegationInvalid");
        return live;
    }

    private LiveGrant introspectNested(String token, int depth) {
        try {
            if (token.length() > MAX_TOKEN_BYTES) denied("ApiKeyDelegationInvalid");
            Map<String, Object> claims = tokenService.getJsonPayload(token, false);
            Map<String, Object> grant = map(claims.get(CLAIM));
            if (number(claims, "exp") != number(grant, "expiresAt")) denied("ApiKeyDelegationInvalid");
            return verify(grant, depth);
        } catch (ClientVisibleException failure) { throw failure; }
        catch (Exception invalid) { denied("ApiKeyDelegationInvalid"); return null; }
    }

    void check(LiveGrant live, String type, Object resource, String operation) {
        if (resource == null || io.cattle.platform.object.util.ObjectUtils.getPropertyIgnoreErrors(resource, "removed") != null
                || live.authorization().policy().authorizeObject(resource) == null) denied("OwnerPermissionDenied");
        if (live.key().policy().getMode() == ApiKeyPolicy.Mode.FULL) return;
        var target = targets.resolveObject(type, resource, live.authorization().policy());
        var decision = evaluator.evaluate(live.key().policy(), new ApiKeyPolicyEvaluator.Request(true, operation, true, List.of(target)), clock.instant());
        if (!decision.allowed()) denied(decision.reason().getCode());
    }

    Object load(String type, String id) {
        // Event IDs originate inside Engine; public typed IDs still round-trip
        // through the authoritative formatter before a model is loaded.
        long numeric = id.matches("[0-9]+") ? Long.parseLong(id) : targets.parseScopeId(type, id);
        Object result = objectManager.loadResource(type, Long.toString(numeric));
        if (result == null) denied("OwnerPermissionDenied");
        return result;
    }

    private Host hostFor(Instance instance, String uuid, Policy owner) {
        if (instance.getRemoved() != null) denied("OwnerPermissionDenied");
        for (Host host : objectManager.mappedChildren(instance, Host.class)) {
            String reported = DataAccessor.fields(host).withKey(HostConstants.FIELD_REPORTED_UUID).as(String.class);
            if (uuid.equals(reported == null ? host.getUuid() : reported) && owner.authorizeObject(host) != null) {
                hostMatches(host, uuid);
                return host;
            }
        }
        denied("ApiKeyDelegationTargetChanged"); return null;
    }
    private void hostMatches(Host host, String uuid) {
        String reported = DataAccessor.fields(host).withKey(HostConstants.FIELD_REPORTED_UUID).as(String.class);
        if (host.getRemoved() != null || !uuid.equals(reported == null ? host.getUuid() : reported)) denied("ApiKeyDelegationTargetChanged");
    }
    private static String identifier(Instance instance) {
        return instance.getExternalId() == null || instance.getExternalId().isEmpty() ? instance.getUuid() : instance.getExternalId();
    }
    static boolean schemaAllows(Schema schema, ApiRequest request) {
        if (schema == null) return false;
        if (request.getAction() != null) return schema.getResourceActions() != null && schema.getResourceActions().containsKey(request.getAction());
        List<String> methods = request.getId() == null ? schema.getCollectionMethods() : schema.getResourceMethods();
        return methods != null && methods.contains(request.getMethod());
    }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.size() > 256) { denied("ApiKeyDelegationInvalid"); return null; }
        return (Map<String, Object>) value;
    }
    static long number(Map<String, Object> grant, String field) { return ApiKeyPolicyCodec.number(grant.get(field)); }
    static String text(Map<String, Object> grant, String field) {
        if (!(grant.get(field) instanceof String value) || value.isBlank() || value.length() > MAX_TOKEN_BYTES) { denied("ApiKeyDelegationInvalid"); return null; }
        return value;
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void denied(String code) { throw new ClientVisibleException(403, code, "This delegated API key access is no longer authorized.", null); }
}
