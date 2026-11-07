package io.cattle.platform.iaas.api.auth.ai.logs;

import io.cattle.platform.api.auth.ApiAuditPayload;
import io.cattle.platform.api.auth.ApiContainerLogsRead;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.docker.api.model.HostAccess;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.integration.internal.rancher.TokenAuthLookup;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded logs authority stays in the Engine; only the fixed broker reads agent output. */
public class AiContainerLogsService {
    @Inject TokenAuthLookup sessions;
    @Inject AiKeyAuthorization keys;
    @Inject ApiAuthenticator authenticator;
    @Inject ApiKeyAuthorizationService keyAccess;
    @Inject io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver targets;
    @Inject ResourceManagerLocator managers;
    @Inject ObjectManager objects;
    @Inject AiContainerLogsRunner runner;
    @Inject ApiKeyAuditSink audit;
    private record Target(long id, String uuid, long actor, long project, int lines, long expires) { }

    public Map<String, Object> read(ApiRequest request, Map<String, Object> input) {
        ApiAuditPayload.boundedContainerLogs(request);
        String requestId = "ailogs-" + UUID.randomUUID(), outcome = "ERROR", reason = "Unavailable";
        Policy actor = ApiUtils.getPolicy(); Long targetId = null;
        try {
            if (Boolean.TRUE.equals(request.getAttribute(AiContainerLogsInputHandler.REJECTED))
                    || !Set.of("type", "resourceType", "resourceId", "view", "tailLines").containsAll(input.keySet())
                    || !"container".equals(input.get("resourceType")) || !"container_logs".equals(input.get("view")))
                throw error(422, "ContainerLogsInputInvalid");
            int lines = lines(input.getOrDefault("tailLines", 100));
            targetId = id(input.get("resourceId"));
            actor = keys.actor(request);
            if (actor == null || actor.getAuthenticatedAsAccountId() <= 0 || actor.getAccountId() <= 0) throw error(403, "ContainerLogsDenied");
            TokenAuthLookup.SessionAuthority session = ApiKeyCredentialContext.get(request) == null
                    ? sessions.captureVerifiedSession(request, actor.getAuthenticatedAsAccountId()) : null;
            Instance instance = objects.loadResource(Instance.class, targetId);
            if (instance == null || instance.getUuid() == null) throw error(403, "ContainerLogsDenied");
            Target target = new Target(targetId, instance.getUuid(), actor.getAuthenticatedAsAccountId(), actor.getAccountId(), lines,
                    System.currentTimeMillis() + 5000);
            ApiRequest domain = authorize(request, session, target);
            Object access = withDomain(domain, () -> managers.getResourceManagerByType("container").resourceAction("container", domain));
            if (!(access instanceof HostAccess nativeAccess)) throw error(503, "ContainerLogsUnavailable");
            // Ticket construction is private to this owner. Invalid consume attempts burn it.
            AiContainerLogsTicket ticket = new AiContainerLogsTicket(request, target.actor(), target.project(), target.uuid(),
                    requestId, lines, target.expires(), nativeAccess);
            Map<String, Object> read = runner.read(ticket, request, target.actor(), target.project(), target.uuid());
            Map<String, Object> result = safeResult(read, requestId, target, secrets(request, nativeAccess.getToken()));
            authorize(request, session, target); // Fresh session/Key, actor/project, UUID, role and native logs ceiling before disclosure.
            outcome = "ALLOW"; reason = "Completed";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", wire("container", target.id())); row.put("type", "container"); row.put("accountId", wire("project", target.project()));
            row.put("logs", result);
            Map<String, Object> response = new LinkedHashMap<>(); response.put("id", requestId); response.put("resourceType", "container");
            response.put("data", List.of(row)); response.put("nextMarker", null); return response;
        } catch (ClientVisibleException failure) {
            if (failure.getStatus() == 401 || failure.getStatus() == 403) {
                outcome = "DENY"; reason = "AuthorityDenied"; throw error(403, "ContainerLogsDenied");
            }
            if (failure.getStatus() == 422) reason = "InputInvalid";
            throw failure;
        } catch (InterruptedException canceled) {
            Thread.currentThread().interrupt(); reason = "Canceled"; throw error(503, "ContainerLogsUnavailable");
        } catch (Exception unavailable) { throw error(503, "ContainerLogsUnavailable"); }
        finally {
            try { audit.recordContainerLogsOutcome(request, actor, requestId, targetId, outcome, reason); }
            catch (RuntimeException unavailable) { throw error(503, "ContainerLogsAuditUnavailable"); }
        }
    }

