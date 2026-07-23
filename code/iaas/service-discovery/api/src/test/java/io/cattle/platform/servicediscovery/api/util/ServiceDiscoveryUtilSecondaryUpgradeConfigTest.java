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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilSecondaryUpgradeConfigTest {

    @Test
    public void updateSecondaryLaunchConfigsRollbackWritesPreviousLaunchConfigs() {
        Service service = serviceWithEmptyFields();
        List<Object> previousLaunchConfigs = launchConfigs(sidekick("sidekick", "81:80/tcp"));
        InServiceUpgradeStrategy strategy = strategy(null, previousLaunchConfigs);

        ServiceDiscoveryUtil.updateSecondaryLaunchConfigs(strategy, service, true);

        assertSame(previousLaunchConfigs, secondaryLaunchConfigs(service));
    }

    @Test
    public void updateSecondaryLaunchConfigsRollbackAllowsNullPreviousLaunchConfigs() {
        Service service = serviceWithEmptyFields();
        InServiceUpgradeStrategy strategy = strategy(null, null);

        ServiceDiscoveryUtil.updateSecondaryLaunchConfigs(strategy, service, true);

        assertNull(secondaryLaunchConfigs(service));
    }

    @Test
    public void updateSecondaryLaunchConfigsRollbackDoesNotValidatePreviousElements() {
        Service service = serviceWithEmptyFields();
        List<Object> previousLaunchConfigs = launchConfigs("not-a-map");
        InServiceUpgradeStrategy strategy = strategy(null, previousLaunchConfigs);

        ServiceDiscoveryUtil.updateSecondaryLaunchConfigs(strategy, service, true);

        assertSame(previousLaunchConfigs, secondaryLaunchConfigs(service));
    }

    @Test
    public void updateSecondaryLaunchConfigsPreservesOldRandomPublicPortsBySidekickName() {
        Service service = serviceWithEmptyFields();
        Map<String, Object> newSidekick = sidekick("sidekick", "80/tcp");
        List<Object> newLaunchConfigs = launchConfigs(newSidekick);
        InServiceUpgradeStrategy strategy = strategy(newLaunchConfigs, launchConfigs(sidekick("sidekick", "81:80/tcp")));

        ServiceDiscoveryUtil.updateSecondaryLaunchConfigs(strategy, service, false);

        assertSame(newLaunchConfigs, secondaryLaunchConfigs(service));
        assertEquals(Arrays.asList("81:80/tcp"), newSidekick.get(InstanceConstants.FIELD_PORTS));
    }

    @Test
    public void updateSecondaryLaunchConfigsDoesNotStringifyOldSidekickNameLookup() {
        Service service = serviceWithEmptyFields();
        Map<String, Object> newSidekick = sidekick("42", "80/tcp");
        InServiceUpgradeStrategy strategy = strategy(launchConfigs(newSidekick),
                launchConfigs(sidekick(Integer.valueOf(42), "81:80/tcp")));

        ServiceDiscoveryUtil.updateSecondaryLaunchConfigs(strategy, service, false);

        assertEquals(Arrays.asList("80/tcp"), newSidekick.get(InstanceConstants.FIELD_PORTS));
    }

    @Test(expected = ClassCastException.class)
    public void updateSecondaryLaunchConfigsRejectsNonMapNewLaunchConfig() {
        InServiceUpgradeStrategy strategy = strategy(launchConfigs("not-a-map"),
                launchConfigs(sidekick("sidekick", "81:80/tcp")));

        ServiceDiscoveryUtil.updateSecondaryLaunchConfigs(strategy, serviceWithEmptyFields(), false);
    }

    @Test(expected = ClassCastException.class)
    public void updateSecondaryLaunchConfigsRejectsNonMapPreviousLaunchConfig() {
        InServiceUpgradeStrategy strategy = strategy(launchConfigs(sidekick("sidekick", "80/tcp")),
                launchConfigs("not-a-map"));

        ServiceDiscoveryUtil.updateSecondaryLaunchConfigs(strategy, serviceWithEmptyFields(), false);
    }

    @Test(expected = NullPointerException.class)
    public void updateSecondaryLaunchConfigsPreservesNullPreviousLaunchConfigsFailureMode() {
        InServiceUpgradeStrategy strategy = strategy(launchConfigs(sidekick("sidekick", "80/tcp")), null);

        ServiceDiscoveryUtil.updateSecondaryLaunchConfigs(strategy, serviceWithEmptyFields(), false);
    }

    private static InServiceUpgradeStrategy strategy(List<Object> launchConfigs, List<Object> previousLaunchConfigs) {
        InServiceUpgradeStrategy strategy = new InServiceUpgradeStrategy();
        strategy.setSecondaryLaunchConfigs(launchConfigs);
        strategy.setPreviousSecondaryLaunchConfigs(previousLaunchConfigs);
        return strategy;
    }

    private static List<Object> launchConfigs(Object... launchConfigs) {
        return new ArrayList<>(Arrays.asList(launchConfigs));
    }

    private static Map<String, Object> sidekick(Object name, String port) {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put("name", name);
        launchConfig.put(InstanceConstants.FIELD_PORTS, Arrays.asList(port));
        return launchConfig;
    }

    private static Object secondaryLaunchConfigs(Service service) {
        return DataUtils.getFields(service).get(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS);
    }

    private static Service serviceWithEmptyFields() {
        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, new HashMap<String, Object>());

        ServiceRecord service = new ServiceRecord();
        service.setData(data);
        return service;
    }
}
