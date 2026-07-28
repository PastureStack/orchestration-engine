package io.cattle.platform.metadata.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class MetadataServiceImplTest {

    @Test
    public void metadataMapPreservesNull() {
        assertNull(new MetadataServiceImpl().metadataMap(null));
    }

    @Test
    public void metadataMapKeepsMapValues() {
        Map<String, Object> source = new HashMap<String, Object>();
        source.put("key", "value");

        Map<String, Object> result = new MetadataServiceImpl().metadataMap(source);

        assertSame(source, result);
        assertEquals("value", result.get("key"));
    }

    @Test(expected = ClassCastException.class)
    public void metadataMapRejectsNonMapValues() {
        new MetadataServiceImpl().metadataMap("not-a-map");
    }
}
