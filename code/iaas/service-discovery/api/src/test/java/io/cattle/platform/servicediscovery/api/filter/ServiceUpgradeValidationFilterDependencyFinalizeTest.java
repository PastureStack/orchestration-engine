package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.ServiceConstants;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;

public class ServiceUpgradeValidationFilterDependencyFinalizeTest {

    @Test
    public void finalizeLCNamesToUpdateIncludesNetworkFromDependencies() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();
        Map<String, Map<Object, Object>> serviceLaunchConfigs = new HashMap<>();
        Map<Object, Object> web = launchConfig();
        serviceLaunchConfigs.put("web", web);
        serviceLaunchConfigs.put("sidekick", launchConfig(ServiceConstants.FIELD_NETWORK_LAUNCH_CONFIG, "web"));
        Map<String, Map<Object, Object>> result = new HashMap<>();

        filter.finalizeLCNamesToUpdate(serviceLaunchConfigs, result, Pair.of("web", web));

        assertTrue(result.containsKey("web"));
        assertTrue(result.containsKey("sidekick"));
    }

    @Test
    public void finalizeLCNamesToUpdateIncludesVolumesFromDependencies() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();
        Map<String, Map<Object, Object>> serviceLaunchConfigs = new HashMap<>();
        Map<Object, Object> web = launchConfig();
        serviceLaunchConfigs.put("web", web);
        serviceLaunchConfigs.put("sidekick", launchConfig(ServiceConstants.FIELD_DATA_VOLUMES_LAUNCH_CONFIG,
                Arrays.asList("web")));
        Map<String, Map<Object, Object>> result = new HashMap<>();

        filter.finalizeLCNamesToUpdate(serviceLaunchConfigs, result, Pair.of("web", web));

        assertTrue(result.containsKey("web"));
        assertTrue(result.containsKey("sidekick"));
    }

    @Test
    public void stringListPreservesStringElements() {
        assertEquals(Arrays.asList("web", "sidekick"),
                ServiceUpgradeValidationFilter.stringList(Arrays.asList("web", "sidekick")));
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonListValue() {
        ServiceUpgradeValidationFilter.stringList("web");
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonStringElement() {
        ServiceUpgradeValidationFilter.stringList(Arrays.asList("web", Integer.valueOf(42)));
    }

    @Test(expected = ClassCastException.class)
    public void finalizeLCNamesToUpdateRejectsNonStringNetworkFromValue() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();
        Map<String, Map<Object, Object>> serviceLaunchConfigs = new HashMap<>();
        Map<Object, Object> web = launchConfig();
        serviceLaunchConfigs.put("web", web);
        serviceLaunchConfigs.put("sidekick", launchConfig(ServiceConstants.FIELD_NETWORK_LAUNCH_CONFIG,
                Integer.valueOf(42)));

        filter.finalizeLCNamesToUpdate(serviceLaunchConfigs, new HashMap<String, Map<Object, Object>>(),
                Pair.of("web", web));
    }

    private static Map<Object, Object> launchConfig(Object... keyValues) {
        Map<Object, Object> launchConfig = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            launchConfig.put(keyValues[i], keyValues[i + 1]);
        }
        return launchConfig;
    }
}
