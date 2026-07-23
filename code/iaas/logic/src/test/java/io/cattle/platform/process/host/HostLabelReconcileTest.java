package io.cattle.platform.process.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class HostLabelReconcileTest {

    @Test
    public void hostLabelsCopiesStringsIntoCaseInsensitiveMap() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("Environment", "prod");
        labels.put("io.rancher.host", "true");

        Map<String, String> result = HostLabelReconcile.hostLabels(labels);

        assertEquals("prod", result.get("environment"));
        assertEquals("true", result.get("IO.RANCHER.HOST"));
    }

    @Test
    public void hostLabelsAcceptsEmptyMap() {
        assertTrue(HostLabelReconcile.hostLabels(new LinkedHashMap<String, String>()).isEmpty());
    }

    @Test(expected = ClassCastException.class)
    public void hostLabelsRejectsNonStringKeys() {
        Map<Object, String> labels = new LinkedHashMap<Object, String>();
        labels.put(42, "prod");

        HostLabelReconcile.hostLabels(labels);
    }

    @Test(expected = ClassCastException.class)
    public void hostLabelsRejectsNonStringValues() {
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("Environment", Boolean.TRUE);

        HostLabelReconcile.hostLabels(labels);
    }
}
