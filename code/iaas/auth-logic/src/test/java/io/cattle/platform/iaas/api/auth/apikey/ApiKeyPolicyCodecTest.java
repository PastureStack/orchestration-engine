package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;

import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class ApiKeyPolicyCodecTest {
    private final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();

    @Test public void legacyHasNoNewPolicyExpiryOwnerOrRevision() {
        CredentialRecord key = key();
        key.setData(new HashMap<>(Map.of("originalField", "unchanged")));
        assertNull(codec.read(key));
        assertEquals(0, codec.revision(key));
        assertEquals(Long.valueOf(123), key.getAccountId());
        assertEquals(Map.of("originalField", "unchanged"), key.getData());
    }

    @Test public void storedPolicyRoundTripsWithServerOwnershipAndOriginalData() {
        CredentialRecord key = key();
        key.setData(new HashMap<>(Map.of("originalField", "unchanged")));
        ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY,
                Instant.parse("2099-01-01T00:00:00Z"), List.of(rule("stack-rule")));
        key.setKind(CredentialConstants.KIND_API_KEY_RESTRICTED);
        key.setData(codec.store(key, policy, 4));
        assertEquals(codec.encode(policy), codec.encode(codec.read(key)));
        assertEquals(4, codec.revision(key));
        assertEquals(123L, key.getData().get(ApiKeyPolicyCodec.OWNER));
        assertEquals("unchanged", key.getData().get("originalField"));
    }

    @Test public void restrictedKindAndForeignOwnerFailClosed() {
        CredentialRecord key = key();
        key.setKind(CredentialConstants.KIND_API_KEY_RESTRICTED);
        assertThrows(IllegalArgumentException.class, () -> codec.read(key));
        key.setData(codec.store(key, full(), 1));
        key.getData().put(ApiKeyPolicyCodec.OWNER, 999L);
        assertThrows(IllegalArgumentException.class, () -> codec.read(key));
    }

    @Test public void publicDtoRejectsUnknownOwnerAndReadonlyFields() {
        Map<String, Object> input = new HashMap<>(codec.encode(full()));
        input.put("ownerId", 999L);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(input));
        input.remove("ownerId");
        input.put("revision", 900);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(input));
    }

    @Test public void fixedModesCannotHideContradictoryRules() {
        ApiKeyPolicy invalidFull = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW,
                null, List.of(rule("bad")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(codec.encode(invalidFull)));
        ApiKeyPolicy invalidClosed = new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.ALLOW,
                null, List.of());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(codec.encode(invalidClosed)));
    }

    @Test public void wireRulesOnlyAcceptTheCanonicalOperations() {
        ApiKeyPolicy unknown = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("unknown", ApiKeyPolicy.Effect.ALLOW,
                        ApiKeyPolicy.Scope.global(), Set.of("HTTP_POST"))));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(codec.encode(unknown)));
    }

    @Test public void canonicalEncodingIgnoresRuleAndOperationOrdering() {
        ApiKeyPolicy a = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(rule("z"), rule("a")));
        ApiKeyPolicy b = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(rule("a"), rule("z")));
        assertEquals(codec.encode(a), codec.encode(b));
        assertEquals(codec.encode(a), codec.encode(codec.decode(codec.encode(a))));
    }

    @Test public void credentialKindChangesAndResourceAliasesCannotInvalidateDenyRules() {
        for (Map.Entry<String, String> alias : Map.of("instance", "container", "environment", "stack",
                "apiKeyRestricted", "apiKey").entrySet()) {
            ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null,
                    List.of(new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY,
                            ApiKeyPolicy.Scope.resource(alias.getKey(), "stable-id"), Set.of("read"))));
            ApiKeyPolicy canonical = codec.decode(codec.encode(policy));
            assertEquals(alias.getValue(), canonical.getRules().get(0).scope().resourceType());
            assertEquals("stable-id", canonical.getRules().get(0).scope().resourceId());
            assertEquals(ApiKeyPolicy.Effect.DENY, canonical.getRules().get(0).effect());
        }
    }

    private static ApiKeyPolicy.Rule rule(String id) {
        return new ApiKeyPolicy.Rule(id, ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st99"),
                Set.of("read", "update"));
    }
    private static ApiKeyPolicy full() {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of());
    }
    private static CredentialRecord key() {
        CredentialRecord key = new CredentialRecord();
        key.setId(12L); key.setAccountId(123L); key.setKind(CredentialConstants.KIND_API_KEY);
        return key;
    }
}
