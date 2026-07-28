package io.cattle.platform.jmx;

import java.io.IOException;
import java.util.List;

interface GraphiteMetricWriter {

    void write(List<GraphiteMetric> metrics) throws IOException;
}
