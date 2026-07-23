package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.constants.ServiceConstants;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceValidationFilterLaunchConfigRefsTest {

    @Test
    public void populateLaunchConfigRefsIncludesNetworkAndVolumesFromRefs() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        Map<String, Object> primary = launchConfig("web");
        primary.put(ServiceConstants.FIELD_NETWORK_LAUNCH_CONFIG, "db");
        primary.put(ServiceConstants.FIELD_DATA_VOLUMES_LAUNCH_CONFIG, Arrays.asList("cache"));

        Map<String, Map<String, String>> refs = filter.populateLaunchConfigRefs(null, "default-service",
                Arrays.asList(primary));

        assertEquals("network_from", refs.get("web").get("db"));
        assertEquals("volumes_from", refs.get("web").get("cache"));
    }

    @Test
    public void populateLaunchConfigRefsUsesServiceNameForPrimaryWithoutName() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        Map<String, Object> primary = new HashMap<>();
        primary.put(ServiceConstants.FIELD_NETWORK_LAUNCH_CONFIG, "sidekick");

        Map<String, Map<String, String>> refs = filter.populateLaunchConfigRefs(null, "default-service",
                Arrays.asList(primary));

        assertEquals("network_from", refs.get("default-service").get("sidekick"));
    }

    @Test
    public void stringListPreservesStringElements() {
        List<String> refs = Arrays.asList("web", "cache");

        assertEquals(refs, ServiceValidationFilter.stringList(refs));
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonListValue() {
        ServiceValidationFilter.stringList("web");
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonStringElement() {
        ServiceValidationFilter.stringList(Arrays.asList("web", Integer.valueOf(42)));
    }

    @Test(expected = ClassCastException.class)
    public void populateLaunchConfigRefsRejectsNonStringNetworkFromValue() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        Map<String, Object> primary = launchConfig("web");
        primary.put(ServiceConstants.FIELD_NETWORK_LAUNCH_CONFIG, Integer.valueOf(42));

        filter.populateLaunchConfigRefs(null, "default-service", Arrays.asList(primary));
    }

    @Test(expected = ClassCastException.class)
    public void populateLaunchConfigRefsRejectsNonListVolumesFromValue() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        Map<String, Object> primary = launchConfig("web");
        primary.put(ServiceConstants.FIELD_DATA_VOLUMES_LAUNCH_CONFIG, "cache");

        filter.populateLaunchConfigRefs(null, "default-service", Arrays.asList(primary));
    }

    private static Map<String, Object> launchConfig(String name) {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put("name", name);
        return launchConfig;
    }
}
