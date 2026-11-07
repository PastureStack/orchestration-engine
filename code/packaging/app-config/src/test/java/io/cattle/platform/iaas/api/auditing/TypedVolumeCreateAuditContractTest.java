package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auth.change.AiResourceChangeDescriptor;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TypedVolumeCreateAuditContractTest {
    @Test public void registeredVolumeCreateHasRealClosedAllowDenyAuditWithoutPrivatePayload() throws Exception {
        assertEquals("POST", AiResourceChangeDescriptor.require("volume", "create").method());
        var fixture = new TypedResourceAuditContractTest(); fixture.temporary.create();
        try {
            List<Map<?, ?>> delivered = new ArrayList<>();
            var method = TypedResourceAuditContractTest.class.getDeclaredMethod("owner", List.class); method.setAccessible(true);
            var owner = (AuditServiceImpl) method.invoke(fixture, delivered);
            Policy policy = mock(Policy.class); when(policy.getAccountId()).thenReturn(77L); when(policy.getAuthenticatedAsAccountId()).thenReturn(42L);
            var request = new ApiRequest(null, null); request.setType("aiResourceChangePlan"); request.setMethod("POST");
            request.setRequestObject(Map.of("name", "PRIVATE", "driverOpts", Map.of("password", "PRIVATE")));
            for (boolean allowed : List.of(true, false)) {
                owner.recordResourceChangeDecision(request, policy, "preview", "aichange-11111111-1111-1111-1111-111111111111",
                        "volume", 91L, allowed, allowed ? "ResourceChangePreviewCreated" : "KeyScopeDenied");
                assertEquals("volume", delivered.getLast().get("targetType"));
                assertEquals(allowed ? "ALLOW" : "DENY", delivered.getLast().get("decision"));
                assertFalse(delivered.getLast().toString().contains("PRIVATE"));
            }
            for (String unknown : List.of("persistentVolume", "storageDriver", "Volume", "volume/path"))
                assertThrows(IllegalArgumentException.class, () -> owner.recordResourceChangeDecision(request, policy, "preview",
                        "aichange-11111111-1111-1111-1111-111111111111", unknown, 91L, true, "ResourceChangePreviewCreated"));
            assertEquals(2, delivered.size());
        } finally { fixture.temporary.delete(); }
    }
}
