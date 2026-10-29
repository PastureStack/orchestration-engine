package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ApiKeyPurposeTest {
    private final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
    private ApiKeyPolicy full(ApiKeyPolicy.Purpose purpose) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), purpose);
    }
    @Test public void existingGeneralPoliciesKeepTheSameWireShape() {
        Map<String,Object> original = new LinkedHashMap<>();
        original.put("mode", "full"); original.put("defaultEffect", "allow");
        original.put("expiresAt", null); original.put("rules", List.of());
        assertEquals(original, codec.encode(full(ApiKeyPolicy.Purpose.GENERAL)));
        assertEquals(ApiKeyPolicy.Purpose.GENERAL, codec.decode(original).getPurpose());
        original.put("purpose", "general");
        assertFalse(codec.encode(codec.decode(original)).containsKey("purpose"));
    }
    @Test public void dedicatedPurposeRoundTripsWithoutChangingScope() {
        ApiKeyPolicy original = full(ApiKeyPolicy.Purpose.AI_BRIDGE);
        assertEquals("ai-bridge", codec.encode(original).get("purpose"));
        ApiKeyPolicy decoded = codec.decode(codec.encode(original));
        assertEquals(ApiKeyPolicy.Purpose.AI_BRIDGE, decoded.getPurpose());
        assertEquals(ApiKeyPolicy.Mode.FULL, decoded.getMode());
        assertEquals(original.getRules(), decoded.getRules());
    }
    @Test public void unknownOrNullPurposeFailsClosed() {
        for (String invalid : new String[] {"AI_BRIDGE", "other", "", null}) {
            Map<String,Object> input = new LinkedHashMap<>(codec.encode(full(ApiKeyPolicy.Purpose.GENERAL)));
            input.put("purpose", invalid);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(input));
        }
    }
    @Test public void removingPurposeBoundaryRequiresExistingMfa() {
        ApiKeyPolicyManagementService management = new ApiKeyPolicyManagementService();
        assertTrue(management.requiresConfirmation(full(ApiKeyPolicy.Purpose.AI_BRIDGE), full(ApiKeyPolicy.Purpose.GENERAL)));
        assertFalse(management.requiresConfirmation(full(ApiKeyPolicy.Purpose.GENERAL), full(ApiKeyPolicy.Purpose.AI_BRIDGE)));
        assertFalse(management.requiresConfirmation(full(ApiKeyPolicy.Purpose.AI_BRIDGE),
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of())));
    }
}
