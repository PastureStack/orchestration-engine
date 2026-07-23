package io.cattle.platform.iaas.api.request.handler;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class ConfigurableRequestOptionsParserTest {

    @After
    public void clearProperties() {
        clear("api.request.options");
    }

    @Test
    public void archaiusSettingsReadDynamicOptions() {
        ConfigurationManager.getConfigInstance().setProperty("api.request.options", "one,two");

        RequestOptionsSettings settings = ArchaiusRequestOptionsSettings.create();

        assertEquals(Arrays.asList("one", "two"), settings.options());

        ConfigurationManager.getConfigInstance().setProperty("api.request.options", "three");

        assertEquals(Arrays.asList("three"), settings.options());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSettings() {
        new ConfigurableRequestOptionsParser(null);
    }

    @Test
    public void injectedSettingsDriveParserOptions() {
        ConfigurableRequestOptionsParser parser = new ConfigurableRequestOptionsParser(settings(Arrays.asList("a", "b")));

        assertEquals(Arrays.asList("a", "b"), parser.getOptions());
    }

    private static RequestOptionsSettings settings(final List<String> options) {
        return new RequestOptionsSettings() {
            @Override
            public List<String> options() {
                return options;
            }
        };
    }

    private void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }
}
