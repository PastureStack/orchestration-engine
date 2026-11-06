package io.cattle.platform.iaas.api.auth.ai.view;

import io.cattle.platform.iaas.api.auth.apikey.ApiKeyOperations;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyCodec;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/** A read-only configured-policy projection; never an effective-authorization decision. */
final class AiApiKeyReadView {
    static final List<String> OPERATIONS = List.of("read", "create", "update", "upgrade", "delete", "exec", "logs", "export");
    private static final List<String> STACK_TYPES = List.of("stack", "service", "container", "volume");
    private static final List<String> PROJECT_TYPES = List.of("stack", "service", "container", "volume", "project", "host",
            "network", "secret", "certificate", "registry", "storagePool", "storagePoolHostMap", "networkPolicy", "projectMember");
    record Evidence(Resource target, SchemaFactory schemas) { }

    private AiApiKeyReadView() { }

    static Map<String, Object> project(Resource key, Schema schema, long actor,
            Function<ApiKeyPolicy.Scope, Evidence> readScope, Supplier<Evidence> refresh) {
        if (!readable(schema, "apiKeyPolicy") || !readable(schema, "apiKeyPolicyRevision"))
            return unavailable("POLICY_FIELDS_UNAVAILABLE");
        Map<String, Object> fields = key.getFields();
        ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
        final ApiKeyPolicy policy;
        final long revision;
        try {
            policy = codec.decode(fields.get("apiKeyPolicy"));
            if (!(fields.get("apiKeyPolicyRevision") instanceof Number number) || number.longValue() < 0
                    || number.longValue() != number.doubleValue()) return unavailable("POLICY_FIELDS_UNAVAILABLE");
            revision = number.longValue();
        } catch (IllegalArgumentException invalid) { return unavailable("POLICY_NOT_CANONICAL"); }
        boolean ownerKnown = readable(schema, "accountId") && Objects.equals(fields.get("accountId"),
                String.valueOf(ApiContext.getContext().getIdFormatter().formatId("account", actor)));
        Map<ApiKeyPolicy.Scope, Evidence> evidence = new LinkedHashMap<>();
        List<Map<String, Object>> rules = new ArrayList<>();
        boolean complete = true;
        for (ApiKeyPolicy.Rule rule : policy.getRules()) {
            ApiKeyPolicy.Scope scope = rule.scope();
            Map<String, Object> projectedScope = new LinkedHashMap<>();
            projectedScope.put("kind", lower(scope.kind()));
            String type = type(scope);
            if (type != null) projectedScope.put("resourceType", type);
            Evidence observed = null;
            boolean global = scope.kind() == ApiKeyPolicy.ScopeKind.GLOBAL;
            if (!global && type != null) {
                // Includes failed lookups: repeated rules never repeat a native read.
                if (!evidence.containsKey(scope)) evidence.put(scope, readScope.apply(scope));
                observed = evidence.get(scope);
            }
            Map<String, Object> target = observed == null ? null : target(observed, scope, type);
            boolean resolved = global || target != null;
            projectedScope.put("availability", resolved ? "resolved" : "unavailable");
            projectedScope.put("reason", resolved ? "NONE" : "SCOPE_TARGET_UNAVAILABLE");
            if (target != null) projectedScope.put("target", target);
            Map<String, Object> item = new LinkedHashMap<>();
            // Native rule labels are not authority and may contain unbounded text.
            // Ordinals retain every configured rule without leaking arbitrary labels.
            item.put("ordinal", rules.size() + 1); item.put("effect", lower(rule.effect()));
            item.put("operations", rule.operations().stream().sorted().toList()); item.put("scope", projectedScope);
            item.put("ownerCapabilities", capabilities(scope, target == null ? null : observed, ownerKnown));
            rules.add(item); complete &= resolved;
        }
        if (!evidence.isEmpty()) {
            Evidence fresh = refresh.get();
            Resource current = fresh == null ? null : fresh.target();
            Schema currentSchema = fresh == null || fresh.schemas() == null ? null : fresh.schemas().getSchema(key.getType());
            if (!readable(currentSchema, "apiKeyPolicy") || !readable(currentSchema, "apiKeyPolicyRevision"))
                throw new ClientVisibleException(403, "OwnerPermissionDenied", "Configured policy is not readable", null);
            if (current == null || !key.getId().equals(current.getId()) || !key.getType().equals(current.getType())
                    || !Objects.equals(fields.get("accountId"), current.getFields().get("accountId"))
                    || !(current.getFields().get("apiKeyPolicyRevision") instanceof Number number)
                    || number.longValue() != revision || !codec.encode(policy).equals(current.getFields().get("apiKeyPolicy")))
                throw new ClientVisibleException(409, "ApiKeyPolicyRevisionConflict", "Configured policy changed during read", null);
        }
        Map<String, Object> configured = new LinkedHashMap<>();
        configured.put("mode", lower(policy.getMode())); configured.put("defaultEffect", lower(policy.getDefaultEffect()));
        configured.put("purpose", policy.getPurpose() == ApiKeyPolicy.Purpose.AI_BRIDGE ? "ai-bridge" : "general");
        configured.put("expiresAt", policy.getExpiresAt() == null ? null : policy.getExpiresAt().toString());
        configured.put("rules", rules);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("availability", "available"); result.put("representation", "configured-policy-read-only");
        result.put("revision", revision); result.put("configuredPolicy", configured);
        result.put("defaultOwnerCapabilities", capabilities(new ApiKeyPolicy.Scope(ApiKeyPolicy.ScopeKind.GLOBAL, null, null), null, ownerKnown));
        result.put("allConfiguredRulesIncluded", true); result.put("scopePreviewComplete", complete);
        result.put("effectiveAuthorization", "not-evaluated"); result.put("previewComplete", false);
        result.put("reason", complete ? "OWNER_CEILING_IS_NOT_EFFECTIVE_AUTHORIZATION" : "SCOPE_PREVIEW_INCOMPLETE");
        return result;
    }

