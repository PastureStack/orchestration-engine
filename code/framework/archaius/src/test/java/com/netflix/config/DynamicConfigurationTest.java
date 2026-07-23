package com.netflix.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class DynamicConfigurationTest {

    @Test
    public void refreshFiresCallbacksOutsideSourceConfigurationLock() {
        DynamicConfiguration source = new DynamicConfiguration();
        ConcurrentCompositeConfiguration composite = new ConcurrentCompositeConfiguration();
        composite.addConfiguration(source);
        DynamicPropertyFactory.initWithConfigurationSource(composite);

        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        AtomicBoolean sourceLockHeldByCallback = new AtomicBoolean(true);
        DynamicStringProperty property = DynamicPropertyFactory.getInstance().getStringProperty(
                "rc16.dynamic.refresh.test", "default");
        property.addCallback(() -> {
            callbackInvoked.set(true);
            sourceLockHeldByCallback.set(Thread.holdsLock(source));
            DynamicPropertyFactory.getInstance().getStringProperty("rc16.dynamic.refresh.peer", "default").get();
        });

        source.setSource(new FixedSource(mapOf(
                "rc16.dynamic.refresh.test", "updated",
                "rc16.dynamic.refresh.peer", "peer")));

        assertTrue(callbackInvoked.get());
        assertFalse(sourceLockHeldByCallback.get());
    }

    private static Map<String, Object> mapOf(String firstKey, String firstValue, String secondKey, String secondValue) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(firstKey, firstValue);
        result.put(secondKey, secondValue);
        return result;
    }

    private static class FixedSource implements PolledConfigurationSource {
        private final Map<String, Object> values;

        FixedSource(Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public PollResult poll(boolean initial, Object checkPoint) {
            return PollResult.createFull(values);
        }
    }
}
