package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.LoadBalancerConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.servicediscovery.api.dao.ServiceConsumeMapDao;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Test;

public class ServiceDiscoveryApiServiceImplLabelExportTest {

    @Test
    public void populateLoadBalancerServiceLabelsMergesConsumedTargetLabels() {
        ServiceRecord balancer = service(1L, "lb", 100L, ServiceConstants.KIND_LOAD_BALANCER_SERVICE);
        ServiceRecord sameStackService = service(2L, "api", 100L, ServiceConstants.KIND_SERVICE);
        ServiceRecord otherStackService = service(3L, "db", 200L, ServiceConstants.KIND_SERVICE);
        StackRecord otherStack = new StackRecord();
        otherStack.setName("shared");
        ServiceDiscoveryApiServiceImpl api = new ServiceDiscoveryApiServiceImpl();
        api.jsonMapper = new JacksonJsonMapper();
        api.consumeMapDao = consumeMapDao(consumeMap(2L, "80", "443"), consumeMap(3L, "5432"));
        api.objectManager = objectManager(sameStackService, otherStackService, otherStack);
        Map<String, Object> composeServiceData = new HashMap<>();
        Map<Object, Object> existingLabels = new HashMap<>();
        existingLabels.put("custom", Integer.valueOf(99));
        composeServiceData.put(InstanceConstants.FIELD_LABELS, existingLabels);

        api.populateLoadBalancerServiceLabels(balancer, ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME,
                composeServiceData);

        Map<?, ?> labels = Map.class.cast(composeServiceData.get(InstanceConstants.FIELD_LABELS));
        assertEquals(Integer.valueOf(99), labels.get("custom"));
        assertEquals("80,443", labels.get(ServiceConstants.LABEL_LB_TARGET + "api"));
        assertEquals("5432", labels.get(ServiceConstants.LABEL_LB_TARGET + "shared/db"));
    }

    @Test(expected = ClassCastException.class)
    public void populateLoadBalancerServiceLabelsKeepsHashMapBoundary() {
        ServiceRecord balancer = service(1L, "lb", 100L, ServiceConstants.KIND_LOAD_BALANCER_SERVICE);
        ServiceDiscoveryApiServiceImpl api = new ServiceDiscoveryApiServiceImpl();
        api.jsonMapper = new JacksonJsonMapper();
        Map<String, Object> composeServiceData = new HashMap<>();
        composeServiceData.put(InstanceConstants.FIELD_LABELS, new TreeMap<Object, Object>());

        api.populateLoadBalancerServiceLabels(balancer, ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME,
                composeServiceData);
    }

    @Test
    public void populateSelectorServiceLabelsMergesExistingLabels() {
        ServiceRecord service = new ServiceRecord();
        service.setSelectorLink("redis");
        service.setSelectorContainer("app");
        Map<String, Object> composeServiceData = new HashMap<>();
        Map<Object, Object> existingLabels = new HashMap<>();
        existingLabels.put("custom", Integer.valueOf(42));
        composeServiceData.put(InstanceConstants.FIELD_LABELS, existingLabels);

        new ServiceDiscoveryApiServiceImpl().populateSelectorServiceLabels(service,
                ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME, composeServiceData);

        Map<?, ?> labels = Map.class.cast(composeServiceData.get(InstanceConstants.FIELD_LABELS));
        assertEquals(Integer.valueOf(42), labels.get("custom"));
        assertEquals("redis", labels.get(ServiceConstants.LABEL_SELECTOR_LINK));
        assertEquals("app", labels.get(ServiceConstants.LABEL_SELECTOR_CONTAINER));
    }

    @Test(expected = ClassCastException.class)
    public void populateSelectorServiceLabelsKeepsHashMapBoundary() {
        ServiceRecord service = new ServiceRecord();
        service.setSelectorLink("redis");
        Map<String, Object> composeServiceData = new HashMap<>();
        composeServiceData.put(InstanceConstants.FIELD_LABELS, new TreeMap<Object, Object>());

        new ServiceDiscoveryApiServiceImpl().populateSelectorServiceLabels(service,
                ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME, composeServiceData);
    }

