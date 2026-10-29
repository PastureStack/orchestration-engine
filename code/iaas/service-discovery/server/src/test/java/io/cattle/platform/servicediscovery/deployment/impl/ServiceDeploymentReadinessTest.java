package io.cattle.platform.servicediscovery.deployment.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.allocator.service.AllocationHelper;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.servicediscovery.deployment.DeploymentUnitInstanceFactory;
import io.cattle.platform.servicediscovery.deployment.impl.planner.ServiceDeploymentPlannerFactoryImpl;
import io.cattle.platform.servicediscovery.deployment.impl.unit.DeploymentUnit;
import io.cattle.platform.servicediscovery.service.ServiceDiscoveryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ServiceDeploymentReadinessTest {
    @Test public void defaultScaleDownRequiresExactlyTheFreshDesiredUnitsAndNoIncompleteSidekick() {
        Fixture f = new Fixture(); ServiceRecord oldSnapshot = f.service(2, null);
        f.current = f.service(1, null);
        DeploymentUnit first = f.unit(10), second = f.unit(11);
        f.units.addAll(List.of(first, second));
        assertFalse(f.manager.isReady(oldSnapshot));
        f.units.remove(second); assertTrue(f.manager.isReady(oldSnapshot));
        when(first.isComplete()).thenReturn(false); assertFalse(f.manager.isReady(oldSnapshot));
        when(first.isComplete()).thenReturn(true); when(first.isStarted()).thenReturn(false);
        assertFalse(f.manager.isReady(oldSnapshot));
        when(first.isStarted()).thenReturn(true); when(first.isHealthCheckInitializing()).thenReturn(true);
        assertFalse(f.manager.isReady(oldSnapshot));
        verify(f.manager.objectMgr, never()).setFields(any(), anyMap());
        verify(f.manager.unitInstanceFactory, times(5)).collectDeploymentUnits(same(f.current), any());
    }

    @Test public void policyUsesItsDomainDesiredScaleRatherThanThePublicScaleField() {
        Fixture f = new Fixture(); f.current = f.service(1, 3);
        f.units.addAll(List.of(f.unit(10), f.unit(11), f.unit(12)));
        assertTrue(f.manager.isReady(f.current));
        f.units.removeLast(); assertFalse(f.manager.isReady(f.current));
        f.current = f.service(1, null);
        f.current.setData(Map.of("fields", Map.of("scale", 1, "scalePolicy", Map.of("min", 1, "max", 3))));
        assertFalse(f.manager.isReady(f.current));
    }

    @Test public void globalUsesEligibleHostsAndRejectsDuplicateOrRetiringExtraHostUnits() {
        Fixture f = new Fixture(); f.current = f.service(99, null);
        when(f.manager.sdSvc.isGlobalService(any())).thenReturn(true);
        when(f.manager.allocationHlpr.getHostsSatisfyingHostAffinity(anyLong(), anyMap())).thenReturn(List.of(10L, 11L));
        DeploymentUnit first = f.unit(10), second = f.unit(11), extra = f.unit(12);
        f.units.add(first); assertFalse(f.manager.isReady(f.current));
        f.units.add(second); assertTrue(f.manager.isReady(f.current));
        f.units.add(extra); assertFalse(f.manager.isReady(f.current));
        f.units.remove(extra); f.units.add(f.unit(10)); assertFalse(f.manager.isReady(f.current));
        f.current.setAccountId(88L); ServiceRecord authorized = f.service(99, null);
        assertFalse(f.manager.isReady(authorized));
    }

    @Test public void manualScaleUsesTheFreshLockedRowInsteadOfAnEarlierOrdinaryReadSnapshot() {
        Fixture f = new Fixture(); ServiceRecord locked = f.service(2, null); f.current = f.service(2, null);
        // Same persisted identity, but an older RR snapshot has the earlier mode.
        locked.setData(Map.of("fields", Map.of("scale", 2,
                "launchConfig", Map.of("labels", Map.of(ServiceConstants.LABEL_SERVICE_GLOBAL, "true")))));
        when(f.manager.sdSvc.isGlobalService(locked)).thenReturn(true);
        when(f.manager.sdSvc.isGlobalService(f.current)).thenReturn(false);
        assertFalse(f.manager.supportsManualScale(locked));
        verifyNoInteractions(f.manager.objectMgr);
        assertTrue(f.manager.supportsManualScale(f.current));
        f.current.setRemoved(new java.util.Date()); assertFalse(f.manager.supportsManualScale(f.current));
        assertFalse(f.manager.supportsManualScale(null));
    }

    private static class Fixture {
        final DeploymentManagerImpl manager = new DeploymentManagerImpl();
        final List<DeploymentUnit> units = new ArrayList<>();
        ServiceRecord current;
        Fixture() {
            manager.objectMgr = mock(ObjectManager.class);
            manager.unitInstanceFactory = mock(DeploymentUnitInstanceFactory.class);
            manager.sdSvc = mock(ServiceDiscoveryService.class); manager.allocationHlpr = mock(AllocationHelper.class);
            manager.deploymentPlannerFactory = new ServiceDeploymentPlannerFactoryImpl();
            when(manager.objectMgr.loadResource(eq(Service.class), anyLong())).thenAnswer(call -> current);
            when(manager.objectMgr.findOne(eq(Stack.class), any(), any())).thenReturn(new StackRecord());
            when(manager.unitInstanceFactory.collectDeploymentUnits(any(), any())).thenAnswer(call -> List.copyOf(units));
        }
        ServiceRecord service(int scale, Integer desired) {
            ServiceRecord result = new ServiceRecord(); result.setId(7L); result.setAccountId(77L); result.setKind("service");
            result.setState("active"); result.setSystem(false); result.setStackId(3L);
            Map<String, Object> fields = new java.util.LinkedHashMap<>(); fields.put("scale", scale);
            fields.put("launchConfig", Map.of("imageUuid", "docker:busybox"));
            if (desired != null) { fields.put("desiredScale", desired); fields.put("scalePolicy", Map.of("min", 1, "max", 3)); }
            result.setData(Map.of("fields", fields)); return result;
        }
        DeploymentUnit unit(long host) {
            DeploymentUnit result = mock(DeploymentUnit.class);
            when(result.isComplete()).thenReturn(true); when(result.isStarted()).thenReturn(true);
            when(result.getLabels()).thenReturn(Map.of(ServiceConstants.LABEL_SERVICE_REQUESTED_HOST_ID, Long.toString(host)));
            return result;
        }
    }
}
