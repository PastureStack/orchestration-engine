package io.cattle.platform.servicediscovery.deployment.impl.unit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.allocator.service.AllocationHelper;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.cattle.platform.object.resource.ResourceMonitor;
import io.cattle.platform.object.resource.ResourcePredicate;
import io.cattle.platform.servicediscovery.api.dao.ServiceExposeMapDao;
import io.cattle.platform.servicediscovery.deployment.DeploymentUnitInstance;
import io.cattle.platform.servicediscovery.deployment.ServiceDeploymentPlanner;
import io.cattle.platform.servicediscovery.deployment.impl.DeploymentManagerImpl;
import io.cattle.platform.servicediscovery.deployment.impl.DeploymentManagerImpl.DeploymentServiceContext;
import io.cattle.platform.servicediscovery.deployment.impl.planner.ServiceDeploymentPlannerFactoryImpl;
import io.cattle.platform.servicediscovery.service.ServiceDiscoveryService;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ExternalDeploymentUnitInstanceLifecycleTest {
    @Mock private ObjectManager objectManager;
    @Mock private ObjectProcessManager processManager;
    @Mock private ResourceMonitor resourceMonitor;
    @Mock private ServiceExposeMapDao exposeMapDao;
    @Mock private AllocationHelper allocationHelper;
    @Mock private ServiceDiscoveryService sdService;
    @InjectMocks private DeploymentManagerImpl manager;
    @InjectMocks private DeploymentUnitInstanceFactoryImpl factory;

    private AutoCloseable mocks;
    private DeploymentServiceContext context;
    private final List<ServiceExposeMapRecord> maps = new ArrayList<>();

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        context = manager.new DeploymentServiceContext();
        StackRecord stack = new StackRecord();
        stack.setId(3L);
        stack.setName("owned-stack");
        when(objectManager.loadResource(eq(Stack.class), anyLong())).thenReturn(stack);
        when(objectManager.findOne(eq(Stack.class), any(), any())).thenReturn(stack);
        when(exposeMapDao.getNonRemovedServiceHostnameMaps(anyLong())).thenAnswer(call ->
                maps.stream().filter(map -> map.getRemoved() == null && map.getHostName() != null).toList());
        when(exposeMapDao.getNonRemovedServiceIpMaps(anyLong())).thenAnswer(call ->
                maps.stream().filter(map -> map.getRemoved() == null && map.getIpAddress() != null).toList());
        when(exposeMapDao.getServiceHostnameExposeMap(any(), anyString())).thenAnswer(call ->
                maps.stream().filter(map -> map.getRemoved() == null
                        && call.getArgument(1).equals(map.getHostName())).findFirst().orElse(null));
        when(exposeMapDao.getServiceIpExposeMap(any(), anyString())).thenAnswer(call ->
                maps.stream().filter(map -> map.getRemoved() == null
                        && call.getArgument(1).equals(map.getIpAddress())).findFirst().orElse(null));
        doAnswer(call -> {
            ServiceExposeMapRecord map = call.getArgument(1);
            map.setState(CommonStatesConstants.REMOVED);
            map.setRemoved(new Date());
            return null;
        }).when(processManager).scheduleStandardProcessAsync(eq(StandardProcess.REMOVE), any(), isNull());
        when(resourceMonitor.waitFor(any(ServiceExposeMap.class), any())).thenAnswer(call -> {
            ServiceExposeMap map = call.getArgument(0);
            ResourcePredicate<ServiceExposeMap> predicate = call.getArgument(1);
            assertTrue(predicate.evaluate(map));
            return map;
        });
    }

    @After
    public void closeMocks() throws Exception {
        mocks.close();
    }

    @Test
    public void hostnameToIpClassifiesTheOldMapForCleanupAndRetainsTheDesiredIp() {
        ServiceRecord service = service(null, List.of("127.0.0.1"));
        ServiceExposeMapRecord old = hostnameMap("localhost");
        ServiceExposeMapRecord desired = ipMap("127.0.0.1");
        assertCleanup(service, old, desired);
    }

    @Test
    public void ipToHostnameClassifiesTheOldMapForCleanupAndRetainsTheDesiredHostname() {
        ServiceRecord service = service("localhost", null);
        ServiceExposeMapRecord old = ipMap("127.0.0.1");
        ServiceExposeMapRecord desired = hostnameMap("localhost");
        assertCleanup(service, old, desired);
    }

    @Test
    public void hostnameReplacementRequiresAnExactTargetMatch() {
        ServiceRecord service = service("new.localhost", null);
        ServiceExposeMapRecord old = hostnameMap("localhost");
        ServiceExposeMapRecord desired = hostnameMap("new.localhost");
        assertCleanup(service, old, desired);
    }

    @Test
    public void ipReplacementCleansOnlyTheNoLongerDesiredAddress() {
        ServiceRecord service = service(null, List.of("127.0.0.2"));
        ServiceExposeMapRecord old = ipMap("127.0.0.1");
        ServiceExposeMapRecord desired = ipMap("127.0.0.2");
        assertCleanup(service, old, desired);
    }

    @Test
    public void absentAndEmptyTargetsRetireExistingMapsInsteadOfDefaultingHealthy() {
        for (String hostname : new String[] {null, ""}) {
            ServiceRecord service = service(hostname, List.of());
            DeploymentUnitInstance oldHostname = instance(service, null, "localhost");
            DeploymentUnitInstance oldIp = instance(service, "127.0.0.1", null);
            assertTrue(oldHostname.isError());
            assertTrue(oldIp.isError());
        }
    }

    @Test
    public void missingDesiredMapIsNotStartedAndStopDoesNotScheduleANullResource() {
        DeploymentUnitInstance unit = instance(service("localhost", null), null, "localhost");
        assertFalse(unit.isError());
        assertTrue(unit.createNew());
        assertFalse(unit.isStarted());
        unit.stop();
        unit.remove();
        unit.waitForRemoval();
        verifyNoInteractions(processManager, resourceMonitor);
    }

    @Test
    public void existingTargetRetainsActiveErrorRemovedAndStopSemantics() {
        ServiceRecord service = service("localhost", null);
        ServiceExposeMapRecord map = hostnameMap("localhost");
        DeploymentUnitInstance unit = instance(service, null, "localhost");
        assertFalse(unit.isError());
        assertFalse(unit.createNew());
        assertTrue(unit.isStarted());
        map.setState("error");
        assertFalse(unit.isStarted());
        assertFalse(unit.isError());
        map.setState(CommonStatesConstants.ACTIVE);
        unit.stop();
        assertEquals(CommonStatesConstants.REMOVED, map.getState());
        assertFalse(unit.isStarted());
        unit.waitForRemoval();
        verify(processManager).scheduleStandardProcessAsync(StandardProcess.REMOVE, map, null);
        assertTrue(factory.collectDeploymentUnits(service, context).stream().allMatch(DeploymentUnit::isComplete));
        assertFalse(planner(service).isDeploymentSettled());
    }

    @Test
    public void aliasNoopPlannerDoesNotCollectOrWriteExternalTargetMaps() {
        ServiceRecord alias = service(null, null);
        alias.setKind(ServiceConstants.KIND_DNS_SERVICE);
        assertTrue(factory.collectDeploymentUnits(alias, context).isEmpty());
        assertTrue(planner(alias).isDeploymentSettled());
        verifyNoInteractions(exposeMapDao, processManager, resourceMonitor);
    }

    private void assertCleanup(ServiceRecord service, ServiceExposeMapRecord old,
            ServiceExposeMapRecord desired) {
        Map<String, Object> before = new LinkedHashMap<>(service.getData());
        ServiceDeploymentPlanner planner = planner(service);
        assertTrue(planner.getStatus(), planner.getStatus().contains("Bad: 1"));
        assertFalse(planner.isDeploymentSettled());
        planner.cleanupBadUnits();
        assertEquals(CommonStatesConstants.REMOVED, old.getState());
        assertEquals(CommonStatesConstants.ACTIVE, desired.getState());
        assertTrue(planner.isDeploymentSettled());
        assertTrue(planner(service).isDeploymentSettled());
        assertEquals(before, service.getData());
        verify(processManager).scheduleStandardProcessAsync(StandardProcess.REMOVE, old, null);
        verify(processManager, never()).scheduleStandardProcessAsync(eq(StandardProcess.REMOVE), same(desired), any());
    }

    private ServiceDeploymentPlanner planner(Service service) {
        return new ServiceDeploymentPlannerFactoryImpl().createServiceDeploymentPlanner(service,
                factory.collectDeploymentUnits(service, context), context);
    }

    private DeploymentUnitInstance instance(Service service, String ip, String hostname) {
        return factory.createDeploymentUnitInstance(context, "owned-unit", service, null,
                org.apache.commons.lang3.tuple.Pair.of(ip, hostname), ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME);
    }

    private ServiceRecord service(String hostname, List<String> ips) {
        ServiceRecord service = new ServiceRecord();
        service.setId(916L);
        service.setAccountId(5L);
        service.setStackId(3L);
        service.setKind(ServiceConstants.KIND_EXTERNAL_SERVICE);
        service.setName("owned-external");
        service.setState(CommonStatesConstants.ACTIVE);
        service.setSystem(false);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(ServiceConstants.FIELD_HOSTNAME, hostname);
        fields.put(ServiceConstants.FIELD_EXTERNALIPS, ips);
        service.setData(Map.of("fields", fields));
        return service;
    }

    private ServiceExposeMapRecord hostnameMap(String hostname) {
        ServiceExposeMapRecord map = map();
        map.setHostName(hostname);
        return map;
    }

    private ServiceExposeMapRecord ipMap(String ip) {
        ServiceExposeMapRecord map = map();
        map.setIpAddress(ip);
        return map;
    }

    private ServiceExposeMapRecord map() {
        ServiceExposeMapRecord map = new ServiceExposeMapRecord();
        map.setId(100L + maps.size());
        map.setServiceId(916L);
        map.setAccountId(5L);
        map.setState(CommonStatesConstants.ACTIVE);
        maps.add(map);
        return map;
    }
}
