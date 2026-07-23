package io.cattle.platform.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.beans.PropertyDescriptor;
import java.time.Duration;

import org.apache.commons.beanutils2.PropertyUtils;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class PoolConfigTest {

    @Test
    public void normalizeValueTreatsBlankValuesAsMissing() {
        assertNull(PoolConfig.normalizeValue(null));
        assertNull(PoolConfig.normalizeValue(""));
        assertNull(PoolConfig.normalizeValue("   "));
        assertEquals("15", PoolConfig.normalizeValue("15"));
    }

    @Test
    public void durationPropertiesAreSkippedForCommonsPoolCompatibility() throws Exception {
        assertTrue(PoolConfig.isModernDurationProperty(getDescriptor(SamplePoolConfig.class, "maxWaitDuration")));
        assertFalse(PoolConfig.isModernDurationProperty(getDescriptor(SamplePoolConfig.class, "maxWaitMillis")));
    }

    @Test
    public void getPropertyReadsDynamicConfigThroughWrapper() {
        final String key = "pool.config.test.value";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "configured");

            assertEquals("configured", PoolConfig.getProperty(key));
        } finally {
            clearProperty(key);
        }
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    protected PropertyDescriptor getDescriptor(Class<?> type, String name) {
        for (PropertyDescriptor desc : PropertyUtils.getPropertyDescriptors(type)) {
            if (name.equals(desc.getName())) {
                return desc;
            }
        }

        throw new IllegalArgumentException("No descriptor found: " + name);
    }

    public static class SamplePoolConfig {

        public void setMaxWaitDuration(Duration maxWaitDuration) {
        }

        public void setMaxWaitMillis(long maxWaitMillis) {
        }

    }

}
