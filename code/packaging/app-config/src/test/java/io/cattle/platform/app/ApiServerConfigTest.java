package io.cattle.platform.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class ApiServerConfigTest {

    @Test
    public void freemarkerConfigKeepsLegacyCompatibleVersionExplicitly() {
        freemarker.template.Configuration config = new ApiServerConfig().FreemarkerConfig();

        assertEquals(freemarker.template.Configuration.VERSION_2_3_0, config.getIncompatibleImprovements());
        assertFalse(config.getLocalizedLookup());
        assertEquals("computer", config.getNumberFormat());
    }
}
