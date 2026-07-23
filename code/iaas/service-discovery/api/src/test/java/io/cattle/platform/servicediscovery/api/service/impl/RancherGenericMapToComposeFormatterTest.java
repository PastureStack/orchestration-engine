package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class RancherGenericMapToComposeFormatterTest {

    @Test
    public void lowerCaseParametersMutatesMapKeysInPlace() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("innerValue", "present");

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requestTimeout", 30);
        value.put("nestedConfig", nested);
        value.put("emptyValue", null);

        Object result = RancherGenericMapToComposeFormatter.lowerCaseParameters(value);

        assertSame(value, result);
        assertEquals(2, value.size());
        assertEquals(Integer.valueOf(30), value.get("request_timeout"));
        assertSame(nested, value.get("nested_config"));
        assertEquals("present", nested.get("inner_value"));
    }

    @Test
    public void lowerCaseParametersReturnsNonMapValuesUnchanged() {
        Object value = "not-a-map";

        assertSame(value, RancherGenericMapToComposeFormatter.lowerCaseParameters(value));
    }

    @Test(expected = ClassCastException.class)
    public void lowerCaseParametersRejectsNonStringKeys() {
        Map<Object, Object> value = new HashMap<>();
        value.put(Integer.valueOf(1), "bad-key");

        RancherGenericMapToComposeFormatter.lowerCaseParameters(value);
    }
}
