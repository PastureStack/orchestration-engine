package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Effect;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Mode;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Rule;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Scope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class ApiKeyPolicyTest {

    @Test
    public void rulesAndOperationsAreDefensivelyCopied() {
        Set<String> operations = new HashSet<>(Set.of("update"));
        Rule rule = new Rule("r1", Effect.ALLOW, Scope.stack("1st1"), operations);
        List<Rule> rules = new ArrayList<>(List.of(rule));
        ApiKeyPolicy policy = new ApiKeyPolicy(Mode.CUSTOM, Effect.DENY, null, rules);
        operations.clear();
        rules.clear();
        assertEquals(Set.of("update"), rule.operations());
        assertEquals(List.of(rule), policy.getRules());
        assertThrows(UnsupportedOperationException.class, () -> rule.operations().clear());
        assertThrows(UnsupportedOperationException.class, () -> policy.getRules().clear());
    }

    @Test
    public void explicitExpiryAndNoExpiryRemainDistinct() {
        Instant expiry = Instant.parse("2026-10-09T00:00:00Z");
        assertEquals(expiry, new ApiKeyPolicy(Mode.FULL, Effect.DENY, expiry, List.of()).getExpiresAt());
        assertEquals(null, new ApiKeyPolicy(Mode.FULL, Effect.DENY, null, List.of()).getExpiresAt());
    }

    @Test
    public void ambiguousAndBlankIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Scope.stack(" "));
        assertThrows(IllegalArgumentException.class, () -> Scope.stack("1st1 "));
        assertThrows(IllegalArgumentException.class,
                () -> new Scope(ApiKeyPolicy.ScopeKind.GLOBAL, null, "1st1"));
        assertThrows(IllegalArgumentException.class, () -> Scope.resource("", "1i1"));
        assertThrows(IllegalArgumentException.class,
                () -> new Rule("r1", Effect.ALLOW, Scope.global(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Rule("r1", Effect.ALLOW, Scope.global(), Set.of(" read")));
        assertThrows(IllegalArgumentException.class,
                () -> new Rule("r1", Effect.ALLOW, Scope.global(), Set.of("*")));
    }

    @Test
    public void duplicateRuleIdsAreRejectedRatherThanSilentlyReplaced() {
        Rule allow = new Rule("r1", Effect.ALLOW, Scope.global(), Set.of("read"));
        Rule deny = new Rule("r1", Effect.DENY, Scope.stack("1st1"), Set.of("read"));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiKeyPolicy(Mode.CUSTOM, Effect.DENY, null, List.of(allow, deny)));
    }

    @Test
    public void targetsAndRequestsCannotBeMutatedThroughCallerLists() {
        var target = ApiKeyPolicyEvaluator.Target.stackResource("container", "1i1", "1a5", "1st1");
        List<ApiKeyPolicyEvaluator.Target> targets = new ArrayList<>(List.of(target));
        var request = new ApiKeyPolicyEvaluator.Request(true, "update", true, targets);
        targets.clear();
        assertEquals(List.of(target), request.targets());
        assertThrows(UnsupportedOperationException.class, () -> request.targets().clear());
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyPolicyEvaluator.Target.projectResource("container", "1i1", " "));
    }

    @Test
    public void resolvedTargetLevelsRequireCompleteAncestry() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyPolicyEvaluator.Target.stackResource("container", "1i1", null, "1st1"));
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyPolicyEvaluator.Target.stackResource("container", "1i1", "1a5", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiKeyPolicyEvaluator.Target("container", "1i1", null, "1st1",
                        ApiKeyPolicyEvaluator.TargetLevel.PLATFORM));
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.PLATFORM,
                ApiKeyPolicyEvaluator.Target.platformResource("setting", "api.host").level());
    }
}
