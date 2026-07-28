package io.cattle.platform.servicediscovery.api.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.addon.InstanceHealthCheck;
import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.util.SystemLabels;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilBalancerDefaultsTest {

    @Test
    public void balancerLabelsReturnsNewMapWhenMissing() {
        assertTrue(ServiceDiscoveryUtil.balancerLabels(null).isEmpty());
    }

    @Test
    public void balancerLabelsPreservesMapInstance() {
        Map<Object, Object> labels = new HashMap<>();

        assertSame(labels, ServiceDiscoveryUtil.balancerLabels(labels));
    }

    @Test(expected = ClassCastException.class)
    public void balancerLabelsRejectsNonMapValues() {
        ServiceDiscoveryUtil.balancerLabels("not-a-map");
    }

    @Test
    public void injectBalancerLabelsAndHealthcheckAddsDefaults() {
        Map<Object, Object> launchConfig = new HashMap<>();

        ServiceDiscoveryUtil.injectBalancerLabelsAndHealthcheck(launchConfig);

        Map<?, ?> labels = Map.class.cast(launchConfig.get(InstanceConstants.FIELD_LABELS));
        assertEquals(AgentConstants.ENVIRONMENT_ADMIN_ROLE, labels.get(SystemLabels.LABEL_AGENT_ROLE));
        assertEquals("true", labels.get(SystemLabels.LABEL_AGENT_CREATE));

        InstanceHealthCheck healthCheck = InstanceHealthCheck.class.cast(
                launchConfig.get(InstanceConstants.FIELD_HEALTH_CHECK));
        assertEquals(Integer.valueOf(42), healthCheck.getPort());
        assertEquals(Integer.valueOf(2000), healthCheck.getInterval());
    }

    @Test
    public void injectBalancerLabelsAndHealthcheckDoesNotOverwriteExistingRoleOrHealthcheck() {
        Map<Object, Object> labels = new HashMap<>();
        labels.put(SystemLabels.LABEL_AGENT_ROLE, "custom");

        InstanceHealthCheck existingHealthCheck = new InstanceHealthCheck();
        Map<Object, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_LABELS, labels);
        launchConfig.put(InstanceConstants.FIELD_HEALTH_CHECK, existingHealthCheck);

        ServiceDiscoveryUtil.injectBalancerLabelsAndHealthcheck(launchConfig);

        assertSame(labels, launchConfig.get(InstanceConstants.FIELD_LABELS));
        assertEquals("custom", labels.get(SystemLabels.LABEL_AGENT_ROLE));
        assertSame(existingHealthCheck, launchConfig.get(InstanceConstants.FIELD_HEALTH_CHECK));
    }
}
