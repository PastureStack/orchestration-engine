package io.cattle.platform.task.dao.impl;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.archaius.util.ConfigProperty;

import java.lang.reflect.Field;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class TaskDaoImplTest {

    @Test
    public void purgeAfterSecondsReadsDynamicConfigThroughWrapper() throws Exception {
        final String key = "task.purge.after.seconds";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "86400");

            assertEquals(Long.valueOf(86400L), afterSeconds().get());
        } finally {
            clearProperty(key);
        }
    }

    private ConfigProperty<?> afterSeconds() throws Exception {
        Field field = TaskDaoImpl.class.getDeclaredField("AFTER_SECONDS");
        field.setAccessible(true);
        return ConfigProperty.class.cast(field.get(null));
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }
}
