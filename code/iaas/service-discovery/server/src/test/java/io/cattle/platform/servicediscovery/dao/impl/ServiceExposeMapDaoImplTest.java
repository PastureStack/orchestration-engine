package io.cattle.platform.servicediscovery.dao.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceExposeMapDaoImplTest {

    @Test
    public void instanceLabelsReturnsEmptyMapWhenMissing() {
        assertTrue(ServiceExposeMapDaoImpl.instanceLabels(null).isEmpty());
    }

    @Test
    public void instanceLabelsCopiesStringEntriesInOrder() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("io.rancher.service.launch.config", "sidekick");
        labels.put("io.rancher.deployment.unit", "du-1");

        Map<String, String> result = ServiceExposeMapDaoImpl.instanceLabels(labels);

        assertEquals(labels, result);
        assertEquals(new ArrayList<String>(labels.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void instanceLabelsRejectsNonStringKeys() {
        Map<Object, String> labels = new LinkedHashMap<Object, String>();
        labels.put(42, "sidekick");

        ServiceExposeMapDaoImpl.instanceLabels(labels);
    }

    @Test(expected = ClassCastException.class)
    public void instanceLabelsRejectsNonStringValues() {
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("io.rancher.service.launch.config", Boolean.TRUE);

        ServiceExposeMapDaoImpl.instanceLabels(labels);
    }
}
