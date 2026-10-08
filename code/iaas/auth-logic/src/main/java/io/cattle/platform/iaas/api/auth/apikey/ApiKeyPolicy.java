package io.cattle.platform.iaas.api.auth.apikey;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable attenuation policy. It contains neither credentials nor an RBAC
 * snapshot. Persistence and credential-kind migration belong to the caller.
 */
public final class ApiKeyPolicy {

    public enum Mode { FULL, CLOSED, CUSTOM }
    public enum Effect { ALLOW, DENY }
    public enum ScopeKind { GLOBAL, PROJECT, STACK, RESOURCE }

    public record Scope(ScopeKind kind, String resourceType, String resourceId) {
        public Scope {
            Objects.requireNonNull(kind, "scope kind");
            if (kind == ScopeKind.GLOBAL) {
                if (resourceType != null || resourceId != null) {
                    throw new IllegalArgumentException("Global scope has no resource identity");
                }
            } else {
                requireIdentifier(resourceId, "scope resource ID");
                if (kind == ScopeKind.RESOURCE) {
                    requireIdentifier(resourceType, "scope resource type");
                } else if (resourceType != null) {
                    throw new IllegalArgumentException("Ancestry scope has no resource type");
                }
            }
        }

        public static Scope global() {
            return new Scope(ScopeKind.GLOBAL, null, null);
        }

        public static Scope project(String id) {
            return new Scope(ScopeKind.PROJECT, null, id);
        }

        public static Scope stack(String id) {
            return new Scope(ScopeKind.STACK, null, id);
        }

        public static Scope resource(String type, String id) {
            return new Scope(ScopeKind.RESOURCE, type, id);
        }
    }

    /** Operation IDs come from the canonical registry, not HTTP method names. */
    public record Rule(String id, Effect effect, Scope scope, Set<String> operations) {
        public Rule {
            requireIdentifier(id, "rule ID");
            Objects.requireNonNull(effect, "rule effect");
            Objects.requireNonNull(scope, "rule scope");
            operations = Set.copyOf(Objects.requireNonNull(operations, "rule operations"));
            if (operations.isEmpty()) {
                throw new IllegalArgumentException("Rule requires at least one operation");
            }
            for (String operation : operations) {
                requireIdentifier(operation, "operation ID");
                if ("*".equals(operation)) {
                    throw new IllegalArgumentException("Rule requires explicit canonical operation IDs");
                }
            }
        }
    }

    private final Mode mode;
    private final Effect defaultEffect;
    private final Instant expiresAt;
    private final List<Rule> rules;

    public ApiKeyPolicy(Mode mode, Effect defaultEffect, Instant expiresAt, List<Rule> rules) {
        this.mode = Objects.requireNonNull(mode, "policy mode");
        this.defaultEffect = Objects.requireNonNull(defaultEffect, "default effect");
        this.expiresAt = expiresAt;
        this.rules = List.copyOf(Objects.requireNonNull(rules, "policy rules"));
        Set<String> ids = new HashSet<>();
        for (Rule rule : this.rules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate rule ID");
            }
        }
    }

    public Mode getMode() {
        return mode;
    }

    public Effect getDefaultEffect() {
        return defaultEffect;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public List<Rule> getRules() {
        return rules;
    }

    static void requireIdentifier(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " must be a nonblank canonical identifier");
        }
    }
}
