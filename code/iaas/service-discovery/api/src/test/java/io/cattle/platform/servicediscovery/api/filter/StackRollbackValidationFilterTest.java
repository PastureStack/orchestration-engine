package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.core.addon.InServiceUpgradeStrategy;
import io.cattle.platform.core.addon.ServiceUpgrade;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.DataUtils;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class StackRollbackValidationFilterTest {

    @Test
    public void stackRollbackRestoresDriverServiceConfigBeforeSchedulingProcess() {
        Map<String, Object> previous = launchConfig("docker:driver:v1");
        Map<String, Object> current = launchConfig("docker:driver:v2");
        ServiceRecord service = upgradedService(current, previous);
        ServiceRecord untouched = new ServiceRecord();
        StackRecord stack = new StackRecord();
        stack.setId(42L);
        List<Service> persisted = new ArrayList<>();

        ObjectManager objectManager = (ObjectManager) Proxy.newProxyInstance(
                ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class },
                (proxy, method, arguments) -> {
                    if ("loadResource".equals(method.getName())) {
                        return stack;
                    }
                    if ("find".equals(method.getName())) {
                        return Arrays.asList(service, untouched);
                    }
                    if ("persist".equals(method.getName())) {
                        persisted.add((Service) arguments[0]);
                        return arguments[0];
                    }
                    return null;
                });
        Object expected = new Object();
        ResourceManager next = (ResourceManager) Proxy.newProxyInstance(
                ResourceManager.class.getClassLoader(),
                new Class<?>[] { ResourceManager.class },
                (proxy, method, arguments) -> "resourceAction".equals(method.getName()) ? expected : null);

        StackValidationFilter filter = new StackValidationFilter();
        filter.objMgr = objectManager;
        filter.jsonMapper = new JacksonJsonMapper();
        ApiRequest request = new ApiRequest(null, null);
        request.setId("1st42");
        request.setAction(ServiceConstants.ACTION_SERVICE_ROLLBACK);

        Object actual = filter.resourceAction("stack", request, next);

        assertSame(expected, actual);
        assertEquals(Arrays.asList(service), persisted);
        assertSame(previous, DataAccessor.fieldMap(service, ServiceConstants.FIELD_LAUNCH_CONFIG));
    }

    private static ServiceRecord upgradedService(Map<String, Object> current,
            Map<String, Object> previous) {
        InServiceUpgradeStrategy strategy = new InServiceUpgradeStrategy();
        strategy.setLaunchConfig(current);
        strategy.setPreviousLaunchConfig(previous);
        ServiceUpgrade upgrade = new ServiceUpgrade();
        upgrade.setInServiceStrategy(strategy);

        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_LAUNCH_CONFIG, current);
        fields.put(ServiceConstants.FIELD_UPGRADE, upgrade);
        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);

        ServiceRecord service = new ServiceRecord();
        service.setData(data);
        return service;
    }

    private static Map<String, Object> launchConfig(String imageUuid) {
        Map<String, Object> result = new HashMap<>();
        result.put("imageUuid", imageUuid);
        return result;
    }
}
