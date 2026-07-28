package io.cattle.platform.configitem.context.data.metadata.common;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class MetadataTypeUtilsTest {

    @Test
    public void stringListCopiesStringValues() {
        List<String> result = MetadataTypeUtils.stringList(Arrays.asList("80:80/tcp", "443:443/tcp"));

        assertEquals(Arrays.asList("80:80/tcp", "443:443/tcp"), result);
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonStringValues() {
        MetadataTypeUtils.stringList(Arrays.<Object>asList("80:80/tcp", 443));
    }

    @Test
    public void stringMapCopiesStringEntriesInOrder() {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put("io.rancher.stack.name", "network-services");
        source.put("io.rancher.container.pull_image", "always");

        Map<String, String> result = MetadataTypeUtils.stringMap(source);

        assertEquals(source, result);
    }

    @Test(expected = ClassCastException.class)
    public void stringMapRejectsNonStringValues() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("valid", "true");
        source.put("invalid", Boolean.TRUE);

        MetadataTypeUtils.stringMap(source);
    }
}