    static String type(ApiKeyPolicy.Scope scope) {
        String type = switch (scope.kind()) {
            case PROJECT -> "project";
            case STACK -> "stack";
            case RESOURCE -> scope.resourceType();
            default -> null;
        };
        return AiResourceViewService.READ_TYPES.contains(type == null ? "" : type) ? type : null;
    }

    static boolean readable(Schema schema, String name) {
        var field = schema == null || schema.getResourceFields() == null ? null : schema.getResourceFields().get(name);
        return field != null && field.isIncludeInList() && !field.isReadOnCreateOnly()
                && (field.getTransform() == null || field.getTransform().isBlank());
    }

    private static Map<String, Object> target(Evidence evidence, ApiKeyPolicy.Scope scope, String type) {
        Resource resource = evidence.target();
        Schema schema = evidence.schemas() == null ? null : evidence.schemas().getSchema(type);
        if (resource == null || !type.equals(resource.getType()) || resource.getId() == null
                || !resource.getId().equals(scope.resourceId()) || !readable(schema, "name")) return null;
        Map<String, Object> safe = new LinkedHashMap<>(); safe.put("id", resource.getId()); safe.put("type", type);
        for (String field : List.of("name", "accountId", "stackId", "stackName", "parentResolution")) {
            Object value = resource.getFields().get(field);
            if (readable(schema, field) && value instanceof String text && text.length() <= 512
                    && text.chars().noneMatch(c -> c < 32 || c == 127)) safe.put(field, text);
        }
        return safe;
    }

    private static Map<String, Object> capabilities(ApiKeyPolicy.Scope scope, Evidence evidence, boolean ownerKnown) {
        List<Map<String, String>> states = new ArrayList<>();
        List<String> types = scope.kind() == ApiKeyPolicy.ScopeKind.STACK ? STACK_TYPES
                : scope.kind() == ApiKeyPolicy.ScopeKind.PROJECT ? PROJECT_TYPES : List.of(type(scope) == null ? "" : type(scope));
        boolean known = ownerKnown && evidence != null && evidence.schemas() != null && scope.kind() != ApiKeyPolicy.ScopeKind.GLOBAL
                && types.stream().allMatch(type -> evidence.schemas().getSchema(type) != null);
        for (String operation : OPERATIONS) {
            String status = "unknown";
            if (known && !(scope.kind() == ApiKeyPolicy.ScopeKind.RESOURCE && "create".equals(operation))) {
                List<String> observed = types.stream().map(type -> state(evidence.schemas().getSchema(type),
                        scope.kind() == ApiKeyPolicy.ScopeKind.RESOURCE ? evidence.target() : null, operation, type)).toList();
                status = observed.contains("observed") ? "observed" : observed.stream().allMatch("unavailable"::equals) ? "unavailable" : "unknown";
            }
            states.add(Map.of("operation", operation, "status", status));
        }
        return Map.of("basis", "native-owner-ceiling", "effectiveAuthorization", "not-evaluated", "operations", states,
                "reason", !ownerKnown ? "KEY_OWNER_CONTEXT_UNAVAILABLE" : !known ? "SCOPE_CAPABILITY_METADATA_UNAVAILABLE"
                        : "OWNER_CEILING_IS_NOT_EFFECTIVE_AUTHORIZATION");
    }

    private static String state(Schema schema, Resource target, String operation, String type) {
        List<String> methods = "create".equals(operation) ? schema.getCollectionMethods() : schema.getResourceMethods();
        if (methods == null) return "unknown";
        String verb = switch (operation) { case "create" -> "POST"; case "read" -> "GET"; case "update" -> "PUT"; case "delete" -> "DELETE"; default -> null; };
        if (verb != null && (methods.contains(verb) || "read".equals(operation) && methods.contains("HEAD")
                || "update".equals(operation) && methods.contains("PATCH"))) return "observed";
        var declared = schema.getResourceActions();
        if (declared == null) return "unknown";
        Set<String> actions = target == null ? declared.keySet() : target.getActions() == null ? Set.of() : target.getActions().keySet();
        if (actions.stream().anyMatch(name -> operation.equals(ApiKeyOperations.of("POST", name, null, type).id()))) return "observed";
        if (target != null && target.getLinks() != null && target.getLinks().keySet().stream()
                .anyMatch(name -> operation.equals(ApiKeyOperations.of("GET", null, name, type).id()))) return "observed";
        if (declared.keySet().stream().anyMatch(name -> operation.equals(ApiKeyOperations.of("POST", name, null, type).id()))) return "unknown";
        // Java Schema has no closed resourceLinks declaration. Missing links
        // cannot prove absence of exec/logs/export capability.
        if (Set.of("exec", "logs", "export").contains(operation)) return "unknown";
        return "unavailable";
    }

    private static Map<String, Object> unavailable(String reason) {
        return Map.of("availability", "unavailable", "representation", "configured-policy-read-only", "previewComplete", false,
                "reason", reason, "effectiveAuthorization", "not-evaluated");
    }

    private static String lower(Enum<?> value) { return value.name().toLowerCase(java.util.Locale.ROOT); }
}
