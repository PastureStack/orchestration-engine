package io.cattle.platform.servicediscovery.api.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.object.util.DataUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilLaunchConfigDataTest {

    @Test
    public void getLaunchConfigDataAsMapCleansPrimaryNullValuesAndCopiesLabels() {
        Map<String, String> labels = labels("io.rancher.primary", "true");
        Map<String, Object> primaryLaunchConfig = launchConfig("docker:primary", labels);
        primaryLaunchConfig.put("removeMe", null);

        Map<String, Object> result = ServiceDiscoveryUtil.getLaunchConfigDataAsMap(
                serviceWithLaunchConfigs(primaryLaunchConfig, Collections.emptyList()), null);

        assertFalse(result.containsKey("removeMe"));
        assertFalse(primaryLaunchConfig.containsKey("removeMe"));
        assertEquals("docker:primary", result.get(InstanceConstants.FIELD_IMAGE_UUID));
        assertNotSame(labels, result.get(InstanceConstants.FIELD_LABELS));
        assertEquals(labels, result.get(InstanceConstants.FIELD_LABELS));
    }

    @Test
    public void getLaunchConfigDataAsMapReturnsMatchingSecondaryConfig() {
        Map<String, String> labels = labels("io.rancher.sidekick", "true");
        Map<String, Object> sidekick = secondary("sidekick", "docker:sidekick", labels);

        Map<String, Object> result = ServiceDiscoveryUtil.getLaunchConfigDataAsMap(
                serviceWithLaunchConfigs(launchConfig("docker:primary", null), Arrays.asList(sidekick)), "sidekick");

        assertEquals("sidekick", result.get("name"));
        assertEquals("docker:sidekick", result.get(InstanceConstants.FIELD_IMAGE_UUID));
        assertNotSame(labels, result.get(InstanceConstants.FIELD_LABELS));
        assertEquals(labels, result.get(InstanceConstants.FIELD_LABELS));
    }

    @Test
    public void getLaunchConfigDataAsMapReturnsEmptyMapForMissingSecondaryConfig() {
        Map<String, Object> result = ServiceDiscoveryUtil.getLaunchConfigDataAsMap(
                serviceWithLaunchConfigs(launchConfig("docker:primary", null), Collections.emptyList()), "missing");

        assertTrue(result.isEmpty());
    }

    @Test(expected = ClassCastException.class)
    public void getLaunchConfigDataAsMapRejectsNonMapSecondaryConfigValues() {
        Service service = serviceWithFields(launchConfig("docker:primary", null),
                Arrays.asList("not-a-launch-config-map"));

        ServiceDiscoveryUtil.getLaunchConfigDataAsMap(service, "sidekick");
    }

    @Test(expected = ClassCastException.class)
    public void getLaunchConfigDataAsMapRejectsNonMapLabelsAtCopyBoundary() {
        Map<String, Object> primaryLaunchConfig = launchConfig("docker:primary", null);
        primaryLaunchConfig.put(InstanceConstants.FIELD_LABELS, "not-a-map");

        ServiceDiscoveryUtil.getLaunchConfigDataAsMap(
                serviceWithLaunchConfigs(primaryLaunchConfig, Collections.emptyList()), null);
    }

    private static Map<String, String> labels(String key, String value) {
        Map<String, String> labels = new HashMap<>();
        labels.put(key, value);
        return labels;
    }

    private static Map<String, Object> launchConfig(String imageUuid, Map<String, String> labels) {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_IMAGE_UUID, imageUuid);
        if (labels != null) {
            launchConfig.put(InstanceConstants.FIELD_LABELS, labels);
        }
        return launchConfig;
    }

    private static Map<String, Object> secondary(String name, String imageUuid, Map<String, String> labels) {
        Map<String, Object> launchConfig = launchConfig(imageUuid, labels);
        launchConfig.put("name", name);
        return launchConfig;
    }

    private static Service serviceWithLaunchConfigs(Map<String, Object> primaryLaunchConfig,
            Object secondaryLaunchConfigs) {
        return serviceWithFields(primaryLaunchConfig, secondaryLaunchConfigs);
    }

    private static Service serviceWithFields(Map<String, Object> primaryLaunchConfig, Object secondaryLaunchConfigs) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_LAUNCH_CONFIG, primaryLaunchConfig);
        fields.put(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS, secondaryLaunchConfigs);

        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);

        ServiceRecord service = new ServiceRecord();
        service.setData(data);
        return service;
    }
}
