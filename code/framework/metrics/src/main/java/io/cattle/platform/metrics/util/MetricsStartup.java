package io.cattle.platform.metrics.util;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jmx.JmxReporter;

public class MetricsStartup {

    private JmxReporter reporter;

    public synchronized void start() {
        if (reporter != null) {
            return;
        }

        MetricRegistry registry = MetricsUtil.getRegistry();
        JmxReporter next = JmxReporter.forRegistry(registry).build();
        next.start();
        reporter = next;
    }

    public synchronized void stop() {
        if (reporter == null) {
            return;
        }

        reporter.stop();
        reporter = null;
    }

    public synchronized boolean isRunning() {
        return reporter != null;
    }

}
