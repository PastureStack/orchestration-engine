package io.cattle.platform.jmx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

class JmxMetricBuffer {

    private final ConcurrentLinkedQueue<GraphiteMetric> metrics = new ConcurrentLinkedQueue<GraphiteMetric>();

    void addAll(List<GraphiteMetric> newMetrics) {
        if (newMetrics == null || newMetrics.isEmpty()) {
            return;
        }
        metrics.addAll(newMetrics);
    }

    List<GraphiteMetric> drain() {
        List<GraphiteMetric> drained = new ArrayList<GraphiteMetric>();
        GraphiteMetric metric;
        while ((metric = metrics.poll()) != null) {
            drained.add(metric);
        }
        return drained;
    }
}
