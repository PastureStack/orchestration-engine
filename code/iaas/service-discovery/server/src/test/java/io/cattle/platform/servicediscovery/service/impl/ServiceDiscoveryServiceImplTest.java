package io.cattle.platform.servicediscovery.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryServiceImplTest {

    @Test
    public void stringListReturnsEmptyListWhenMissing() {
        assertTrue(ServiceDiscoveryServiceImpl.stringList(null).isEmpty());
    }

    @Test
    public void stringListCopiesStringValuesInOrder() {
        List<String> ports = new ArrayList<String>();
        ports.add("80/tcp");
        ports.add("443:443/tcp");

        assertEquals(ports, ServiceDiscoveryServiceImpl.stringList(ports));
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonListValues() {
        ServiceDiscoveryServiceImpl.stringList("80/tcp");
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonStringValues() {
        List<Object> ports = new ArrayList<Object>();
        ports.add(Integer.valueOf(80));

        ServiceDiscoveryServiceImpl.stringList(ports);
    }

    @Test
    public void stringMapReturnsEmptyMapWhenMissing() {
        assertTrue(ServiceDiscoveryServiceImpl.stringMap(null).isEmpty());
    }

    @Test
    public void stringMapCopiesStringEntriesInOrder() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("app", "web");
        labels.put("tier", "frontend");

        Map<String, String> result = ServiceDiscoveryServiceImpl.stringMap(labels);

        assertEquals(labels, result);
        assertEquals(new ArrayList<String>(labels.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void stringMapRejectsNonMapValues() {
        ServiceDiscoveryServiceImpl.stringMap("app=web");
    }

    @Test(expected = ClassCastException.class)
    public void stringMapRejectsNonStringKeys() {
        Map<Object, String> labels = new LinkedHashMap<Object, String>();
        labels.put(Integer.valueOf(1), "web");

        ServiceDiscoveryServiceImpl.stringMap(labels);
    }

    @Test(expected = ClassCastException.class)
    public void stringMapRejectsNonStringValues() {
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("app", Boolean.TRUE);

        ServiceDiscoveryServiceImpl.stringMap(labels);
    }

    @Test
    public void objectListReturnsEmptyListWhenMissing() {
        assertTrue(ServiceDiscoveryServiceImpl.objectList(null).isEmpty());
    }

    @Test
    public void objectListKeepsExistingValues() {
        List<Object> values = new ArrayList<Object>();
        values.add("primary");
        values.add(Integer.valueOf(1));

        assertEquals(values, ServiceDiscoveryServiceImpl.objectList(values));
    }

    @Test(expected = ClassCastException.class)
    public void objectListRejectsNonListValues() {
        ServiceDiscoveryServiceImpl.objectList("secondary");
    }

    @Test
    public void stringObjectMapReturnsEmptyMapWhenMissing() {
        assertTrue(ServiceDiscoveryServiceImpl.stringObjectMap(null).isEmpty());
    }

    @Test
    public void stringObjectMapCopiesStringKeysAndObjectValuesInOrder() {
        Map<String, Object> launchConfig = new LinkedHashMap<String, Object>();
        launchConfig.put("imageUuid", "docker:busybox");
        launchConfig.put("ports", Integer.valueOf(80));

        Map<String, Object> result = ServiceDiscoveryServiceImpl.stringObjectMap(launchConfig);

        assertEquals(launchConfig, result);
        assertEquals(new ArrayList<String>(launchConfig.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void stringObjectMapRejectsNonMapValues() {
        ServiceDiscoveryServiceImpl.stringObjectMap("image=busybox");
    }

    @Test(expected = ClassCastException.class)
    public void stringObjectMapRejectsNonStringKeys() {
        Map<Object, Object> launchConfig = new LinkedHashMap<Object, Object>();
        launchConfig.put(Integer.valueOf(1), "docker:busybox");

        ServiceDiscoveryServiceImpl.stringObjectMap(launchConfig);
    }
}
