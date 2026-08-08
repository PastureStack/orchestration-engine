package io.cattle.platform.jmx;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compatibility publisher for the legacy Rancher 1.6 jmxtrans settings.
 *
 * This class intentionally keeps the old class name so existing Spring wiring and
 * operational references remain stable, but it no longer loads the abandoned
 * embedded-jmxtrans runtime.
 */
public class EmbeddedJmxTransPublisher implements JmxPublisher {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedJmxTransPublisher.class);

    static final String DEFAULT_INTERNAL_MBEAN = "org.jmxtrans.embedded:name=default,type=EmbeddedJmxTrans";

    @Override
    public void start(JmxPublisherConfig config) throws Exception {
        JmxTransJsonParser parser = new JmxTransJsonParser();
        List<JmxQuery> queries = parser.parse(config.getResources());
        JmxPublisherStats stats = new JmxPublisherStats();
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        registerStatsMBean(server, stats);

        if (isBlank(config.getGraphiteHost())) {
            log.info("JMX metrics publisher loaded {} queries; graphite.host is empty so export is disabled",
                    queries.size());
            return;
        }

        GraphiteMetricWriter writer = new GraphitePlainTextWriter(config.getGraphiteHost(), config.getGraphitePort(),
                config.getGraphiteOptions());
        JmxMetricCollector collector = new JmxMetricCollector(server, stats);
        JmxMetricBuffer buffer = new JmxMetricBuffer();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new JmxThreadFactory());

        int queryInterval = Math.max(1, config.getQueryIntervalSeconds());
        int exportInterval = Math.max(queryInterval, config.getExportIntervalSeconds());
        String metricPrefix = metricPrefix(config);

        executor.scheduleAtFixedRate(new CollectTask(collector, queries, buffer, metricPrefix), 0, queryInterval,
                TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(new ExportTask(writer, buffer, stats), exportInterval, exportInterval,
                TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownTask(executor), "rc16-jmx-publisher-shutdown"));
        log.info("JMX metrics publisher loaded {} queries; queryInterval={}s exportInterval={}s graphite={}:{}",
                queries.size(), queryInterval, exportInterval, config.getGraphiteHost(), config.getGraphitePort());
    }

    private void registerStatsMBean(MBeanServer server, JmxPublisherStats stats) {
        try {
            ObjectName objectName = new ObjectName(DEFAULT_INTERNAL_MBEAN);
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
            }
            server.registerMBean(stats, objectName);
        } catch (Exception e) {
            log.warn("Failed to register JMX publisher compatibility MBean", e);
        }
    }

    private String metricPrefix(JmxPublisherConfig config) {
        String rootPrefix = config.getGraphiteOptions().get(GraphitePlainTextWriter.OPTION_ROOT_PREFIX);
        String namePrefix = config.getGraphiteOptions().get(GraphitePlainTextWriter.OPTION_NAME_PREFIX);
        String metricPathPrefix = config.getGraphiteOptions().get(GraphitePlainTextWriter.OPTION_METRIC_PATH_PREFIX);

        String prefix = firstNonBlank(metricPathPrefix, namePrefix);
        if (isBlank(prefix) && !isBlank(config.getServerId())) {
            prefix = "servers." + config.getServerId();
        }
        if (!isBlank(rootPrefix)) {
            prefix = isBlank(prefix) ? rootPrefix : rootPrefix + "." + prefix;
        }
        return GraphiteMetric.sanitizePath(prefix);
    }

    private static String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return isBlank(second) ? null : second;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class CollectTask implements Runnable {
        private final JmxMetricCollector collector;
        private final List<JmxQuery> queries;
        private final JmxMetricBuffer buffer;
        private final String metricPrefix;

        CollectTask(JmxMetricCollector collector, List<JmxQuery> queries, JmxMetricBuffer buffer, String metricPrefix) {
            this.collector = collector;
            this.queries = queries;
            this.buffer = buffer;
            this.metricPrefix = metricPrefix;
        }

        @Override
        public void run() {
            try {
                buffer.addAll(collector.collect(queries, metricPrefix));
            } catch (Exception e) {
                log.warn("Failed to collect JMX metrics", e);
            }
        }
    }

    private static class ExportTask implements Runnable {
        private final GraphiteMetricWriter writer;
        private final JmxMetricBuffer buffer;
        private final JmxPublisherStats stats;

        ExportTask(GraphiteMetricWriter writer, JmxMetricBuffer buffer, JmxPublisherStats stats) {
            this.writer = writer;
            this.buffer = buffer;
            this.stats = stats;
        }

        @Override
        public void run() {
            List<GraphiteMetric> metrics = buffer.drain();
            if (metrics.isEmpty()) {
                return;
            }

            long start = System.nanoTime();
            try {
                writer.write(metrics);
                stats.exported(metrics.size(), System.nanoTime() - start);
            } catch (Exception e) {
                log.warn("Failed to export JMX metrics to Graphite", e);
            }
        }
    }

    private static class ShutdownTask implements Runnable {
        private final ScheduledExecutorService executor;

        ShutdownTask(ScheduledExecutorService executor) {
            this.executor = executor;
        }

        @Override
        public void run() {
            executor.shutdownNow();
        }
    }
}
