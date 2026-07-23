package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.cattle.platform.core.constants.ServiceConstants;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryApiServiceImplDrainTimeoutTest {

    @Test
    public void excludeZeroDrainTimeoutRemovesZeroValue() {
        Map<String, Object> composeServiceData = dataWithDrainTimeout(Integer.valueOf(0));

        new ServiceDiscoveryApiServiceImpl().excludeZeroDrainTimeout(composeServiceData);

        assertFalse(composeServiceData.containsKey(ServiceConstants.FIELD_DRAIN_TIMEOUT));
    }

    @Test
    public void excludeZeroDrainTimeoutRemovesNullValue() {
        Map<String, Object> composeServiceData = dataWithDrainTimeout(null);

        new ServiceDiscoveryApiServiceImpl().excludeZeroDrainTimeout(composeServiceData);

        assertFalse(composeServiceData.containsKey(ServiceConstants.FIELD_DRAIN_TIMEOUT));
    }

    @Test
    public void excludeZeroDrainTimeoutKeepsNonZeroValue() {
        Map<String, Object> composeServiceData = dataWithDrainTimeout(Integer.valueOf(5));

        new ServiceDiscoveryApiServiceImpl().excludeZeroDrainTimeout(composeServiceData);

        assertEquals(Integer.valueOf(5), composeServiceData.get(ServiceConstants.FIELD_DRAIN_TIMEOUT));
    }

    @Test(expected = ClassCastException.class)
    public void excludeZeroDrainTimeoutRejectsNonIntegerValue() {
        Map<String, Object> composeServiceData = dataWithDrainTimeout("5");

        new ServiceDiscoveryApiServiceImpl().excludeZeroDrainTimeout(composeServiceData);
    }

    private static Map<String, Object> dataWithDrainTimeout(Object drainTimeout) {
        Map<String, Object> composeServiceData = new HashMap<>();
        composeServiceData.put(ServiceConstants.FIELD_DRAIN_TIMEOUT, drainTimeout);
        return composeServiceData;
    }
}
