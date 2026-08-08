package io.cattle.platform.docker.process.instancehostmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;

public class DockerPostInstanceHostMapActivateTest {

    @Test
    public void ipAddressDaoKeepsRuntimeInjectionAnnotation() throws Exception {
        Field field = DockerPostInstanceHostMapActivate.class.getDeclaredField("ipAddressDao");

        assertTrue(field.isAnnotationPresent(Inject.class));
    }

    @Test
    public void stringListOrNullPreservesNullAndStringValues() {
        assertNull(DockerPostInstanceHostMapActivate.stringListOrNull(null));

        List<String> result = DockerPostInstanceHostMapActivate.stringListOrNull(Arrays.<Object>asList("80:80/tcp", null));

        assertEquals(Arrays.asList("80:80/tcp", null), result);
    }

    @Test
    public void objectListOrNullCopiesMountPayloads() {
        Map<String, Object> mount = new HashMap<String, Object>();
        mount.put("Destination", "/data");

        List<Object> result = DockerPostInstanceHostMapActivate.objectListOrNull(Arrays.<Object>asList(mount));

        assertEquals(Arrays.<Object>asList(mount), result);
    }

    @Test
    public void stringMapOrEmptyReadsDockerLabels() {
        Map<String, Object> labels = new HashMap<String, Object>();
        labels.put("io.rancher.container.name", "web");
        labels.put("io.rancher.stack.name", null);

        Map<String, String> result = DockerPostInstanceHostMapActivate.stringMapOrEmpty(labels);

        assertEquals("web", result.get("io.rancher.container.name"));
        assertTrue(result.containsKey("io.rancher.stack.name"));
        assertNull(result.get("io.rancher.stack.name"));
        assertTrue(DockerPostInstanceHostMapActivate.stringMapOrEmpty("not-a-map").isEmpty());
    }

    @Test
    public void stringObjectMapOrNullReadsInspectData() {
        Map<String, Object> inspect = new HashMap<String, Object>();
        inspect.put("Config", new HashMap<String, Object>());

        Map<String, Object> result = DockerPostInstanceHostMapActivate.stringObjectMapOrNull(inspect);

        assertEquals(inspect, result);
        assertNull(DockerPostInstanceHostMapActivate.stringObjectMapOrNull(null));
    }

    @Test
    public void nestedValueReadsDockerInspectLabels() {
        Map<String, Object> labels = new HashMap<String, Object>();
        labels.put("io.rancher.container.name", "web");
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("Labels", labels);
        Map<String, Object> inspect = new HashMap<String, Object>();
        inspect.put("Config", config);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("dockerInspect", inspect);

        Object result = DockerPostInstanceHostMapActivate.nestedValue(data, "dockerInspect", "Config", "Labels");

        assertEquals(labels, result);
        assertNull(DockerPostInstanceHostMapActivate.nestedValue(data, "dockerInspect", "Missing", "Labels"));
    }
}