    private ApiRequest authorize(ApiRequest original, TokenAuthLookup.SessionAuthority session, Target target) {
        if (Thread.currentThread().isInterrupted() || System.currentTimeMillis() >= target.expires()) throw error(503, "ContainerLogsUnavailable");
        if (session != null) sessions.requireCurrentSession(session, original);
        Policy actor = keys.actor(original);
        if (actor == null || actor.getAuthenticatedAsAccountId() != target.actor() || actor.getAccountId() != target.project())
            throw error(403, "ContainerLogsDenied");
        Instance instance = objects.loadResource(Instance.class, target.id());
        if (instance == null || instance.getRemoved() != null || !"container".equals(instance.getKind())
                || !Objects.equals(instance.getAccountId(), target.project()) || !target.uuid().equals(instance.getUuid()))
            throw error(403, "ContainerLogsDenied");
        ApiRequest domain = new ApiRequest(original); domain.setRequestServletContext(original.getServletContext());
        domain.setType("container"); domain.setId(Long.toString(target.id())); domain.setMethod("POST"); domain.setAction("logs"); domain.setLink(null);
        domain.setRequestParams(new LinkedHashMap<>()); domain.setConditions(new LinkedHashMap<>());
        domain.setRequestObject(Map.of("lines", target.lines(), "follow", false, "timestamps", true, "since", ""));
        var authority = authenticator.currentAuthorization(target.actor(), target.project(), domain);
        var schema = authority.schemas().getSchema("container");
        if (schema == null || schema.getResourceMethods() == null || !schema.getResourceMethods().contains("GET")
                || schema.getResourceActions() == null || !schema.getResourceActions().containsKey("logs")
                || authority.policy().authorizeObject(instance) == null) throw error(403, "ContainerLogsDenied");
        domain.setSchemaFactory(authority.schemas()); ApiContainerLogsRead.bind(domain, target.expires());
        var key = ApiKeyCredentialContext.get(original); if (key != null) ApiKeyCredentialContext.attach(domain, key);
        withDomain(domain, () -> { keyAccess.authorize(domain, authority.policy()); return null; }, authority.policy());
        return domain;
    }

    private <T> T withDomain(ApiRequest domain, java.util.function.Supplier<T> call) {
        Policy current = authenticator.currentAuthorization(ApiUtils.getPolicy().getAuthenticatedAsAccountId(),
                ApiUtils.getPolicy().getAccountId(), domain).policy();
        return withDomain(domain, call, current);
    }
    private static <T> T withDomain(ApiRequest domain, java.util.function.Supplier<T> call, Policy policy) {
        ApiContext context = ApiContext.getContext(); ApiRequest old = context.getApiRequest(); Object previous = context.getPolicy();
        context.setApiRequest(domain); context.setPolicy(policy);
        try { return call.get(); } finally { context.setApiRequest(old); context.setPolicy(previous); }
    }

