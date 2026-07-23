package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import io.cattle.platform.core.constants.ServiceConstants;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceValidationFilterLaunchConfigsTest {

    @Test
    public void populateLaunchConfigsRemovesPrimaryNameAndKeepsMapInstance() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        Map<String, Object> primary = launchConfig("primary");
        ApiRequest request = requestWith(ServiceConstants.FIELD_LAUNCH_CONFIG, primary);

        List<?> launchConfigs = filter.populateLaunchConfigs(null, request);

        assertEquals(1, launchConfigs.size());
        assertSame(primary, launchConfigs.get(0));
        assertFalse(primary.containsKey("name"));
    }

    @Test
    public void populateLaunchConfigsKeepsSecondaryElementsWithoutEarlyMapCast() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        Object nonMapSidekick = "not-a-map";
        List<Object> sidekicks = new ArrayList<>();
        sidekicks.add(nonMapSidekick);
        ApiRequest request = requestWith(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS, sidekicks);

        List<?> launchConfigs = filter.populateLaunchConfigs(null, request);

        assertEquals(1, launchConfigs.size());
        assertSame(nonMapSidekick, launchConfigs.get(0));
    }

    @Test(expected = ClassCastException.class)
    public void populateLaunchConfigsRejectsNonMapPrimaryLaunchConfig() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        ApiRequest request = requestWith(ServiceConstants.FIELD_LAUNCH_CONFIG, "not-a-map");

        filter.populateLaunchConfigs(null, request);
    }

    @Test(expected = ClassCastException.class)
    public void populateLaunchConfigsRejectsNonStringPrimaryName() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        Map<String, Object> primary = launchConfig(Integer.valueOf(42));
        ApiRequest request = requestWith(ServiceConstants.FIELD_LAUNCH_CONFIG, primary);

        filter.populateLaunchConfigs(null, request);
    }

    @Test(expected = ClassCastException.class)
    public void populateLaunchConfigsRejectsNonListSecondaryLaunchConfigs() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        ApiRequest request = requestWith(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS, "not-a-list");

        filter.populateLaunchConfigs(null, request);
    }

    @Test(expected = ClassCastException.class)
    public void populateLaunchConfigRefsRejectsNonMapLaunchConfigAtValidationBoundary() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        List<Object> launchConfigs = new ArrayList<>();
        launchConfigs.add("not-a-map");

        filter.populateLaunchConfigRefs(null, "default-service", launchConfigs);
    }

    private static ApiRequest requestWith(String field, Object value) {
        Map<String, Object> data = new HashMap<>();
        data.put(field, value);
        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(data);
        return request;
    }

    private static Map<String, Object> launchConfig(Object name) {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put("name", name);
        return launchConfig;
    }
}
