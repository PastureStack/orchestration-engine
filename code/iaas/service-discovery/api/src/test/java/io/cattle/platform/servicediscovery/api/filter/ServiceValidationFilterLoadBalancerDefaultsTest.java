package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.core.addon.InstanceHealthCheck;
import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.util.SystemLabels;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceValidationFilterLoadBalancerDefaultsTest {

    @Test
    public void setLBServiceEnvVarsAndHealthcheckAddsDefaultsToExistingLaunchConfig() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        ApiRequest request = new ApiRequest(null, null);
        Map<String, Object> data = new HashMap<>();
        Map<Object, Object> launchConfig = new HashMap<>();
        data.put(ServiceConstants.FIELD_LAUNCH_CONFIG, launchConfig);
        request.setRequestObject(data);

        ApiRequest result = filter.setLBServiceEnvVarsAndHealthcheck(
                ServiceConstants.KIND_LOAD_BALANCER_SERVICE, null, request);

        assertSame(request, result);
        assertSame(launchConfig, data.get(ServiceConstants.FIELD_LAUNCH_CONFIG));

        Map<?, ?> labels = Map.class.cast(launchConfig.get(InstanceConstants.FIELD_LABELS));
        assertEquals(AgentConstants.ENVIRONMENT_ADMIN_ROLE, labels.get(SystemLabels.LABEL_AGENT_ROLE));
        assertEquals("true", labels.get(SystemLabels.LABEL_AGENT_CREATE));

        InstanceHealthCheck healthCheck = InstanceHealthCheck.class.cast(
                launchConfig.get(InstanceConstants.FIELD_HEALTH_CHECK));
        assertEquals(Integer.valueOf(42), healthCheck.getPort());
        assertEquals(Integer.valueOf(2000), healthCheck.getInterval());
    }

    @Test
    public void setLBServiceEnvVarsAndHealthcheckSkipsNonLoadBalancerTypes() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        ApiRequest request = new ApiRequest(null, null);
        Map<String, Object> data = new HashMap<>();
        data.put(ServiceConstants.FIELD_LAUNCH_CONFIG, "not-a-map");
        request.setRequestObject(data);

        ApiRequest result = filter.setLBServiceEnvVarsAndHealthcheck(ServiceConstants.KIND_SERVICE, null, request);

        assertSame(request, result);
        assertEquals("not-a-map", data.get(ServiceConstants.FIELD_LAUNCH_CONFIG));
    }

    @Test(expected = ClassCastException.class)
    public void setLBServiceEnvVarsAndHealthcheckRejectsNonMapLaunchConfig() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        ApiRequest request = new ApiRequest(null, null);
        Map<String, Object> data = new HashMap<>();
        data.put(ServiceConstants.FIELD_LAUNCH_CONFIG, "not-a-map");
        request.setRequestObject(data);

        filter.setLBServiceEnvVarsAndHealthcheck(ServiceConstants.KIND_LOAD_BALANCER_SERVICE, null, request);
    }

    @Test
    public void launchConfigMapPreservesMapInstance() {
        Map<Object, Object> launchConfig = new HashMap<>();

        assertSame(launchConfig, ServiceValidationFilter.launchConfigMap(launchConfig));
    }

    @Test(expected = NullPointerException.class)
    public void launchConfigMapPreservesNullFailureMode() {
        ServiceValidationFilter.launchConfigMap(null);
    }
}
