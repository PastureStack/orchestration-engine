package io.cattle.platform.eventing.impl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class AbstractEventServiceTest {

    @Test
    public void defaultEventCallSettingsReadDynamicValuesThroughWrapper() {
        final String retriesKey = "eventing.retry";
        final String timeoutKey = "eventing.timeout.millis";

        try {
            ConfigurationManager.getConfigInstance().setProperty(retriesKey, "3");
            ConfigurationManager.getConfigInstance().setProperty(timeoutKey, "15000");

            assertEquals(Integer.valueOf(3), AbstractEventService.DEFAULT_RETRIES.get());
            assertEquals(Long.valueOf(15000L), AbstractEventService.DEFAULT_TIMEOUT.get());
        } finally {
            clearProperty(retriesKey);
            clearProperty(timeoutKey);
        }
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }
}
