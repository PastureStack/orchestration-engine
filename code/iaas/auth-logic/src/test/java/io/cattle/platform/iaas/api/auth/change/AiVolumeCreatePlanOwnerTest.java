package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import io.github.ibuildthecloud.gdapi.context.ApiContext;

public class AiVolumeCreatePlanOwnerTest {
    @After public void removeContext() { ApiContext.remove(); }
    private static AiResourceChangePlanServiceTest.Fixture fixture() {
        var f = new AiResourceChangePlanServiceTest.Fixture();
        when(f.domain.volumeCreateScope(any())).thenReturn(Map.of("uuid", "project-uuid"));
        doAnswer(call -> {
            f.dispatched++; if (f.failDispatch) throw new IllegalStateException("lost response"); return AiVolumeCreateChangeTest.volume();
        }).when(f.domain).dispatch(any(), any(), nullable(String.class), anyMap(), anyString());
        return f;
    }
    private static void preview(AiResourceChangePlanServiceTest.Fixture f, boolean key) {
        var request = f.request(null, key); request.setId(null);
        request.setRequestObject(Map.of("resourceType", "volume", "operation", "create", "parameters", Map.of("name", "new-data")));
        f.id = (String) f.service.preview(request).get("id");
    }
    @Test public void onlyHumanCanConfirmAndAnUnapprovedPlanCannotCreateAnything() {
        var f = fixture(); preview(f, true);
        assertThrows(ClientVisibleException.class, () -> f.service.action(f.id, f.request("apply", true)));
        assertThrows(ClientVisibleException.class, () -> f.service.action(f.id, f.request("confirm", true)));
        assertEquals(0, f.dispatched); assertEquals(0, f.claims);
    }
    @Test public void nativeCreateResultPinsExactUuidAndDurableApplyRetryDoesNotCreateAgain() {
        var f = fixture(); preview(f, false); f.confirm(); f.service.action(f.id, f.request("apply", false));
        var stored = f.documents.get(f.id); assertEquals("new-volume", stored.get("createdResourceUuid"));
        assertEquals(91L, ((Number) stored.get("internalResourceId")).longValue());
        f.service.action(f.id, f.request("apply", false)); assertEquals(1, f.dispatched); assertEquals(1, f.claims);
    }
    @Test public void lostNativeResponseStaysUnknownAndNeverResetsTheClaim() {
        var f = fixture(); preview(f, false); f.confirm(); f.failDispatch = true;
        assertEquals("unknown", f.service.action(f.id, f.request("apply", false)).get("status"));
        f.service.action(f.id, f.request("apply", false)); assertEquals(1, f.dispatched); assertEquals(1, f.claims);
    }
    @Test public void nativeRoleRevocationBeforeConfirmOrApplyStopsBeforeClaim() {
        var f = fixture(); preview(f, false); f.confirm(); f.writeAllowed = false;
        assertThrows(ClientVisibleException.class, () -> f.service.action(f.id, f.request("apply", false)));
        assertEquals(0, f.claims); assertEquals(0, f.dispatched);
    }
}
