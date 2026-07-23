package io.cattle.platform.lock.definition;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class AbstractBlockingLockDefinitionTest {

    @Test
    public void defaultWaitReadsDynamicConfigThroughWrapper() {
        final String key = "default.lock.wait.millis";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "12345");

            TestBlockingLockDefinition lock = new TestBlockingLockDefinition("test-lock");
            assertEquals(12345L, lock.getWait());
        } finally {
            if (ConfigurationManager.getConfigInstance().containsKey(key)) {
                ConfigurationManager.getConfigInstance().clearProperty(key);
            }
        }
    }

    private static class TestBlockingLockDefinition extends AbstractBlockingLockDefintion {

        TestBlockingLockDefinition(String lockId) {
            super(lockId);
        }
    }
}