    @Test
    public void populateSidekickLabelsAddsPrimarySidekickLabel() {
        Service service = serviceWithSidekicks("worker", "logger");
        Map<String, Object> composeServiceData = new HashMap<>();
        Map<Object, Object> existingLabels = new HashMap<>();
        existingLabels.put("custom", Integer.valueOf(7));
        existingLabels.put(ServiceConstants.LABEL_SIDEKICK, "old");
        composeServiceData.put(InstanceConstants.FIELD_LABELS, existingLabels);

        new ServiceDiscoveryApiServiceImpl().populateSidekickLabels(service, composeServiceData, true);

        Map<?, ?> labels = Map.class.cast(composeServiceData.get(InstanceConstants.FIELD_LABELS));
        assertEquals(Integer.valueOf(7), labels.get("custom"));
        assertEquals("worker,logger", labels.get(ServiceConstants.LABEL_SIDEKICK));
    }

    @Test
    public void populateSidekickLabelsRemovesEmptyLabelMapForNonPrimary() {
        Service service = serviceWithSidekicks("worker");
        Map<String, Object> composeServiceData = new HashMap<>();

        new ServiceDiscoveryApiServiceImpl().populateSidekickLabels(service, composeServiceData, false);

        assertFalse(composeServiceData.containsKey(InstanceConstants.FIELD_LABELS));
    }

    @Test(expected = ClassCastException.class)
    public void populateSidekickLabelsKeepsHashMapBoundary() {
        Service service = serviceWithSidekicks("worker");
        Map<String, Object> composeServiceData = new HashMap<>();
        composeServiceData.put(InstanceConstants.FIELD_LABELS, new TreeMap<Object, Object>());

        new ServiceDiscoveryApiServiceImpl().populateSidekickLabels(service, composeServiceData, true);
    }

    private static Service serviceWithSidekicks(String... names) {
        Map<String, Object> fields = new HashMap<>();
        List<Map<String, Object>> sidekicks = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> sidekick = new HashMap<>();
            sidekick.put("name", name);
            sidekicks.add(sidekick);
        }
        fields.put(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS, sidekicks);

        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);

        ServiceRecord service = new ServiceRecord();
        service.setData(data);
        return service;
    }

    private static ServiceRecord service(Long id, String name, Long stackId, String kind) {
        ServiceRecord service = new ServiceRecord();
        service.setId(id);
        service.setName(name);
        service.setStackId(stackId);
        service.setKind(kind);
        service.setData(dataWithFields(new HashMap<String, Object>()));
        return service;
    }

    private static ServiceConsumeMapRecord consumeMap(Long consumedServiceId, String... ports) {
        ServiceConsumeMapRecord map = new ServiceConsumeMapRecord();
        map.setConsumedServiceId(consumedServiceId);
        Map<String, Object> fields = new HashMap<>();
        fields.put(LoadBalancerConstants.FIELD_LB_TARGET_PORTS, Arrays.asList(ports));
        map.setData(dataWithFields(fields));
        return map;
    }

    private static Map<String, Object> dataWithFields(Map<String, Object> fields) {
        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);
        return data;
    }

    private static ServiceConsumeMapDao consumeMapDao(ServiceConsumeMapRecord... maps) {
        return ServiceConsumeMapDao.class.cast(Proxy.newProxyInstance(
                ServiceConsumeMapDao.class.getClassLoader(),
                new Class<?>[] { ServiceConsumeMapDao.class },
                (proxy, method, args) -> {
                    if ("findConsumedServices".equals(method.getName())) {
                        return Arrays.asList(maps);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static ObjectManager objectManager(ServiceRecord sameStackService, ServiceRecord otherStackService,
            StackRecord otherStack) {
        return ObjectManager.class.cast(Proxy.newProxyInstance(
                ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class },
                (proxy, method, args) -> {
                    if ("loadResource".equals(method.getName()) && args.length == 2 && args[0] == Service.class) {
                        Long id = Long.class.cast(args[1]);
                        return sameStackService.getId().equals(id) ? sameStackService : otherStackService;
                    }
                    if ("loadResource".equals(method.getName()) && args.length == 2
                            && args[0] == io.cattle.platform.core.model.Stack.class) {
                        return otherStack;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }
}
