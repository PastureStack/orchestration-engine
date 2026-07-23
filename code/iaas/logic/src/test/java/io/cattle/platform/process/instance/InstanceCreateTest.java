package io.cattle.platform.process.instance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class InstanceCreateTest {

    @Test
    public void instanceLabelsReturnsNullWhenFieldIsMissing() {
        assertNull(InstanceCreate.instanceLabels(null));
    }

    @Test
    public void instanceLabelsCopiesStringEntriesInOrder() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("io.rancher.container.name", "app");
        labels.put("environment", "prod");

        Map<String, String> result = InstanceCreate.instanceLabels(labels);

        assertEquals(labels, result);
        assertEquals(new ArrayList<String>(labels.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void instanceLabelsRejectsNonStringKeys() {
        Map<Object, String> labels = new LinkedHashMap<Object, String>();
        labels.put(42, "prod");

        InstanceCreate.instanceLabels(labels);
    }

    @Test(expected = ClassCastException.class)
    public void instanceLabelsRejectsNonStringValues() {
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("environment", Boolean.TRUE);

        InstanceCreate.instanceLabels(labels);
    }
}
