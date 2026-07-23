package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ValidationErrorException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceValidationFilterPortsTest {

    @Test
    public void validatePortsAcceptsValidPortSpecs() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWithLaunchConfig(launchConfig(Arrays.asList("81:80/tcp", "443/tcp")));

        filter.validatePorts(null, ServiceConstants.KIND_SERVICE, request);
    }

    @Test(expected = ValidationErrorException.class)
    public void validatePortsRejectsNullPortEntries() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWithLaunchConfig(launchConfig(Arrays.asList("80/tcp", null)));

        filter.validatePorts(null, ServiceConstants.KIND_SERVICE, request);
    }

    @Test(expected = ClassCastException.class)
    public void validatePortsRejectsNonMapLaunchConfig() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWithLaunchConfig("not-a-map");

        filter.validatePorts(null, ServiceConstants.KIND_SERVICE, request);
    }

    @Test
    public void convertedStringListPreservesConvertedStrings() {
        List<String> ports = ServiceValidationFilter.convertedStringList(new JacksonJsonMapper(),
                Arrays.asList("80/tcp", "443/tcp"));

        assertEquals(Arrays.asList("80/tcp", "443/tcp"), ports);
    }

    @Test
    public void convertedStringListPreservesNullCollection() {
        assertNull(ServiceValidationFilter.convertedStringList(new JacksonJsonMapper(), null));
    }

    private static ServiceValidationFilter filter() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        filter.jsonMapper = new JacksonJsonMapper();
        return filter;
    }

    private static ApiRequest requestWithLaunchConfig(Object launchConfig) {
        Map<String, Object> data = new HashMap<>();
        data.put(ServiceConstants.FIELD_LAUNCH_CONFIG, launchConfig);

        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(data);
        return request;
    }

    private static Map<String, Object> launchConfig(List<String> ports) {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_PORTS, ports);
        return launchConfig;
    }
}
