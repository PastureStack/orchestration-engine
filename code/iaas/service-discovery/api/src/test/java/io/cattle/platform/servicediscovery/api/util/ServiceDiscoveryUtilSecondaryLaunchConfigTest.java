package io.cattle.platform.servicediscovery.api.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.object.util.DataUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilSecondaryLaunchConfigTest {

    @Test
    public void secondaryLaunchConfigsReturnsEmptyListWhenMissing() {
        assertTrue(ServiceDiscoveryUtil.secondaryLaunchConfigs(null).isEmpty());
    }

    @Test
    public void secondaryLaunchConfigsPreservesMapInstances() {
        Map<Object, Object> sidekick = new HashMap<>();
        sidekick.put("name", "sidekick");

        List<Map<Object, Object>> result = ServiceDiscoveryUtil.secondaryLaunchConfigs(Arrays.asList(sidekick));

        assertEquals(1, result.size());
        assertSame(sidekick, result.get(0));
    }

    @Test(expected = ClassCastException.class)
    public void secondaryLaunchConfigsRejectsNonListValue() {
        ServiceDiscoveryUtil.secondaryLaunchConfigs("not-a-list");
    }

    @Test(expected = ClassCastException.class)
    public void secondaryLaunchConfigsRejectsNonMapListValue() {
        ServiceDiscoveryUtil.secondaryLaunchConfigs(Arrays.asList("not-a-map"));
    }

    @Test
    public void getServiceLaunchConfigNamesKeepsPrimaryFirstAndStringifiesSecondaryNames() {
        Service service = serviceWithFields(fieldsWithLaunchConfigs(
                Arrays.asList(secondary("web"), secondary(Integer.valueOf(42)))));

        assertEquals(Arrays.asList(ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME, "web", "42"),
                ServiceDiscoveryUtil.getServiceLaunchConfigNames(service));
    }

    @Test
    public void getServiceLaunchConfigsWithNamesIncludesPrimaryAndSecondaryMaps() {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put("imageUuid", "docker:primary");
        Map<String, Object> sidekick = secondary("sidekick");
        Service service = serviceWithFields(fieldsWithLaunchConfigs(launchConfig, Arrays.asList(sidekick)));

        Map<String, Map<Object, Object>> result = ServiceDiscoveryUtil.getServiceLaunchConfigsWithNames(service);

        assertSame(launchConfig, result.get(ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME));
        assertSame(sidekick, result.get("sidekick"));
    }

    private static Map<String, Object> fieldsWithLaunchConfigs(List<Map<String, Object>> secondaryLaunchConfigs) {
        return fieldsWithLaunchConfigs(new HashMap<String, Object>(), secondaryLaunchConfigs);
    }

    private static Map<String, Object> fieldsWithLaunchConfigs(Map<String, Object> primaryLaunchConfig,
            List<Map<String, Object>> secondaryLaunchConfigs) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_LAUNCH_CONFIG, primaryLaunchConfig);
        fields.put(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS, secondaryLaunchConfigs);
        return fields;
    }

    private static Map<String, Object> secondary(Object name) {
        Map<String, Object> config = new HashMap<>();
        config.put("name", name);
        return config;
    }

    private static Service serviceWithFields(Map<String, Object> fields) {
        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);

        ServiceRecord service = new ServiceRecord();
        service.setData(data);
        return service;
    }
}
