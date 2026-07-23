package io.cattle.platform.process.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class EnvironmentUpgradeTest {

    @Test
    public void launchConfigReturnsEmptyMapWhenFieldIsMissing() {
        assertTrue(EnvironmentUpgrade.launchConfig(null).isEmpty());
    }

    @Test
    public void launchConfigCopiesStringKeysInOrderAndPreservesValues() {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("imageUuid", "rancher/lb-service-haproxy:v0.9.14");
        fields.put("ports", Arrays.asList("80:80/tcp"));

        Map<String, Object> result = EnvironmentUpgrade.launchConfig(fields);

        assertEquals(fields, result);
        assertEquals(new ArrayList<String>(fields.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void launchConfigRejectsNonStringKeys() {
        Map<Object, Object> fields = new LinkedHashMap<Object, Object>();
        fields.put(42, "rancher/lb-service-haproxy:v0.9.14");

        EnvironmentUpgrade.launchConfig(fields);
    }

    @Test
    public void stringListCopiesStringValues() {
        assertEquals(Arrays.asList("80:80/tcp", "443:443/tcp"),
                EnvironmentUpgrade.stringList(Arrays.asList("80:80/tcp", "443:443/tcp")));
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonStringValues() {
        EnvironmentUpgrade.stringList(Arrays.asList("80:80/tcp", Boolean.TRUE));
    }

    @Test
    public void labelMapForWriteReturnsMutableMapWhenFieldIsMissing() {
        Map<String, String> labels = EnvironmentUpgrade.labelMapForWrite(null);

        labels.put("io.rancher.container.create_agent", "true");

        assertEquals("true", labels.get("io.rancher.container.create_agent"));
    }

    @Test
    public void labelMapForWriteCopiesStringEntries() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("io.rancher.scheduler.global", "true");

        assertEquals(labels, EnvironmentUpgrade.labelMapForWrite(labels));
    }

    @Test(expected = ClassCastException.class)
    public void labelMapForWriteRejectsNonStringValues() {
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("io.rancher.scheduler.global", Boolean.TRUE);

        EnvironmentUpgrade.labelMapForWrite(labels);
    }
}
