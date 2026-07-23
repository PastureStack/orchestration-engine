package io.cattle.platform.api.auth.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class ArchaiusPolicyOptionsTest {

    @After
    public void clearProperties() {
        clear("account.type.test.enabled");
        clear("account.type.test.mode");
    }

    @Test
    public void readsDynamicBooleanAndStringOptions() {
        ConfigurationManager.getConfigInstance().setProperty("account.type.test.enabled", true);
        ConfigurationManager.getConfigInstance().setProperty("account.type.test.mode", "initial");

        ArchaiusPolicyOptions options = new ArchaiusPolicyOptions("test");

        assertTrue(options.isOption("enabled"));
        assertEquals("initial", options.getOption("mode"));

        ConfigurationManager.getConfigInstance().setProperty("account.type.test.enabled", false);
        ConfigurationManager.getConfigInstance().setProperty("account.type.test.mode", "updated");

        assertFalse(options.isOption("enabled"));
        assertEquals("updated", options.getOption("mode"));
    }

    @Test
    public void optionCallbackOverridesConfigProperty() {
        ConfigurationManager.getConfigInstance().setProperty("account.type.test.mode", "configured");
        ArchaiusPolicyOptions options = new ArchaiusPolicyOptions("test");
        options.callbacks.put("mode", new OptionCallback() {
            @Override
            public String getOption() {
                return "callback";
            }
        });

        assertEquals("callback", options.getOption("mode"));
    }

    private void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }
}
