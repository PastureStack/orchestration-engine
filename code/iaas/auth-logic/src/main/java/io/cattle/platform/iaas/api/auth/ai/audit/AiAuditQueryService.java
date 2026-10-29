package io.cattle.platform.iaas.api.auth.ai.audit;

import io.cattle.platform.api.auth.ApiAuditPayload;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The Engine observes the complete Go-owner result before durably recording its canonical outcome. */
public class AiAuditQueryService {
    static final Set<String> INPUTS = Set.of("keyId", "environmentId", "from", "to", "lastMinutes", "decision",
            "outcome", "operation", "targetType", "requestId", "httpStatus", "limit", "offset", "eventId");
    static final Set<String> ROW_FIELDS = Set.of("type", "id", "created", "accountId", "authenticatedAsAccountId", "keyId",
            "eventType", "resourceType", "resourceId", "authType", "decision", "outcome", "responseCode", "requestId",
            "targetType", "targetId", "operation", "phase", "processId", "processName", "failureCode", "httpStatus",
            "policyRevision", "preview", "interactionChannel", "payloadsIncluded", "requestPayloadPresence", "responsePayloadPresence");
    @Inject AiKeyAuthorization keys;
    @Inject ApiKeyAuditSink audit;
    @Inject AiAuditQueryRunner runner;

