package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Test;

public class CompleteCohortAuditIntersectionTest {
    @Test public void logsMetadataAndRegistryVolumeTargetsShareTheClosedDeliveryOwnerWithoutPayloads() {
        var owner = new AuditServiceImpl(); owner.idFormatter = new IdentityFormatter();
        owner.apiKeyOutbox = mock(ApiKeyAuditOutbox.class); owner.auditLogDao = mock(AuditLogDao.class);
        List<Map<String, Object>> delivered = new ArrayList<>();
        doAnswer(call -> { Map<String, Object> row = call.getArgument(0);
            ((Consumer<Map<String, Object>>) call.getArgument(1)).accept(row); return null;
        }).when(owner.apiKeyOutbox).persist(any(), any());
        doAnswer(call -> { delivered.add(new HashMap<>((Map<String, Object>) call.getArgument(2))); return null;
        }).when(owner.auditLogDao).createApiKeyEvent(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any());
        Policy actor = mock(Policy.class); when(actor.getAccountId()).thenReturn(5L); when(actor.getAuthenticatedAsAccountId()).thenReturn(42L);
        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(Map.of("text", "PRIVATE_LOG", "credentials", "PRIVATE_CREDENTIAL", "url", "PRIVATE_URL"));
        for (String type : List.of("registry", "volume")) for (boolean allowed : List.of(true, false)) {
            owner.recordResourceChangeDecision(request, actor, "preview", "aichange-11111111-1111-1111-1111-111111111111",
                    type, 7L, allowed, allowed ? "ResourceChangePreviewCreated" : "KeyScopeDenied");
            assertEquals(type, delivered.getLast().get("targetType"));
            assertFalse(delivered.getLast().containsKey("maxLines"));
        }
        for (String outcome : List.of("ALLOW", "DENY")) {
            owner.recordContainerLogsOutcome(request, actor, "ailogs-11111111-1111-1111-1111-111111111111", 8L,
                    outcome, "ALLOW".equals(outcome) ? "Completed" : "AuthorityDenied");
            var data = delivered.getLast(); assertEquals("container", data.get("targetType"));
            assertEquals(false, data.get("payloadsIncluded")); assertEquals(200, data.get("maxLines"));
            assertEquals(65536, data.get("maxBytes")); assertEquals(5000, data.get("maxMillis"));
        }
        assertEquals(6, delivered.size()); assertFalse(delivered.toString().contains("PRIVATE"));
        for (String unknown : List.of("storagePool", "storageDriver")) assertThrows(IllegalArgumentException.class,
                () -> owner.recordResourceChangeDecision(request, actor, "preview", "aichange-11111111-1111-1111-1111-111111111111",
                        unknown, 7L, true, "ResourceChangePreviewCreated"));
        assertEquals(6, delivered.size());
    }
}
