package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Effect;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Mode;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Rule;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Scope;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator.Reason;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator.Request;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator.Target;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class ApiKeyPolicyEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final Target A = Target.stackResource("container", "1i1", "1a5", "1st1");
    private static final Target B = Target.stackResource("container", "1i2", "1a5", "1st2");
    private final ApiKeyPolicyEvaluator evaluator = new ApiKeyPolicyEvaluator();

    @Test
    public void fullPreservesCurrentOwnerAuthorityWithoutRegistryOrResolverConstraints() {
        ApiKeyPolicy full = policy(Mode.FULL, Effect.DENY,
                new Rule("stale-deny", Effect.DENY, Scope.global(), Set.of("read")));
        assertTrue(evaluate(full, "read", A).allowed());
        assertTrue(evaluator.evaluate(full, new Request(true, "future.action", false, List.of()), NOW).allowed());
        var unresolved = Target.unresolved("new-resource", "1new1");
        assertTrue(evaluator.evaluate(full, new Request(true, "future.action", false, List.of(unresolved)), NOW).allowed());
        assertEquals(Reason.OWNER_DENIED,
                evaluator.evaluate(full, new Request(false, "read", true, List.of(A)), NOW).reason());
    }

    @Test
    public void closedDoesNotReadResidualGrantsOrOpenDefaults() {
        var closed = policy(Mode.CLOSED, Effect.ALLOW, new Rule("stale", Effect.ALLOW, Scope.global(), Set.of("read")));
        assertEquals(Reason.POLICY_DENIED, evaluate(closed, "read", A).reason());
    }

    @Test
    public void noPolicyIsNotAssumedToBeALegacyCredential() {
        assertThrows(NullPointerException.class, () -> evaluate(null, "read", A));
    }

    @Test
    public void expiryUsesInjectedUtcClockAndExactBoundary() {
        for (Mode mode : Mode.values()) {
            var policy = new ApiKeyPolicy(mode, Effect.ALLOW, NOW, List.of());
            var request = new Request(true, "read", true, List.of(A));
            if (mode != Mode.CLOSED) {
                assertTrue(evaluator.evaluate(policy, request, NOW.minusNanos(1)).allowed());
            }
            assertEquals(Reason.EXPIRED, evaluator.evaluate(policy, request, NOW).reason());
            assertEquals(Reason.EXPIRED, evaluator.evaluate(policy, request, NOW.plusNanos(1)).reason());
        }
    }

    @Test
    public void stackUpdateAndOtherReadAreDistinctFromUpgradeDeleteExecLogsAndExport() {
        var custom = policy(Mode.CUSTOM, Effect.DENY,
                new Rule("read", Effect.ALLOW, Scope.global(), Set.of("read")),
                new Rule("update-a", Effect.ALLOW, Scope.stack("1st1"), Set.of("update")));
        assertTrue(evaluate(custom, "update", A).allowed());
        assertTrue(evaluate(custom, "read", B).allowed());
        assertFalse(evaluate(custom, "update", B).allowed());
        for (String operation : List.of("upgrade", "delete", "exec", "logs", "export")) {
            assertFalse(evaluate(custom, operation, A).allowed());
        }
    }

    @Test
    public void explicitDenyWinsRegardlessOfRuleOrderOrScopeSpecificity() {
        Rule allow = new Rule("allow", Effect.ALLOW, Scope.resource("container", "1i1"), Set.of("update"));
        Rule deny = new Rule("deny", Effect.DENY, Scope.project("1a5"), Set.of("update"));
        for (List<Rule> rules : List.of(List.of(allow, deny), List.of(deny, allow))) {
            var decision = evaluate(new ApiKeyPolicy(Mode.CUSTOM, Effect.ALLOW, null, rules), "update", A);
            assertFalse(decision.allowed());
            assertEquals(Reason.POLICY_DENIED, decision.reason());
            assertEquals(rules.stream().map(Rule::id).toList(), decision.matchedRuleIds());
        }
    }

    @Test
    public void aGrantCannotOvercomeCurrentOwnerDenial() {
        var custom = policy(Mode.CUSTOM, Effect.ALLOW);
        assertEquals(Reason.OWNER_DENIED,
                evaluator.evaluate(custom, new Request(false, "update", true, List.of(A)), NOW).reason());
    }

    @Test
    public void customRejectsUnknownOperationsEvenWithOpenDefault() {
        var custom = policy(Mode.CUSTOM, Effect.ALLOW, new Rule("read", Effect.ALLOW, Scope.global(), Set.of("read")));
        assertEquals(Reason.UNKNOWN_OPERATION,
                evaluator.evaluate(custom, new Request(true, "unclassified", false, List.of(A)), NOW).reason());
    }

    @Test
    public void customRejectsUnresolvedAncestryAndEmptyOperationTargets() {
        var custom = policy(Mode.CUSTOM, Effect.ALLOW);
        var unresolved = Target.unresolved("container", "1i1");
        assertEquals(Reason.SCOPE_DENIED, evaluate(custom, "update", unresolved).reason());
        assertEquals(Reason.SCOPE_DENIED,
                evaluator.evaluate(custom, new Request(true, "update", true, List.of()), NOW).reason());
    }

    @Test
    public void resourceScopeRequiresBothTypeAndId() {
        var custom = policy(Mode.CUSTOM, Effect.DENY,
                new Rule("exact", Effect.ALLOW, Scope.resource("container", "1i1"), Set.of("read")));
        assertTrue(evaluate(custom, "read", A).allowed());
        assertFalse(evaluate(custom, "read", Target.stackResource("service", "1i1", "1a5", "1st1")).allowed());
        assertFalse(evaluate(custom, "read", Target.stackResource("container", "1i10", "1a5", "1st1")).allowed());
    }

    @Test
    public void movingAcrossStacksNeedsEveryTargetAuthorized() {
        var custom = policy(Mode.CUSTOM, Effect.DENY,
                new Rule("a", Effect.ALLOW, Scope.stack("1st1"), Set.of("update")));
        assertFalse(evaluate(custom, "update", A, B).allowed());
        var both = policy(Mode.CUSTOM, Effect.DENY,
                new Rule("a", Effect.ALLOW, Scope.stack("1st1"), Set.of("update")),
                new Rule("b", Effect.ALLOW, Scope.stack("1st2"), Set.of("update")));
        assertTrue(evaluate(both, "update", A, B).allowed());
    }

    @Test
    public void blacklistDefaultRemainsUnderOwnerCeiling() {
        var custom = policy(Mode.CUSTOM, Effect.ALLOW,
                new Rule("no-b-delete", Effect.DENY, Scope.stack("1st2"), Set.of("delete")));
        assertTrue(evaluate(custom, "read", B).allowed());
        assertTrue(evaluate(custom, "delete", A).allowed());
        assertFalse(evaluate(custom, "delete", B).allowed());
    }

    @Test
    public void decisionsHaveStableCodesAndImmutableRuleIdsNotExecutionStatus() {
        var custom = policy(Mode.CUSTOM, Effect.DENY,
                new Rule("a", Effect.ALLOW, Scope.stack("1st1"), Set.of("read")));
        var decision = evaluate(custom, "read", A);
        assertEquals("KeyRuleAllowed", decision.reason().getCode());
        assertEquals(List.of("a"), decision.matchedRuleIds());
        assertThrows(UnsupportedOperationException.class, () -> decision.matchedRuleIds().clear());
    }

    @Test
    public void globalDenyCannotBeOverriddenByMoreSpecificAllow() {
        var policy = policy(Mode.CUSTOM, Effect.DENY,
                new Rule("closed", Effect.DENY, Scope.global(), Set.of("update")),
                new Rule("a", Effect.ALLOW, Scope.stack("1st1"), Set.of("update")));
        var decision = evaluate(policy, "update", A);
        assertFalse(decision.allowed());
        assertEquals(List.of("closed", "a"), decision.matchedRuleIds());
    }

    @Test
    public void projectScopeMatchesVerifiedProjectAndDescendantsNotOtherProjects() {
        var policy = policy(Mode.CUSTOM, Effect.DENY,
                new Rule("project", Effect.ALLOW, Scope.project("1a5"), Set.of("read")));
        assertTrue(evaluate(policy, "read", Target.projectResource("project", "1a5", "1a5")).allowed());
        assertTrue(evaluate(policy, "read", A).allowed());
        assertFalse(evaluate(policy, "read", Target.projectResource("project", "1a6", "1a6")).allowed());
        assertFalse(evaluate(policy, "read", Target.platformResource("setting", "api.host")).allowed());
    }

    @Test
    public void ruleIdsAreCompleteAndOrderedIndependentlyOfTargetOrder() {
        var policy = policy(Mode.CUSTOM, Effect.DENY,
                new Rule("b", Effect.ALLOW, Scope.stack("1st2"), Set.of("read")),
                new Rule("a", Effect.ALLOW, Scope.stack("1st1"), Set.of("read")));
        assertEquals(List.of("b", "a"), evaluate(policy, "read", A, B, A).matchedRuleIds());
        assertEquals(List.of("b", "a"), evaluate(policy, "read", B, A).matchedRuleIds());
    }

    private ApiKeyPolicy policy(Mode mode, Effect defaultEffect, Rule... rules) {
        return new ApiKeyPolicy(mode, defaultEffect, null, List.of(rules));
    }

    private ApiKeyPolicyEvaluator.Decision evaluate(ApiKeyPolicy policy, String operation, Target... targets) {
        return evaluator.evaluate(policy, new Request(true, operation, true, List.of(targets)), NOW);
    }
}