    public Map<String, Object> read(ApiRequest request) {
        ApiAuditPayload.auditMetadataQuery(request);
        String id = "aiaudit-" + UUID.randomUUID(); Policy actor = ApiUtils.getPolicy();
        String outcome = "ERROR", reason = "AuditQueryUnavailable"; int count = 0;
        try {
            actor = keys.actor(request);
            if (actor == null || actor.getAuthenticatedAsAccountId() <= 0) throw error(403, "PermissionDenied");
            Map<String, Object> query = input(request.getRequestObject());
            var servlet = request.getServletContext();
            if (servlet == null || servlet.getRequest() == null) throw error(403, "PermissionDenied");
            var http = servlet.getRequest();
            ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
            Map<String, Object> privateInput = new LinkedHashMap<>();
            privateInput.put("query", query); privateInput.put("actorId", wire(actor.getAuthenticatedAsAccountId()));
            if (key != null) {
                String authorization = http.getHeader("Authorization");
                if (authorization == null || !authorization.startsWith("Basic ") || authorization.length() > 32768
                        || !Long.toString(key.revision()).equals(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)))
                    throw error(403, "ApiKeyPolicyChanged");
                privateInput.put("authorization", authorization); privateInput.put("revision", key.revision());
                privateInput.put("credentialId", String.valueOf(ApiContext.getContext().getIdFormatter().formatId("apiKey", key.credentialId())));
                privateInput.put("credentialKind", key.kind());
            } else {
                String cookie = http.getHeader("Cookie");
                if (cookie == null || cookie.isBlank() || cookie.length() > 32768 || http.getHeader("Authorization") != null)
                    throw error(403, "PermissionDenied");
                privateInput.put("cookie", cookie);
            }
            Map<String, Object> owner = runner.run(privateInput);
            if (!owner.keySet().equals(Set.of("status", "body")) || !integer(owner.get("status"), 100, 599))
                throw error(503, "AuditQueryUnavailable");
            int code = ((Number) owner.get("status")).intValue();
            if (code != 200) {
                if (code == 401 || code == 403) { outcome = "DENY"; throw error(code, "AuditQueryDenied"); }
                if (code == 400) throw error(422, "AuditQueryInputInvalid");
                if (code == 422) throw error(422, "AuditQueryNarrowQuery");
                if (code == 404) throw error(404, "AuditQueryEventUnavailable");
                throw error(503, "AuditQueryUnavailable");
            }
            Map<String, Object> result = safeResult(owner.get("body"));
            // Revalidate actor/credential after all pages, before admitting a complete result.
            Policy current = keys.actor(request);
            if (current.getAuthenticatedAsAccountId() != actor.getAuthenticatedAsAccountId()
                    || current.getAccountId() != actor.getAccountId()) throw error(403, "OwnerPermissionDenied");
            result.put("id", id); count = ((List<?>) result.get("data")).size();
            outcome = "ALLOW"; reason = "AuditQueryCompleted";
            return result;
        } catch (ClientVisibleException denied) {
            if (denied.getStatus() == 401 || denied.getStatus() == 403) outcome = "DENY";
            reason = denied.getCode(); throw denied;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw error(503, "AuditQueryUnavailable");
        } catch (Exception unavailable) { throw error(503, "AuditQueryUnavailable"); }
        finally {
            try { audit.recordAuditQueryOutcome(request, actor, id, outcome, reason, count); }
            catch (RuntimeException unavailable) { throw error(503, "AuditQueryAuditUnavailable"); }
        }
    }

    static Map<String, Object> input(Object value) {
        Map<String, Object> values = object(value); values.remove("type");
        if (!INPUTS.containsAll(values.keySet())) throw error(422, "AuditQueryInputInvalid");
        for (var entry : values.entrySet()) {
            Object field = entry.getValue();
            if (Set.of("lastMinutes", "httpStatus", "limit", "offset").contains(entry.getKey())) {
                if (!(field instanceof Number n) || n.longValue() != n.doubleValue() || n.longValue() < 0 || n.longValue() > 527040)
                    throw error(422, "AuditQueryInputInvalid");
            } else if (!(field instanceof String text) || text.length() > 160 || text.chars().anyMatch(c -> c < 32 || c == 127))
                throw error(422, "AuditQueryInputInvalid");
        }
        return values;
    }

    static Map<String, Object> safeResult(Object value) {
        Map<String, Object> body = object(value);
        if (!body.keySet().equals(Set.of("resourceType", "data", "total", "nextOffset", "range", "payloadPolicy"))
                || !"auditLog".equals(body.get("resourceType")) || !"HUMAN_ONLY_NATIVE_AUDIT_UI".equals(body.get("payloadPolicy"))
                || !(body.get("data") instanceof List<?> rows) || rows.size() > 50
                || !integer(body.get("total"), 0, 20000))
            throw error(503, "AuditQueryUnavailable");
        for (Object row : rows) {
            Map<String, Object> fields = object(row);
            if (!ROW_FIELDS.containsAll(fields.keySet()) || !"auditLog".equals(fields.get("type"))
                    || !Boolean.FALSE.equals(fields.get("payloadsIncluded")) || !(fields.get("id") instanceof String))
                throw error(503, "AuditQueryUnavailable");
            for (Object field : fields.values()) if (!(field instanceof String text && text.length() <= 512)
                    && !(field instanceof Number) && !(field instanceof Boolean)) throw error(503, "AuditQueryUnavailable");
        }
        Map<String, Object> range = object(body.get("range"));
        if (!range.keySet().equals(Set.of("from", "to")) || range.values().stream().anyMatch(v -> !(v instanceof String s) || s.length() > 64))
            throw error(503, "AuditQueryUnavailable");
        try {
            var from = java.time.OffsetDateTime.parse((String) range.get("from")).toInstant();
            var to = java.time.OffsetDateTime.parse((String) range.get("to")).toInstant();
            if (!from.isBefore(to) || java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(366)) > 0)
                throw error(503, "AuditQueryUnavailable");
        } catch (java.time.DateTimeException invalid) { throw error(503, "AuditQueryUnavailable"); }
        Object next = body.get("nextOffset");
        if (next != null && !integer(next, 0, 20000)) throw error(503, "AuditQueryUnavailable");
        return body;
    }

    static boolean integer(Object value, long minimum, long maximum) {
        return value instanceof Number n && n.longValue() == n.doubleValue() && n.longValue() >= minimum && n.longValue() <= maximum;
    }

    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw error(422, "AuditQueryInputInvalid");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw error(422, "AuditQueryInputInvalid");
            result.put(key, entry.getValue());
        }
        return result;
    }
    static String wire(long id) { return String.valueOf(ApiContext.getContext().getIdFormatter().formatId("account", id)); }
    static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
