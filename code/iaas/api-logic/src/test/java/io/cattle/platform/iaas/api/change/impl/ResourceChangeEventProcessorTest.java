package io.cattle.platform.iaas.api.change.impl;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class ResourceChangeEventProcessorTest {

    @Test
    public void stringObjectMapKeepsStringKeysAndValues() {
        Map<Object, Object> input = new LinkedHashMap<Object, Object>();
        input.put("id", "1");
        input.put("actions", Integer.valueOf(2));

        Map<String, Object> result = ResourceChangeEventProcessor.stringObjectMap(input);

        assertEquals("1", result.get("id"));
        assertEquals(Integer.valueOf(2), result.get("actions"));
    }

    @Test(expected = ClassCastException.class)
    public void stringObjectMapRejectsNonMapValues() {
        ResourceChangeEventProcessor.stringObjectMap("invalid");
    }

    @Test(expected = ClassCastException.class)
    public void stringObjectMapRejectsNonStringKeys() {
        Map<Object, Object> input = new LinkedHashMap<Object, Object>();
        input.put(Integer.valueOf(1), "invalid");

        ResourceChangeEventProcessor.stringObjectMap(input);
    }
}
