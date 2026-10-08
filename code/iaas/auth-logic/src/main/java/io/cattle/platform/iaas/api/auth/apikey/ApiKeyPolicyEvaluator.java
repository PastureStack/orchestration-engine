package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Effect;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Mode;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Rule;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Scope;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure decision core shared by HTTP, background work and delegated connections.
 * The caller must supply current RBAC and authoritative resource ancestry.
 * UI fields, request payload IDs and cached role names are not trusted inputs.
 */
public final class ApiKeyPolicyEvaluator {

    public enum Reason {
        OWNER_DENIED("OwnerPermissionDenied"),
        EXPIRED("ApiKeyExpired"),
        POLICY_DENIED("KeyPolicyDenied"),
        SCOPE_DENIED("KeyScopeDenied"),
        UNKNOWN_OPERATION("UnknownOperation"),
        FULL_ACCESS("KeyFullAccess"),
        RULE_ALLOWED("KeyRuleAllowed"),
        DEFAULT_ALLOWED("KeyDefaultAllowed");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public enum TargetLevel { UNRESOLVED, PLATFORM, PROJECT, STACK }

    /**
     * Stable identities and level must come from the authoritative resolver.
     * PLATFORM means verified absence of project/stack ancestry, not missing data.
     * These targets represent existing resources. Before create, evaluate the
     * authoritative parent/destination resources, never invent a child ID.
     * Mapping create semantics is the responsibility of the operation adapter.
     */
    public record Target(String resourceType, String resourceId, String projectId,
                         String stackId, TargetLevel level) {
        public Target {
            Objects.requireNonNull(level, "target level");
            ApiKeyPolicy.requireIdentifier(resourceType, "target resource type");
            ApiKeyPolicy.requireIdentifier(resourceId, "target resource ID");
            if (level == TargetLevel.PROJECT || level == TargetLevel.STACK) {
                ApiKeyPolicy.requireIdentifier(projectId, "target project ID");
            } else if (projectId != null) {
                throw new IllegalArgumentException("Target level has no project ancestry");
            }
            if (level == TargetLevel.STACK) {
                ApiKeyPolicy.requireIdentifier(stackId, "target stack ID");
            } else if (stackId != null) {
                throw new IllegalArgumentException("Target level has no stack ancestry");
            }
        }

        public static Target unresolved(String type, String id) {
            return new Target(type, id, null, null, TargetLevel.UNRESOLVED);
        }

        public static Target platformResource(String type, String id) {
            return new Target(type, id, null, null, TargetLevel.PLATFORM);
        }

        public static Target projectResource(String type, String id, String projectId) {
            return new Target(type, id, projectId, null, TargetLevel.PROJECT);
        }

        public static Target stackResource(String type, String id, String projectId, String stackId) {
            return new Target(type, id, projectId, stackId, TargetLevel.STACK);
        }
    }

    /**
     * ownerAllowed is the live RBAC result for this operation and ALL targets,
     * not account activity, membership or a grant stored when the Key was issued.
     * Include every authoritative source/destination target; collection query
     * predicates require a separate pre-pagination adapter, not an empty target list.
     */
    public record Request(boolean ownerAllowed, String operation, boolean operationRegistered,
                          List<Target> targets) {
        public Request {
            ApiKeyPolicy.requireIdentifier(operation, "canonical operation");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        }
    }

    /**
     * ALLOW is not execution success. Matching IDs are deduplicated in policy
     * order, including both grants and denials; owner/expiry/full/closed skip rules.
     */
    public record Decision(boolean allowed, Reason reason, List<String> matchedRuleIds) {
        public Decision {
            Objects.requireNonNull(reason, "decision reason");
            matchedRuleIds = List.copyOf(matchedRuleIds);
        }
    }

    public Decision evaluate(ApiKeyPolicy policy, Request request, Instant now) {
        Objects.requireNonNull(policy, "policy; legacy absence must be handled explicitly");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "clock instant");
        if (!request.ownerAllowed()) {
            return deny(Reason.OWNER_DENIED);
        }
        if (policy.getExpiresAt() != null && !now.isBefore(policy.getExpiresAt())) {
            return deny(Reason.EXPIRED);
        }
        // Full must not be limited by a new registry, resolver or stale custom rules.
        if (policy.getMode() == Mode.FULL) {
            return new Decision(true, Reason.FULL_ACCESS, List.of());
        }
        if (policy.getMode() == Mode.CLOSED) {
            return deny(Reason.POLICY_DENIED);
        }
        if (!request.operationRegistered()) {
            return deny(Reason.UNKNOWN_OPERATION);
        }
        if (request.targets().isEmpty()
                || request.targets().stream().anyMatch(t -> t.level() == TargetLevel.UNRESOLVED)) {
            return deny(Reason.SCOPE_DENIED);
        }

        Set<String> grants = new LinkedHashSet<>();
        Set<String> denials = new LinkedHashSet<>();
        Set<String> matches = new LinkedHashSet<>();
        boolean missingGrant = false;
        for (Target target : request.targets()) {
            boolean granted = policy.getDefaultEffect() == Effect.ALLOW;
            for (Rule rule : policy.getRules()) {
                if (!matches(rule.scope(), target)
                        || !rule.operations().contains(request.operation())) {
                    continue;
                }
                matches.add(rule.id());
                if (rule.effect() == Effect.DENY) {
                    denials.add(rule.id());
                } else {
                    granted = true;
                    grants.add(rule.id());
                }
            }
            missingGrant |= !granted;
        }
        List<String> matchingIds = policy.getRules().stream().map(Rule::id).filter(matches::contains).toList();
        if (!denials.isEmpty()) {
            return new Decision(false, Reason.POLICY_DENIED, matchingIds);
        }
        if (missingGrant) {
            return new Decision(false, Reason.SCOPE_DENIED, matchingIds);
        }
        return new Decision(true, grants.isEmpty() ? Reason.DEFAULT_ALLOWED : Reason.RULE_ALLOWED, matchingIds);
    }

    private boolean matches(Scope scope, Target target) {
        return switch (scope.kind()) {
            case GLOBAL -> true;
            case PROJECT -> scope.resourceId().equals(target.projectId());
            case STACK -> scope.resourceId().equals(target.stackId());
            case RESOURCE -> scope.resourceType().equals(target.resourceType())
                    && scope.resourceId().equals(target.resourceId());
        };
    }

    private Decision deny(Reason reason) {
        return new Decision(false, reason, List.of());
    }
}
