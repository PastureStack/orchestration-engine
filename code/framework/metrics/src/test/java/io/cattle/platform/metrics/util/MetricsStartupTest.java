package io.cattle.platform.metrics.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codahale.metrics.MetricRegistry;

public class MetricsStartupTest {

    @Test
    public void startAndStopAreIdempotent() {
        MetricRegistry original = MetricsUtil.getRegistry();
        MetricsStartup startup = new MetricsStartup();

        try {
            MetricsUtil.setRegistry(new MetricRegistry());

            assertFalse(startup.isRunning());

            startup.start();
            startup.start();
            assertTrue(startup.isRunning());

            startup.stop();
            startup.stop();
            assertFalse(startup.isRunning());
        } finally {
            startup.stop();
            MetricsUtil.setRegistry(original);
        }
    }
}
