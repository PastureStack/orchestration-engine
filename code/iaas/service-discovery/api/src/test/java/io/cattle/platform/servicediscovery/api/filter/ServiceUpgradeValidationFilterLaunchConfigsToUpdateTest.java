package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.addon.InServiceUpgradeStrategy;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceUpgradeValidationFilterLaunchConfigsToUpdateTest {

    @Test
    public void getLaunchConfigsToUpdateInitialIncludesPrimaryByServiceName() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();
        Service service = service("web");
        Map<Object, Object> primary = launchConfig(null);
        InServiceUpgradeStrategy strategy = strategy(primary, null);

        Map<String, Map<Object, Object>> result = filter.getLaunchConfigsToUpdateInitial(service, strategy,
                new HashMap<String, Map<Object, Object>>());

        assertSame(primary, result.get("web"));
    }

    @Test
    public void getLaunchConfigsToUpdateInitialIncludesSecondaryByName() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();
        Map<Object, Object> sidekick = launchConfig("sidekick");
        InServiceUpgradeStrategy strategy = strategy(null, sidekick);

        Map<String, Map<Object, Object>> result = filter.getLaunchConfigsToUpdateInitial(service("web"), strategy,
                new HashMap<String, Map<Object, Object>>());

        assertSame(sidekick, result.get("sidekick"));
    }

    @Test
    public void getLaunchConfigsToUpdateInitialReturnsEmptyMapWhenNoConfigsAreRequested() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();

        assertTrue(filter.getLaunchConfigsToUpdateInitial(service("web"), strategy(null, null),
                new HashMap<String, Map<Object, Object>>()).isEmpty());
    }

    @Test(expected = ClassCastException.class)
    public void getLaunchConfigsToUpdateInitialRejectsNonMapPrimaryLaunchConfig() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();
        InServiceUpgradeStrategy strategy = strategy("not-a-map", null);

        filter.getLaunchConfigsToUpdateInitial(service("web"), strategy, new HashMap<String, Map<Object, Object>>());
    }

    @Test(expected = NullPointerException.class)
    public void getLaunchConfigsToUpdateInitialPreservesNonMapSecondaryNameFailureMode() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();
        InServiceUpgradeStrategy strategy = strategy(null, "not-a-map");

        filter.getLaunchConfigsToUpdateInitial(service("web"), strategy, new HashMap<String, Map<Object, Object>>());
    }

    @Test(expected = NullPointerException.class)
    public void getLaunchConfigsToUpdateInitialPreservesMissingSecondaryNameFailureMode() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();
        InServiceUpgradeStrategy strategy = strategy(null, new HashMap<Object, Object>());

        filter.getLaunchConfigsToUpdateInitial(service("web"), strategy, new HashMap<String, Map<Object, Object>>());
    }

    private static InServiceUpgradeStrategy strategy(Object primary, Object secondary) {
        InServiceUpgradeStrategy strategy = new InServiceUpgradeStrategy();
        strategy.setLaunchConfig(primary);
        if (secondary != null) {
            strategy.setSecondaryLaunchConfigs(Arrays.asList(secondary));
        }
        return strategy;
    }

    private static Map<Object, Object> launchConfig(String name) {
        Map<Object, Object> launchConfig = new HashMap<>();
        if (name != null) {
            launchConfig.put("name", name);
        }
        return launchConfig;
    }

    private static Service service(String name) {
        ServiceRecord service = new ServiceRecord();
        service.setName(name);
        return service;
    }
}
