package io.cattle.platform.iaas.api.auth.ai.view;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Pagination;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.Sort;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Metadata is projected on the server before it reaches a dedicated AI Key.
 * Each read still traverses its ordinary, freshly authorized domain manager.
 */
public class AiResourceViewService {
    public static final String DOMAIN_READ_ATTRIBUTE = "ai.resource.view.domain.read";
    public static final Set<String> READ_TYPES = Set.of("project", "projectTemplate", "account", "stack", "service",
            "loadBalancerService", "dnsService", "externalService", "container", "virtualMachine",
            "host", "volume", "snapshot", "backup", "backupTarget", "storagePool", "storageDriver",
            "network", "networkDriver", "certificate", "registry", "registryCredential", "apiKey",
            "apiKeyRestricted", "setting", "machineDriver", "secret", "processExecution", "processPool", "auditLog");
    private static final Set<String> GLOBAL_TYPES = Set.of("project", "projectTemplate", "account", "apiKey", "apiKeyRestricted",
            "setting", "machineDriver", "processExecution", "processPool", "auditLog");
    private static final Set<String> MEMORY_COLLECTIONS = Set.of("project", "setting", "processPool");
    private static final List<String> COMMON_FIELDS = List.of("name", "description", "state", "kind", "created", "healthState");
    private static final Map<String, List<String>> FIELDS = Map.ofEntries(
            Map.entry("project", List.of("orchestration")),
            Map.entry("service", List.of("scale", "stackId")),
            Map.entry("loadBalancerService", List.of("scale", "stackId")),
            Map.entry("dnsService", List.of("stackId")), Map.entry("externalService", List.of("stackId")),
            Map.entry("container", List.of("imageUuid", "memory", "cpuShares", "startCount", "networkMode")),
            Map.entry("virtualMachine", List.of("imageUuid", "memory", "cpuShares")),
            Map.entry("host", List.of("hostname", "agentIpAddress", "dockerVersion", "memTotal", "cpuCount")),
            Map.entry("volume", List.of("driver", "accessMode", "size")),
            Map.entry("certificate", List.of("cn", "expiresAt", "issuedAt")),
            Map.entry("registry", List.of()), Map.entry("registryCredential", List.of()),
            Map.entry("apiKey", List.of("apiKeyPolicyRevision")),
            Map.entry("apiKeyRestricted", List.of("apiKeyPolicyRevision")),
            Map.entry("setting", List.of("source", "inDb")),
            Map.entry("machineDriver", List.of("builtin", "active")),
            Map.entry("processExecution", List.of("processName", "startTime", "endTime", "exitReason")),
            Map.entry("processPool", List.of("activeTasks", "poolSize", "queueSize")),
            Map.entry("auditLog", List.of("eventType", "resourceType", "responseCode", "httpStatus", "authType",
                    "interactionChannel", "decision", "outcome", "operation")));

    @Inject AiKeyAuthorization keyAuthorization;
    @Inject ApiKeyAuthorizationService keyAccess;
    @Inject ApiAuthenticator authenticator;
    @Inject ResourceManagerLocator managers;

