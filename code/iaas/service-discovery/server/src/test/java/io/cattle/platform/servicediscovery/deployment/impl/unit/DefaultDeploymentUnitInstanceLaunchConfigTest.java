package io.cattle.platform.servicediscovery.deployment.impl.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class DefaultDeploymentUnitInstanceLaunchConfigTest {

    @Test
    public void serviceContainerPreservesExplicitRestartPolicy() {
        Map<String, Object> restartPolicy = new HashMap<>();
        restartPolicy.put("name", "unless-stopped");
        restartPolicy.put("maximumRetryCount", 0);
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put("restartPolicy", restartPolicy);

        Map<String, Object> result = DefaultDeploymentUnitInstance.prepareContainerLaunchConfig(
                launchConfig, "stack-service-1");

        assertSame(launchConfig, result);
        assertSame(restartPolicy, result.get("restartPolicy"));
        assertEquals("stack-service-1", result.get("name"));
    }
}