    static Map<String, Object> safeResult(Map<String, Object> input, String requestId, Target target, List<String> secrets) {
        if (input == null || !input.keySet().equals(Set.of("requestId", "actorId", "projectId", "containerUuid", "operation",
                "tailLines", "text", "truncated", "completeness", "stopReason"))
                || !requestId.equals(input.get("requestId")) || !sameNumber(input.get("actorId"), target.actor())
                || !sameNumber(input.get("projectId"), target.project()) || !target.uuid().equals(input.get("containerUuid"))
                || !"logs".equals(input.get("operation")) || !sameNumber(input.get("tailLines"), target.lines())
                || !"unknown".equals(input.get("completeness")) || !(input.get("truncated") instanceof Boolean)
                || !Set.of("eof", "line_limit", "byte_limit", "deadline").contains(input.get("stopReason"))
                || !"eof".equals(input.get("stopReason")) && !Boolean.TRUE.equals(input.get("truncated"))
                || !(input.get("text") instanceof String text) || text.getBytes(StandardCharsets.UTF_8).length > 65536
                || text.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)
                || text.chars().filter(c -> c == '\n').count() + (text.isEmpty() || text.endsWith("\n") ? 0 : 1) > target.lines())
            throw error(503, "ContainerLogsUnavailable");
        String clean = text.replaceAll("\\x1B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\))", "")
                .replaceAll("[\\x00-\\x08\\x0B-\\x1F\\x7F-\\x9F]", "");
        for (String secret : secrets) if (secret != null && !secret.isEmpty()) clean = clean.replace(secret, "[REDACTED]");
        clean = clean.replaceAll("(?im)(\\b(?:password|passwd|secret|token|api[_-]?key|authorization|cookie)\\b[ \\t]*[:=][ \\t]*)"
                + "(\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)", "$1[REDACTED]");
        boolean truncated = Boolean.TRUE.equals(input.get("truncated"));
        byte[] encoded = clean.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 65536) {
            int end = 65536; while (end > 0 && (encoded[end] & 0xC0) == 0x80) end--;
            clean = new String(encoded, 0, end, StandardCharsets.UTF_8); truncated = true;
        }
        return Map.of("text", clean, "truncated", truncated, "completeness", "unknown", "stopReason", input.get("stopReason"),
                "resourceTextIsUntrusted", true, "redaction", "known-request-secrets-and-sensitive-key-patterns-only");
    }
    private static List<String> secrets(ApiRequest request, String nativeToken) {
        List<String> values = new ArrayList<>(); values.add(nativeToken);
        var http = request.getServletContext().getRequest(); var cookies = http.getCookies();
        if (cookies != null) for (var cookie : cookies) if ("token".equalsIgnoreCase(cookie.getName())) values.add(cookie.getValue());
        String authorization = http.getHeader("Authorization");
        if (authorization != null) {
            values.add(authorization);
            if (authorization.startsWith("Basic ")) try {
                String pair = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
                int colon = pair.indexOf(':'); if (colon >= 0) { values.add(pair.substring(0, colon)); values.add(pair.substring(colon + 1)); }
            } catch (IllegalArgumentException invalid) { /* Already authenticated by the native owner. */ }
        }
        values.removeIf(Objects::isNull); values.sort(java.util.Comparator.comparingInt(String::length).reversed()); return values;
    }
    private static int lines(Object value) {
        if (!(value instanceof Number n) || n.longValue() != n.doubleValue() || n.longValue() < 1 || n.longValue() > 200)
            throw error(422, "ContainerLogsInputInvalid");
        return n.intValue();
    }
    private long id(Object value) {
        if (!(value instanceof String text)) throw error(422, "ContainerLogsInputInvalid");
        try {
            long id = targets.parseScopeId("container", text);
            if (id <= 0) throw error(422, "ContainerLogsInputInvalid");
            return id;
        }
        catch (ClientVisibleException | IllegalArgumentException invalid) { throw error(422, "ContainerLogsInputInvalid"); }
    }
    private static boolean sameNumber(Object value, long expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }
    private static String wire(String type, long id) { return String.valueOf(ApiContext.getContext().getIdFormatter().formatId(type, id)); }
    private static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code); }
}
