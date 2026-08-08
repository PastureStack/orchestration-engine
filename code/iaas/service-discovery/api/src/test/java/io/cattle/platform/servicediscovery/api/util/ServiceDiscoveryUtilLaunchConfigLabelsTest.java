package io.cattle.platform.servicediscovery.api.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.object.util.DataUtils;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilLaunchConfigLabelsTest {

    @Test
    public void launchConfigLabelsReturnsNewEmptyMapWhenMissing() {
        assertTrue(ServiceDiscoveryUtil.launchConfigLabels(null).isEmpty());
    }

    @Test
    public void launchConfigLabelsPreservesMapInstance() {
        Map<String, String> labels = new HashMap<>();
        labels.put("io.rancher.test", "true");

        assertSame(labels, ServiceDiscoveryUtil.launchConfigLabels(labels));
    }

    @Test(expected = ClassCastException.class)
    public void launchConfigLabelsRejectsNonMapValues() {
        ServiceDiscoveryUtil.launchConfigLabels("not-a-map");
    }

    @Test
    public void getLaunchConfigLabelsDefaultsNullLaunchConfigNameToPrimary() {
        Map<String, String> labels = new HashMap<>();
        labels.put("io.rancher.primary", "yes");

        Service service = serviceWithLaunchConfigLabels(labels);

        Map<String, String> result = ServiceDiscoveryUtil.getLaunchConfigLabels(service, null);

        assertNotSame(labels, result);
        assertEquals("yes", result.get("io.rancher.primary"));
    }

    @Test
    public void getLaunchConfigLabelsReturnsEmptyMapWhenLabelsMissing() {
        Service service = serviceWithLaunchConfigLabels(null);

        assertTrue(ServiceDiscoveryUtil.getLaunchConfigLabels(service, ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME)
                .isEmpty());
    }

    private static Service serviceWithLaunchConfigLabels(Map<String, String> labels) {
        Map<String, Object> launchConfig = new HashMap<>();
        if (labels != null) {
            launchConfig.put(InstanceConstants.FIELD_LABELS, labels);
        }

        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_LAUNCH_CONFIG, launchConfig);

        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);

        ServiceRecord service = new ServiceRecord();
        service.setData(data);
        return service;
    }
}
