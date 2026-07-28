package io.cattle.platform.archaius.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.sources.NamedMapConfiguration;

import java.util.Iterator;
import java.util.Properties;

import org.junit.Test;

import com.netflix.config.ConcurrentCompositeConfiguration;

public class ArchaiusConfigurationViewTest {

    @Test
    public void exposesPropertyKeysAndNamedSourceFromCompositeConfiguration() {
        Properties props = new Properties();
        props.setProperty("setting.public", "value");

        NamedMapConfiguration named = new NamedMapConfiguration(props);
        named.setSourceName("UnitTestSource");

        ConcurrentCompositeConfiguration composite = new ConcurrentCompositeConfiguration();
        composite.addConfiguration(named);

        ArchaiusConfigurationView view = ArchaiusConfigurationView.from(composite);

        assertEquals("value", view.getProperty("setting.public"));
        assertEquals("UnitTestSource", view.getSourceName("setting.public"));
        assertTrue(contains(view.getKeys(), "setting.public"));
    }

    @Test
    public void returnsNullWhenCurrentConfigurationIsUnavailable() {
        assertNull(ArchaiusConfigurationView.from(null));
    }

    private boolean contains(Iterator<String> keys, String expected) {
        while (keys.hasNext()) {
            if (expected.equals(keys.next())) {
                return true;
            }
        }

        return false;
    }

}
