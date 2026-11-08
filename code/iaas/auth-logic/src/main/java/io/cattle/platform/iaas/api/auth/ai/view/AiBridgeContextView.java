package io.cattle.platform.iaas.api.auth.ai.view;

import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Closed navigation identity only. These rows never authorize a Project or its business resources. */
final class AiBridgeContextView {
    record Candidate(String type, String id) { }
    record Project(String id, String uuid, String name, String state) { }
    private AiBridgeContextView() { }

    static void validate(Map<String, Object> input) {
        boolean bound = input.keySet().equals(Set.of("resourceType", "view", "resourceId"));
        boolean named = input.keySet().equals(Set.of("resourceType", "view", "query"));
        if (!"project".equals(input.get("resourceType")) || !"bridge_context".equals(input.get("view"))
                || !(bound && input.get("resourceId") instanceof String id && id.matches("[A-Za-z0-9_-]{1,128}")
                || named && input.get("query") instanceof String name && !name.isBlank() && name.length() <= 256))
            throw error(422, "ResourceViewInputInvalid");
    }

    static List<Candidate> candidates(ApiKeyPolicy policy) {
        if (policy.getMode() != ApiKeyPolicy.Mode.CUSTOM) return List.of();
        Set<Candidate> result = new LinkedHashSet<>();
        for (ApiKeyPolicy.Rule rule : policy.getRules()) {
            if (rule.effect() != ApiKeyPolicy.Effect.ALLOW || !rule.operations().contains("read")) continue;
            var scope = rule.scope();
            if (scope.kind() == ApiKeyPolicy.ScopeKind.STACK) result.add(new Candidate("stack", scope.resourceId()));
            else if (scope.kind() == ApiKeyPolicy.ScopeKind.RESOURCE && AiResourceViewService.READ_TYPES.contains(scope.resourceType())
                    && !Set.of("project", "projectTemplate", "account", "apiKey", "apiKeyRestricted", "setting",
                            "machineDriver", "processInstance", "processExecution", "processPool", "auditLog").contains(scope.resourceType()))
                result.add(new Candidate(scope.resourceType(), scope.resourceId()));
        }
        if (result.size() > 128) throw error(422, "ResourceViewNarrowQuery");
        return List.copyOf(result);
    }

    static void requireNoParentDeny(ApiKeyPolicy policy, String projectId) {
        if (policy.getMode() == ApiKeyPolicy.Mode.FULL) return;
        // Default DENY still bounds business access. Only an explicit read DENY
        // is tested here; a separate, freshly authorized child GET is mandatory.
        var explicit = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW,
                null, policy.getRules(), policy.getPurpose());
        var target = ApiKeyPolicyEvaluator.Target.projectResource("project", projectId, projectId);
        var decision = new ApiKeyPolicyEvaluator().evaluate(explicit,
                new ApiKeyPolicyEvaluator.Request(true, "read", true, List.of(target)), Instant.EPOCH);
        if (!decision.allowed()) throw error(403, "KeyPolicyDenied");
    }

    static boolean selected(Map<String, Object> input, Project project) {
        return input.containsKey("resourceId") ? input.get("resourceId").equals(project.id())
                : input.get("query").equals(project.name());
    }

    static Map<String, Object> envelope(List<Project> projects) {
        if (projects.size() > 50) throw error(422, "ResourceViewNarrowQuery");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Project project : projects) rows.add(Map.of("id", project.id(), "uuid", project.uuid(),
                "name", project.name(), "state", project.state()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "aiview-" + UUID.randomUUID()); result.put("resourceType", "project");
        result.put("data", rows); result.put("nextMarker", null);
        return result;
    }

    private static ClientVisibleException error(int status, String code) {
        return new ClientVisibleException(status, code, code, null);
    }
}
