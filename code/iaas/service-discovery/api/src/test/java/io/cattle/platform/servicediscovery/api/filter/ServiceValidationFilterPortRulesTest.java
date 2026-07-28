package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.addon.PortRule;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ValidationErrorException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceValidationFilterPortRulesTest {

    @Test
    public void validateLbConfigAcceptsSelectorOnlyRule() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWithPortRules(rule("selector", "app"));

        filter.validateLbConfig(request, ServiceConstants.KIND_LOAD_BALANCER_SERVICE);
    }

    @Test(expected = ValidationErrorException.class)
    public void validateLbConfigRejectsRuleWithoutSelectorOrService() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWithPortRules(rule("sourcePort", Integer.valueOf(80)));

        filter.validateLbConfig(request, ServiceConstants.KIND_LOAD_BALANCER_SERVICE);
    }

    @Test(expected = ValidationErrorException.class)
    public void validateLbConfigRejectsRuleWithSelectorAndService() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWithPortRules(rule("selector", "app", "serviceId", "1s1", "targetPort",
                Integer.valueOf(80)));

        filter.validateLbConfig(request, ServiceConstants.KIND_LOAD_BALANCER_SERVICE);
    }

    @Test(expected = ValidationErrorException.class)
    public void validateLbConfigRejectsServiceRuleWithoutTargetPort() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWithPortRules(rule("serviceId", "1s1"));

        filter.validateLbConfig(request, ServiceConstants.KIND_LOAD_BALANCER_SERVICE);
    }

    @Test(expected = ValidationErrorException.class)
    public void validatePortsRejectsReservedLoadBalancerHealthCheckPort() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWithPortRules(rule("selector", "app", "sourcePort", Integer.valueOf(42)));

        filter.validatePorts(null, ServiceConstants.KIND_LOAD_BALANCER_SERVICE, request);
    }

    @Test
    public void portRulesConvertsMapElementsToPortRules() {
        List<PortRule> portRules = ServiceValidationFilter.portRules(new JacksonJsonMapper(),
                Arrays.asList(rule("selector", "app", "sourcePort", Integer.valueOf(80))));

        assertEquals("app", portRules.get(0).getSelector());
        assertEquals(Integer.valueOf(80), portRules.get(0).getSourcePort());
    }

    @Test
    public void requestMapFieldPreservesMapValue() {
        ApiRequest request = requestWithLbConfig(rule("selector", "app"));

        assertEquals("app", ServiceValidationFilter.requestMapField(request, ServiceConstants.FIELD_LB_CONFIG)
                .get("selector"));
    }

    @Test(expected = ClassCastException.class)
    public void requestMapFieldRejectsNonMapValue() {
        ApiRequest request = requestWithLbConfig("not-a-map");

        ServiceValidationFilter.requestMapField(request, ServiceConstants.FIELD_LB_CONFIG);
    }

    private static ServiceValidationFilter filter() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        filter.jsonMapper = new JacksonJsonMapper();
        return filter;
    }

    @SafeVarargs
    private static ApiRequest requestWithPortRules(Map<String, Object>... portRules) {
        Map<String, Object> lbConfig = new HashMap<>();
        lbConfig.put(ServiceConstants.FIELD_PORT_RULES, Arrays.asList(portRules));

        return requestWithLbConfig(lbConfig);
    }

    private static ApiRequest requestWithLbConfig(Object lbConfig) {
        Map<String, Object> data = new HashMap<>();
        data.put(ServiceConstants.FIELD_LB_CONFIG, lbConfig);

        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(data);
        return request;
    }

    private static Map<String, Object> rule(Object... keyValues) {
        Map<String, Object> rule = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            rule.put(String.class.cast(keyValues[i]), keyValues[i + 1]);
        }
        return rule;
    }
}