    public Map<String, Object> read(ApiRequest request) {
        Map<String, Object> input = input(request);
        String type = (String) input.get("resourceType");
        Policy current = keyAuthorization.actor(request);
        if (current == null || current.getAuthenticatedAsAccountId() <= 0) throw error(403, "PermissionDenied");
        long principal = current.getAuthenticatedAsAccountId();
        long account = GLOBAL_TYPES.contains(type) ? principal : current.getAccountId();
        ApiRequest domain = new ApiRequest(request);
        domain.setRequestServletContext(request.getServletContext());
        domain.setType(type); domain.setId(input.get("resourceId") == null || "processPool".equals(type)
                ? null : rawId((String) input.get("resourceId")).toString());
        domain.setMethod("GET"); domain.setAction(null); domain.setLink(null); domain.setRequestObject(null);
        domain.setRequestParams(new LinkedHashMap<>()); domain.setConditions(new LinkedHashMap<>());
        domain.setInclude(null);
        domain.setSort(new Sort("id", Sort.SortOrder.ASC, null));
        domain.setPagination(new Pagination(MEMORY_COLLECTIONS.contains(type) ? 10001 : 51));
        // ApiRequest's copy constructor gives attributes a fresh map. This is
        // an internal marker, never derived from an HTTP header or input map.
        domain.setAttribute(DOMAIN_READ_ATTRIBUTE, Boolean.TRUE);
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key != null) ApiKeyCredentialContext.attach(domain, key);
        var authority = authenticator.currentAuthorization(principal, account, domain);
        domain.setSchemaFactory(authority.schemas());
        Schema schema = authority.schemas().getSchema(type);
        List<String> methods = schema == null ? null : domain.getId() == null ? schema.getCollectionMethods() : schema.getResourceMethods();
        if (methods == null || !methods.contains("GET")) throw error(403, "OwnerPermissionDenied");
        ApiContext context = ApiContext.getContext();
        ApiRequest previous = context.getApiRequest(); Object previousPolicy = context.getPolicy();
        context.setApiRequest(domain); context.setPolicy(authority.policy());
        try {
            keyAccess.authorize(domain, authority.policy());
            return fetch(domain, input);
        } finally {
            context.setApiRequest(previous); context.setPolicy(previousPolicy);
        }
    }

    protected Map<String, Object> fetch(ApiRequest domain, Map<String, Object> input) {
        String type = domain.getType();
        ResourceManager manager = managers.getResourceManagerByType(type);
        if (manager == null) throw error(404, "NotFound");
        String parent = (String) input.get("parentId");
        if (parent != null) {
            if (!Set.of("service", "loadBalancerService", "dnsService", "externalService").contains(type))
                throw error(422, "ResourceViewInputInvalid");
            // Parent references are never a substitute for child authorization.
            domain.getConditions().put("stackId", List.of(new Condition(ConditionType.EQ, rawId(parent))));
        }
        String marker = (String) input.get("marker");
        if (marker != null && !MEMORY_COLLECTIONS.contains(type))
            domain.getConditions().put("id", List.of(new Condition(ConditionType.GT, rawId(marker))));
        List<?> rows;
        if (domain.getId() != null) {
            Object row = manager.getById(type, domain.getId(), new ListOptions(domain));
            if (row == null) throw error(404, "NotFound");
            rows = List.of(row);
        } else {
            Object listed = manager.list(type, domain);
            if (!(listed instanceof List<?> list) || list.size() > 10000) throw error(422, "ResourceViewNarrowQuery");
            rows = list;
        }
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Object row : rows) {
            Resource resource = manager.convertResponse(row, domain);
            if (resource == null) throw error(503, "ResourceViewUnavailable");
            String id = "processPool".equals(type) ? poolId(resource.getFields()) : resource.getId();
            if (id == null) throw error(503, "ResourceViewUnavailable");
            Map<String, Object> safe = project(type, id, resource.getFields());
            if ("processPool".equals(type) && (!(safe.get("name") instanceof String name) || name.isBlank()))
                safe.put("name", "Process pool " + id.substring(5, 17));
            if (marker == null || compareIds((String) safe.get("id"), marker) > 0) visible.add(safe);
        }
        if ("processPool".equals(type) && input.get("resourceId") instanceof String selected) {
            visible.removeIf(item -> !selected.equals(item.get("id")));
            if (visible.size() != 1) throw error(404, "NotFound");
        }
        if (MEMORY_COLLECTIONS.contains(type)) visible.sort((a, b) -> compareIds((String) a.get("id"), (String) b.get("id")));
        boolean hasMore = visible.size() > 50;
        List<Map<String, Object>> page = visible.subList(0, Math.min(50, visible.size()));
        String next = hasMore ? (String) page.getLast().get("id") : null;
        if (Set.of("service", "loadBalancerService", "dnsService", "externalService").contains(type)) enrichParents(domain, page);
        String query = ((String) input.getOrDefault("query", "")).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matching = page.stream().filter(row -> query.isEmpty()
                || ((String) row.getOrDefault("name", "")).toLowerCase(Locale.ROOT).contains(query)).toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", "aiview-" + UUID.randomUUID()); output.put("resourceType", type);
        output.put("data", matching); output.put("nextMarker", next);
        return output;
    }

    /** One authorized parent query per page, rather than one request per candidate. */
    private void enrichParents(ApiRequest domain, List<Map<String, Object>> page) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> item : page) {
            Object id = item.get("stackId");
            if (id instanceof String || id instanceof Number) ids.add(id.toString());
            item.put("parentResolution", "unavailable");
        }
        if (ids.isEmpty()) return;
        Schema schema = domain.getSchemaFactory().getSchema("stack");
        if (schema == null || schema.getCollectionMethods() == null || !schema.getCollectionMethods().contains("GET")) return;
        ApiRequest parents = new ApiRequest(domain);
        parents.setRequestServletContext(domain.getServletContext());
        parents.setType("stack"); parents.setId(null);
        parents.setConditions(new LinkedHashMap<>());
        parents.getConditions().put("id", List.of(new Condition(ConditionType.IN, ids.stream().map(AiResourceViewService::rawId).toList())));
        parents.setPagination(new Pagination(50));
        parents.setAttribute(DOMAIN_READ_ATTRIBUTE, Boolean.TRUE);
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(domain);
        if (key != null) ApiKeyCredentialContext.attach(parents, key);
        ApiContext context = ApiContext.getContext();
        context.setApiRequest(parents);
        try {
            keyAccess.authorize(parents, ApiUtils.getPolicy());
            ResourceManager manager = managers.getResourceManagerByType("stack");
            if (manager == null) return;
            Object result = manager.list("stack", parents);
            if (!(result instanceof List<?> rows) || rows.size() > 50) throw error(503, "ResourceViewUnavailable");
            Map<String, String> names = new LinkedHashMap<>();
            Set<String> requested = new LinkedHashSet<>();
            for (String id : ids) requested.add(rawId(id).toString());
            for (Object row : rows) {
                Resource resource = manager.convertResponse(row, parents);
                if (resource == null || resource.getId() == null) continue;
                String id = rawId(resource.getId()).toString();
                Object name = resource.getFields().get("name");
                if (requested.contains(id) && name instanceof String text) names.put(id, text.substring(0, Math.min(text.length(), 512)));
            }
            for (Map<String, Object> item : page) {
                Object parent = item.get("stackId");
                String name = parent == null ? null : names.get(rawId(parent.toString()).toString());
                if (name != null) { item.put("stackName", name); item.put("parentResolution", "resolved"); }
            }
        } catch (ClientVisibleException denied) {
            // A child read grant need not include a parent name. Revocation,
            // expiry, revision changes and other failures still invalidate it.
            if (!Set.of("KeyScopeDenied", "KeyPolicyDenied", "OwnerPermissionDenied", "NotFound").contains(denied.getCode())) throw denied;
        } finally {
            context.setApiRequest(domain);
        }
    }

    /** No recursive copy: nested credentials, values, payloads and launchConfig stay inside the server. */
    public static Map<String, Object> project(String type, String id, Map<String, Object> fields) {
        if (!READ_TYPES.contains(type) || id == null || fields == null) throw error(503, "ResourceViewUnavailable");
        Map<String, Object> safe = new LinkedHashMap<>(); safe.put("id", id); safe.put("type", type);
        for (String field : COMMON_FIELDS) scalar(safe, fields, field);
        for (String field : FIELDS.getOrDefault(type, List.of())) scalar(safe, fields, field);
        if (Set.of("service", "loadBalancerService", "dnsService", "externalService").contains(type)
                && fields.get("launchConfig") instanceof Map<?, ?> launch && launch.get("imageUuid") instanceof String image)
            safe.put("imageUuid", image.substring(0, Math.min(image.length(), 512)));
        if (Set.of("apiKey", "apiKeyRestricted").contains(type) && fields.get("apiKeyPolicy") instanceof Map<?, ?> policy) {
            for (String field : List.of("mode", "purpose", "expiresAt")) {
                Object value = policy.get(field);
                if (value instanceof String text && text.length() <= 128) safe.put(field, text);
            }
        }
        return safe;
    }

    private static void scalar(Map<String, Object> output, Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (value instanceof String text) output.put(field, text.substring(0, Math.min(text.length(), "description".equals(field) ? 1024 : 512)));
        else if (value instanceof Boolean || value instanceof Number) output.put(field, value);
    }

    private static Map<String, Object> input(ApiRequest request) {
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        if (!Set.of("type", "resourceType", "resourceId", "query", "marker", "parentId").containsAll(input.keySet())
                || !(input.get("resourceType") instanceof String type) || !READ_TYPES.contains(type)) throw error(422, "ResourceViewInputInvalid");
        for (String key : List.of("resourceId", "marker", "parentId")) {
            Object value = input.get(key);
            if (value != null && (!(value instanceof String text) || !text.matches("[A-Za-z0-9_.-]{1,128}"))) throw error(422, "ResourceViewInputInvalid");
        }
        if (input.containsKey("query") && (!(input.get("query") instanceof String query) || query.length() > 256)) throw error(422, "ResourceViewInputInvalid");
        if (input.get("resourceId") != null && (input.get("marker") != null || input.get("query") != null || input.get("parentId") != null))
            throw error(422, "ResourceViewInputInvalid");
        return input;
    }

    private static Object rawId(String id) {
        Object value = ApiContext.getContext().getIdFormatter().parseId(id);
        return value == null ? id : value;
    }

    private static int compareIds(String left, String right) {
        Object a = rawId(left), b = rawId(right);
        try { return Long.compare(Long.parseLong(a.toString()), Long.parseLong(b.toString())); }
        catch (NumberFormatException nonnumeric) { return left.compareTo(right); }
    }

    // ProcessPoolManager exposes an owner-supplied bean identity, not a persisted
    // object with an ID. Its detail is resolved only inside the same authorized
    // collection, never by treating this digest as a database ID or permission.
    private static String poolId(Map<String, Object> fields) {
        if (!(fields.get("poolKey") instanceof String key) || key.isBlank() || key.length() > 512
                || key.chars().anyMatch(value -> value < 32 || value == 127)) throw error(503, "ResourceViewUnavailable");
        try { return "pool-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("process-pool-bean:" + key).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw error(503, "ResourceViewUnavailable"); }
    }

    private static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
