package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceUpgradeValidationFilterLaunchConfigVersionTest {

    @Test
    public void setLaunchConfigVersionWritesVersionToExistingMap() {
        ServiceUpgradeValidationFilter filter = new ServiceUpgradeValidationFilter();
        Map<String, Object> launchConfig = new HashMap<>();

        filter.setLaunchConfigVersion("version-1", launchConfig);

        assertEquals("version-1", launchConfig.get(ServiceConstants.FIELD_VERSION));
    }

    @Test
    public void launchConfigMapPreservesMapInstance() {
        Map<String, Object> launchConfig = new HashMap<>();

        assertSame(launchConfig, ServiceUpgradeValidationFilter.launchConfigMap(launchConfig));
    }

    @Test
    public void putLaunchConfigImageUuidWritesToExistingMap() {
        Map<String, Object> launchConfig = new HashMap<>();

        ServiceUpgradeValidationFilter.putLaunchConfigImageUuid(launchConfig, "docker:resolved");

        assertEquals("docker:resolved", launchConfig.get(InstanceConstants.FIELD_IMAGE_UUID));
    }

    @Test(expected = ClassCastException.class)
    public void setLaunchConfigVersionRejectsNonMapLaunchConfig() {
        new ServiceUpgradeValidationFilter().setLaunchConfigVersion("version-1", "not-a-map");
    }

    @Test(expected = ClassCastException.class)
    public void putLaunchConfigImageUuidRejectsNonMapLaunchConfig() {
        ServiceUpgradeValidationFilter.putLaunchConfigImageUuid("not-a-map", "docker:resolved");
    }

    @Test(expected = NullPointerException.class)
    public void setLaunchConfigVersionPreservesNullFailureMode() {
        new ServiceUpgradeValidationFilter().setLaunchConfigVersion("version-1", null);
    }

    @Test(expected = NullPointerException.class)
    public void putLaunchConfigImageUuidPreservesNullFailureMode() {
        ServiceUpgradeValidationFilter.putLaunchConfigImageUuid(null, "docker:resolved");
    }
}
