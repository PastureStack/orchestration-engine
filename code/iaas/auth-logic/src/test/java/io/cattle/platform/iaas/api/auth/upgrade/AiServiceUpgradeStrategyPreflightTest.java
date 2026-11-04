package io.cattle.platform.iaas.api.auth.upgrade;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.addon.VolumePreflightInput;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.iaas.api.port.PortPreflightDao;
import io.cattle.platform.iaas.api.port.PortPreflightService;
import io.cattle.platform.iaas.api.volume.VolumePreflightService;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.servicediscovery.api.filter.ServiceUpgradeValidationFilter;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Actual native port inventory/parser and filter, with no dispatcher, socket or daemon. */
public class AiServiceUpgradeStrategyPreflightTest {
    @Test public void nativeRetainIpStartFirstInvariantRejectsBeforeAnyPlanOrResourceMutation() throws Exception {
        Fixture f = new Fixture(); DataAccessor.setField(f.target, "retainIp", true);
        var before = f.service.snapshot(f.target);
        var denied = assertThrows(ClientVisibleException.class, () -> f.preview(true, 1));
        assertEquals(422, denied.getApiError().getStatus()); assertEquals("InvalidOption", denied.getApiError().getCode());
        assertTrue(f.documents.isEmpty()); assertEquals(0, f.dispatched);
        assertEquals(List.of("InvalidOption"), f.deniedReasons);
        assertEquals(before, f.service.snapshot(f.target)); assertTrue(f.volumes.isEmpty());
        verify(f.inventory, never()).getEligibleHosts(anyLong()); verify(f.inventory, never()).getPortOwners(anyLong());
        // Native retain-IP is compatible with the legacy stop-first strategy.
        f.preview(false, 1); f.action("confirm"); f.action("apply"); assertEquals(1, f.dispatched);
    }

    @Test public void realStartFirstPortCapacityRejectsAtPreviewConfirmationAndBeforeClaim() throws Exception {
        Fixture f = new Fixture();
        var preview = assertThrows(ClientVisibleException.class, () -> f.preview(true, 2));
        assertEquals(422, preview.getApiError().getStatus()); assertEquals("PortConflict", preview.getApiError().getCode());
        assertTrue(f.documents.isEmpty()); assertEquals(0, f.dispatched);

        // One free host can fit batch1, but cannot fit the approved batch2.
        f.preview(false, 2); f.action("cancel");
        f.preview(true, 1);
        f.hosts(false);
        var confirm = assertThrows(ClientVisibleException.class, () -> f.action("confirm"));
        assertEquals(422, confirm.getApiError().getStatus()); assertEquals("PortConflict", confirm.getApiError().getCode());
        assertEquals("pending", f.document().get("confirmationState"));
        f.hosts(true); f.action("confirm"); f.hosts(false);
        var apply = assertThrows(ClientVisibleException.class, () -> f.action("apply"));
        assertEquals(422, apply.getApiError().getStatus()); assertEquals("PortConflict", apply.getApiError().getCode());
        assertEquals("preview", f.document().get("status")); assertNull(f.document().get("operationId"));
        assertEquals(0, f.dispatched);
        assertEquals(List.of("PortConflict", "PortConflict", "PortConflict"), f.deniedReasons);
        f.hosts(true); f.action("apply");
        assertEquals(1, f.dispatched);
        assertEquals(List.of(false, true, true, true), f.volumes.stream().map(VolumePreflightInput::getStartFirst).toList());
        assertEquals(List.of(2, 1, 1, 1), f.volumes.stream().map(VolumePreflightInput::getBatchSize).toList());
    }

    @Test public void nativeVolumePreflightReceivesApprovedStartFirstAndNativeBatchNormalization() throws Exception {
        Fixture f = new Fixture();
        DataAccessor.fieldMap(f.target, "launchConfig").put("ports", List.of());
        f.preview(true, 9007199254740991L); f.action("confirm"); f.action("apply");
        assertEquals(3, f.volumes.size());
        for (VolumePreflightInput input : f.volumes) {
            assertEquals(Boolean.TRUE, input.getStartFirst()); assertEquals(Integer.valueOf(Integer.MAX_VALUE), input.getBatchSize());
            assertEquals(List.of("named:/data"), input.getDataVolumes());
            assertEquals(Long.valueOf(10), input.getServiceId()); assertEquals(Long.valueOf(3), input.getStackId());
        }
        Map<?, ?> dispatched = (Map<?, ?>) ((Map<?, ?>) f.dispatchedRequest.getRequestObject()).get("inServiceStrategy");
        assertEquals(9007199254740991L, dispatched.get("batchSize"));
        assertEquals(9007199254740991L, ((Number) ((Map<?, ?>) f.document().get("upgradeStrategy")).get("batchSize")).longValue());
    }

    private static class Fixture extends AiServiceUpgradePlanServiceTest.Fixture {
        final PortPreflightDao inventory = mock(PortPreflightDao.class);
        final List<VolumePreflightInput> volumes = new ArrayList<>();
        Fixture() throws Exception {
            DataAccessor.setField(target, "scale", 2);
            DataAccessor.fieldMap(target, "launchConfig").put("networkMode", "bridge");
            when(service.objects.loadResource(io.cattle.platform.core.model.Service.class, 10L)).thenReturn(target);
            hosts(true);
            doReturn(List.of(owner(1), owner(2))).when(inventory).getPortOwners(77L);
            var port = new PortPreflightService(); inject(port, "dao", inventory); inject(port, "objectManager", service.objects);
            var volume = mock(VolumePreflightService.class);
            doAnswer(call -> { volumes.add(call.getArgument(1)); return null; }).when(volume).assertAvailable(eq(project), any());
            var nativeFilter = new ServiceUpgradeValidationFilter(); inject(nativeFilter, "objectManager", service.objects);
            inject(nativeFilter, "portPreflightService", port); inject(nativeFilter, "volumePreflightService", volume);
            service.upgradeValidation = nativeFilter;
        }
        void hosts(boolean freeHost) {
            var values = new ArrayList<io.cattle.platform.core.model.Host>();
            for (long id = 1; id <= (freeHost ? 3 : 2); id++) {
                var host = new HostRecord(); host.setId(id); host.setAccountId(77L); values.add(host);
            }
            when(inventory.getEligibleHosts(77L)).thenReturn(values);
        }
        Map<String, Object> preview(boolean startFirst, long batchSize) {
            return preview(Map.of("serviceId", "10", "image", "app:2", "startFirst", startFirst, "batchSize", batchSize, "intervalMillis", 100));
        }
        // PortOwner is intentionally package-private in the native inventory.
        private static Object owner(long host) throws Exception {
            Class<?> type = Class.forName("io.cattle.platform.iaas.api.port.PortOwner");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); Object owner = constructor.newInstance();
            for (var entry : Map.<String, Object>of("portId", 100L + host, "hostId", host, "serviceId", 10L, "instanceId", 200L + host,
                    "state", "running", "bindAddress", "0.0.0.0", "publicPort", 8080, "privatePort", 80, "protocol", "tcp").entrySet()) {
                var field = type.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(owner, entry.getValue());
            }
            return owner;
        }
        private static void inject(Object target, String field, Object value) throws Exception {
            var property = target.getClass().getDeclaredField(field); property.setAccessible(true); property.set(target, value);
        }
    }
}
