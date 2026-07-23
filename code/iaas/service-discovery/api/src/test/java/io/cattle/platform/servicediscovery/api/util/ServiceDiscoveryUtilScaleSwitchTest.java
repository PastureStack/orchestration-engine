package io.cattle.platform.servicediscovery.api.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilScaleSwitchTest {

    @Test
    public void isGlobalServiceReturnsFalseWhenLabelsAreMissing() {
        assertFalse(ServiceDiscoveryUtil.isGlobalServiceData(new HashMap<>()));
    }

    @Test
    public void isGlobalServiceReadsGlobalLabelString() {
        assertTrue(ServiceDiscoveryUtil.isGlobalServiceData(launchConfig("true")));
        assertFalse(ServiceDiscoveryUtil.isGlobalServiceData(launchConfig("false")));
    }

    @Test
    public void isGlobalServiceTreatsNullGlobalLabelAsFalse() {
        assertFalse(ServiceDiscoveryUtil.isGlobalServiceData(launchConfig(null)));
    }

    @Test(expected = ClassCastException.class)
    public void isGlobalServiceRejectsNonMapLabels() {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_LABELS, "not-a-map");

        ServiceDiscoveryUtil.isGlobalServiceData(launchConfig);
    }

    @Test(expected = ClassCastException.class)
    public void isGlobalServiceRejectsNonStringGlobalLabelValues() {
        ServiceDiscoveryUtil.isGlobalServiceData(launchConfig(Boolean.TRUE));
    }

    @Test
    public void validateScaleSwitchAllowsSameScaleMode() {
        ServiceDiscoveryUtil.validateScaleSwitch(launchConfig("true"), launchConfig("true"));
        ServiceDiscoveryUtil.validateScaleSwitch(launchConfig("false"), new HashMap<>());
    }

    @Test(expected = ClientVisibleException.class)
    public void validateScaleSwitchRejectsGlobalToFixedSwitch() {
        ServiceDiscoveryUtil.validateScaleSwitch(new HashMap<>(), launchConfig("true"));
    }

    @Test(expected = ClassCastException.class)
    public void validateScaleSwitchRejectsNonMapLaunchConfig() {
        ServiceDiscoveryUtil.validateScaleSwitch("not-a-map", launchConfig("true"));
    }

    static Map<String, Object> launchConfig(Object globalLabelValue) {
        Map<String, Object> labels = new HashMap<>();
        labels.put(ServiceConstants.LABEL_SERVICE_GLOBAL, globalLabelValue);

        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_LABELS, labels);
        return launchConfig;
    }
}
