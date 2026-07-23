package io.cattle.platform.servicediscovery.api.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.cattle.platform.core.addon.InServiceUpgradeStrategy;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.object.util.DataUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilPrimaryLaunchConfigTest {

    @Test
    public void updatePrimaryLaunchConfigRollbackWritesPreviousLaunchConfig() {
        Service service = serviceWithEmptyFields();
        Map<String, Object> previousLaunchConfig = launchConfigWithPorts("81:80/tcp");
        InServiceUpgradeStrategy strategy = strategy(null, previousLaunchConfig);

        ServiceDiscoveryUtil.updatePrimaryLaunchConfig(strategy, service, true);

        assertSame(previousLaunchConfig, primaryLaunchConfig(service));
    }

    @Test
    public void updatePrimaryLaunchConfigRollbackAllowsNullPreviousLaunchConfig() {
        Service service = serviceWithEmptyFields();
        InServiceUpgradeStrategy strategy = strategy(null, null);

        ServiceDiscoveryUtil.updatePrimaryLaunchConfig(strategy, service, true);

        assertNull(primaryLaunchConfig(service));
    }

    @Test
    public void updatePrimaryLaunchConfigPreservesOldRandomPublicPorts() {
        Service service = serviceWithEmptyFields();
        Map<String, Object> newLaunchConfig = launchConfigWithPorts("80/tcp");
        Map<String, Object> previousLaunchConfig = launchConfigWithPorts("81:80/tcp");
        InServiceUpgradeStrategy strategy = strategy(newLaunchConfig, previousLaunchConfig);

        ServiceDiscoveryUtil.updatePrimaryLaunchConfig(strategy, service, false);

        assertSame(newLaunchConfig, primaryLaunchConfig(service));
        assertEquals(Arrays.asList("81:80/tcp"), newLaunchConfig.get(InstanceConstants.FIELD_PORTS));
    }

    @Test(expected = ClassCastException.class)
    public void updatePrimaryLaunchConfigRejectsNonMapLaunchConfig() {
        InServiceUpgradeStrategy strategy = strategy("not-a-map", new HashMap<String, Object>());

        ServiceDiscoveryUtil.updatePrimaryLaunchConfig(strategy, serviceWithEmptyFields(), false);
    }

    @Test(expected = ClassCastException.class)
    public void updatePrimaryLaunchConfigRejectsNonMapPreviousLaunchConfig() {
        InServiceUpgradeStrategy strategy = strategy(new HashMap<String, Object>(), "not-a-map");

        ServiceDiscoveryUtil.updatePrimaryLaunchConfig(strategy, serviceWithEmptyFields(), false);
    }

    @Test(expected = NullPointerException.class)
    public void updatePrimaryLaunchConfigPreservesNullLaunchConfigFailureMode() {
        InServiceUpgradeStrategy strategy = strategy(null, new HashMap<String, Object>());

        ServiceDiscoveryUtil.updatePrimaryLaunchConfig(strategy, serviceWithEmptyFields(), false);
    }

    private static InServiceUpgradeStrategy strategy(Object launchConfig, Object previousLaunchConfig) {
        InServiceUpgradeStrategy strategy = new InServiceUpgradeStrategy();
        strategy.setLaunchConfig(launchConfig);
        strategy.setPreviousLaunchConfig(previousLaunchConfig);
        return strategy;
    }

    private static Map<String, Object> launchConfigWithPorts(String port) {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_PORTS, Arrays.asList(port));
        return launchConfig;
    }

    private static Map<String, Object> primaryLaunchConfig(Service service) {
        Object launchConfig = DataUtils.getFields(service).get(ServiceConstants.FIELD_LAUNCH_CONFIG);
        if (launchConfig == null) {
            return null;
        }
        return ServiceDiscoveryUtil.launchConfigDataMap(launchConfig);
    }

    private static Service serviceWithEmptyFields() {
        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, new HashMap<String, Object>());

        ServiceRecord service = new ServiceRecord();
        service.setData(data);
        return service;
    }
}
