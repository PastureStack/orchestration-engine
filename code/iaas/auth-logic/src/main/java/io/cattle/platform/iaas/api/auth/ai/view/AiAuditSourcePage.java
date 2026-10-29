package io.cattle.platform.iaas.api.auth.ai.view;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Pagination;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Sort;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fixed source pages for the existing Go audit query owner. Authorization and
 * filtered native GET dispatch remain in AiResourceViewService; this helper
 * cannot select a URL, mutation, grant, arbitrary field or database query.
 */
final class AiAuditSourcePage {
    private static final Set<String> SOURCES = Set.of("project", "account", "apiKey", "auditLog");
    private static final List<String> AUDIT_FIELDS = List.of("created", "accountId", "authenticatedAsAccountId",
            "eventType", "resourceType", "resourceId", "authType", "eventId", "keyId", "decision", "outcome",
            "httpStatus", "responseCode", "requestId", "targetType", "targetId", "operation", "policyRevision",
            "phase", "preview", "processId", "processName", "failureCode", "interactionChannel");
    final String category, type, resourceId, projectId, accountId, marker;
    final Instant from, to;
    final int limit;

    private AiAuditSourcePage(Map<String, Object> input, Map<String, Object> page) {
        category = (String) input.get("resourceType");
        require(SOURCES.contains(category));
        require(Set.of("type", "resourceType", "resourceId", "sourcePage").containsAll(input.keySet()));
        require(Set.of("type", "limit", "marker", "projectId", "accountId", "from", "to", "keyKind").containsAll(page.keySet()));
        require(!page.containsKey("type") || "aiAuditSourcePageInput".equals(page.get("type")));
        String keyKind = text(page, "keyKind", 32);
        require(keyKind == null || "apiKey".equals(category) && Set.of("apiKey", "apiKeyRestricted").contains(keyKind));
        type = keyKind == null ? category : keyKind;
        resourceId = text(input, "resourceId", 128);
        require("apiKey".equals(category) == (resourceId != null));
        if (resourceId != null) number(type, resourceId);
        projectId = text(page, "projectId", 128);
        accountId = text(page, "accountId", 128);
        marker = text(page, "marker", 128);
        if (projectId != null) { require(Set.of("apiKey", "auditLog").contains(category)); number("project", projectId); }
        if (accountId != null) { require("auditLog".equals(category)); number("account", accountId); }
        if (marker != null) { require(resourceId == null); number(type, marker); }
        Object size = page.getOrDefault("limit", 1000);
        require(size instanceof Number n && n.longValue() == n.doubleValue() && n.longValue() > 0 && n.longValue() <= 1000);
        limit = ((Number) size).intValue();
        String start = text(page, "from", 64), end = text(page, "to", 64);
        if ("auditLog".equals(category)) {
            require(start != null && end != null);
            try { from = OffsetDateTime.parse(start).toInstant(); to = OffsetDateTime.parse(end).toInstant(); }
            catch (java.time.DateTimeException invalid) { throw error(422, "ResourceViewInputInvalid"); }
            require(from.isBefore(to) && Duration.between(from, to).compareTo(Duration.ofDays(366)) <= 0);
        } else {
            require(start == null && end == null && accountId == null);
            from = null; to = null;
        }
    }

    static AiAuditSourcePage parse(Map<String, Object> input) {
        return new AiAuditSourcePage(input, object(input.get("sourcePage")));
    }

    void configure(ApiRequest domain) {
        boolean descending = "auditLog".equals(category);
        domain.setSort(new Sort("id", descending ? Sort.SortOrder.DESC : Sort.SortOrder.ASC, null));
        domain.setPagination(new Pagination("project".equals(category) ? 10001 : limit + 1));
        if (marker != null)
            domain.getConditions().put("id", List.of(new Condition(descending ? ConditionType.LT : ConditionType.GT, number(type, marker))));
        if ("account".equals(category))
            domain.getConditions().put("kind", List.of(new Condition(ConditionType.NOTIN, List.of("service", "agent"))));
        if (from != null) {
            domain.getConditions().put("created", List.of(new Condition(ConditionType.GTE, Timestamp.from(from)),
                    new Condition(ConditionType.LTE, Timestamp.from(to))));
        }
        if (accountId != null)
            domain.getConditions().put("accountId", List.of(new Condition(ConditionType.EQ, number("account", accountId))));
    }

