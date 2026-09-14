package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.ServiceConstants;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class ServiceRollbackValidationFilterTypesTest {

    @Test
    public void rollbackRestoresConfigsForEveryUpgradeableServiceType() {
        String[] actualTypes = new ServiceRollbackValidationFilter().getTypes();
        Set<String> actual = new HashSet<>(Arrays.asList(actualTypes));
        Set<String> upgradeTypes = new HashSet<>(Arrays.asList(
                new ServiceUpgradeValidationFilter().getTypes()));

        assertEquals(upgradeTypes, actual);
        assertEquals(actual.size(), actualTypes.length);
        assertTrue(actual.contains(ServiceConstants.KIND_NETWORK_DRIVER_SERVICE));
        assertTrue(actual.contains(ServiceConstants.KIND_STORAGE_DRIVER_SERVICE));
    }
}
