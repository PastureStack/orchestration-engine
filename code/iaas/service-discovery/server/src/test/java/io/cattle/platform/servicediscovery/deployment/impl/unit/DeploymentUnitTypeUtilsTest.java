package io.cattle.platform.servicediscovery.deployment.impl.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;

public class DeploymentUnitTypeUtilsTest {

    @Test
    public void stringListReturnsEmptyListWhenMissing() {
        assertTrue(DeploymentUnitTypeUtils.stringList(null).isEmpty());
    }

    @Test
    public void stringListCopiesStringValues() {
        assertEquals(Arrays.asList("10.0.0.10", "10.0.0.11"),
                DeploymentUnitTypeUtils.stringList(Arrays.asList("10.0.0.10", "10.0.0.11")));
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonStringValues() {
        DeploymentUnitTypeUtils.stringList(Arrays.asList("10.0.0.10", Boolean.TRUE));
    }

    @Test
    public void integerListReturnsEmptyListWhenMissing() {
        assertTrue(DeploymentUnitTypeUtils.integerList(null).isEmpty());
    }

    @Test
    public void integerListCopiesIntegerValues() {
        assertEquals(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2)),
                DeploymentUnitTypeUtils.integerList(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2))));
    }

    @Test(expected = ClassCastException.class)
    public void integerListRejectsNonIntegerValues() {
        DeploymentUnitTypeUtils.integerList(Arrays.asList(Integer.valueOf(1), "2"));
    }

    @Test
    public void stringMapReturnsEmptyMapWhenMissing() {
        assertTrue(DeploymentUnitTypeUtils.stringMap(null).isEmpty());
    }

    @Test
    public void stringMapCopiesStringEntriesInOrder() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("io.rancher.deployment.unit", "du-1");
        labels.put("io.rancher.service.launch.config", "primary");

        Map<String, String> result = DeploymentUnitTypeUtils.stringMap(labels);

        assertEquals(labels, result);
        assertEquals(new ArrayList<String>(labels.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void stringMapRejectsNonStringValues() {
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("io.rancher.deployment.unit", Boolean.TRUE);

        DeploymentUnitTypeUtils.stringMap(labels);
    }

    @Test
    public void stringPairCopiesExternalServiceIpAndHost() {
        Pair<String, String> result = DeploymentUnitTypeUtils.stringPair(Pair.of("10.0.0.10", "app.example.test"));

        assertEquals("10.0.0.10", result.getLeft());
        assertEquals("app.example.test", result.getRight());
    }

    @Test(expected = ClassCastException.class)
    public void stringPairRejectsNonStringValues() {
        DeploymentUnitTypeUtils.stringPair(Pair.of("10.0.0.10", Boolean.TRUE));
    }
}