    Map<String, Object> fetch(ResourceManager manager, ApiRequest domain, Policy owner) {
        Object result = resourceId == null ? manager.list(type, domain) : manager.getById(type, domain.getId(), new ListOptions(domain));
        if (result == null) throw error(404, "NotFound");
        List<?> rows;
        if (resourceId != null) rows = List.of(result);
        else if (result instanceof List<?> list) rows = list;
        else throw error(503, "ResourceViewUnavailable");
        if (rows.size() > ("project".equals(category) ? 10000 : limit + 1)) throw error(422, "ResourceViewNarrowQuery");
        List<Map<String, Object>> projected = new ArrayList<>();
        for (Object row : rows) {
            Resource resource = manager.convertResponse(row, domain);
            if (resource == null || resource.getId() == null) throw error(503, "ResourceViewUnavailable");
            if (!type.equals(resource.getType()) || resourceId != null && !resourceId.equals(resource.getId()))
                throw error(403, "OwnerPermissionDenied");
            Map<String, Object> safe = project(resource, domain);
            if (marker == null || ("auditLog".equals(category)
                    ? number(type, (String) safe.get("id")) < number(type, marker)
                    : number(type, (String) safe.get("id")) > number(type, marker))) projected.add(safe);
        }
        Comparator<Map<String, Object>> order = Comparator.comparingLong(row -> number(type, (String) row.get("id")));
        projected.sort("auditLog".equals(category) ? order.reversed() : order);
        boolean more = projected.size() > limit;
        List<Map<String, Object>> data = projected.subList(0, Math.min(limit, projected.size()));
        Map<String, Object> authority = new LinkedHashMap<>();
        authority.put("actorId", wire("account", owner.getAuthenticatedAsAccountId()));
        authority.put("accountId", wire("account", owner.getAccountId()));
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(domain);
        if (key != null) {
            authority.put("credentialId", wire("apiKey", key.credentialId()));
            authority.put("credentialRevision", key.revision()); authority.put("credentialKind", key.kind());
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", "aiview-" + UUID.randomUUID()); output.put("resourceType", type); output.put("data", data);
        output.put("nextMarker", more ? data.getLast().get("id") : null);
        output.put("nativeAuthority", authority); output.put("sourcePageResult", true);
        return output;
    }

    private Map<String, Object> project(Resource resource, ApiRequest domain) {
        String id = resource.getId(); number(type, id);
        Map<String, Object> fields = resource.getFields();
        Map<String, Object> safe = new LinkedHashMap<>(); safe.put("id", id); safe.put("type", type);
        List<String> allowed = switch (category) {
            case "project" -> List.of("name", "displayName", "kind");
            case "account" -> List.of("name", "username", "kind");
            case "apiKey" -> List.of("accountId", "apiKeyPolicyRevision");
            default -> AUDIT_FIELDS;
        };
        for (String field : allowed) {
            // The normal schema projection must expose it before this narrower
            // source projection may copy it. No raw data/payload fallback exists.
            Object value = fields.get(field);
            if (value instanceof String text && text.length() <= 512 && text.chars().noneMatch(c -> c < 32 || c == 127))
                safe.put(field, text);
            else if (value instanceof Boolean || value instanceof Number) safe.put(field, value);
            else if ("created".equals(field) && value instanceof Date date) safe.put(field, date.toInstant().toString());
        }
        if (Set.of("apiKey", "auditLog").contains(category) && !(safe.get("accountId") instanceof String))
            throw error(403, "OwnerPermissionDenied");
        if ("auditLog".equals(category) && !safe.containsKey("created")) throw error(403, "OwnerPermissionDenied");
        return safe;
    }

    static long number(String type, String id) {
        String value = ApiContext.getContext().getIdFormatter().parseId(id);
        require(value != null && value.matches("[1-9][0-9]*"));
        try {
            long numeric = Long.parseLong(value);
            require(wire(type, numeric).equals(id)); return numeric;
        } catch (NumberFormatException invalid) { throw error(422, "ResourceViewInputInvalid"); }
    }

    static String wire(String type, long id) {
        return String.valueOf(ApiContext.getContext().getIdFormatter().formatId(type, id));
    }

    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>);
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : ((Map<?, ?>) value).entrySet()) {
            require(entry.getKey() instanceof String); result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static String text(Map<String, Object> values, String key, int maximum) {
        if (!values.containsKey(key)) return null;
        Object value = values.get(key);
        require(value instanceof String text && !text.isBlank() && text.length() <= maximum);
        return (String) value;
    }

    private static void require(boolean value) { if (!value) throw error(422, "ResourceViewInputInvalid"); }
    private static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
