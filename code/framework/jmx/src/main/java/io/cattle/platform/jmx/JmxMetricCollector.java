package io.cattle.platform.jmx;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

class JmxMetricCollector {

    private final MBeanServer server;
    private final JmxPublisherStats stats;

    JmxMetricCollector(MBeanServer server, JmxPublisherStats stats) {
        this.server = server;
        this.stats = stats;
    }

    List<GraphiteMetric> collect(List<JmxQuery> queries, String metricPrefix) throws Exception {
        long start = System.nanoTime();
        List<GraphiteMetric> metrics = new ArrayList<GraphiteMetric>();
        long timestampSeconds = System.currentTimeMillis() / 1000L;

        for (JmxQuery query : queries) {
            Set<ObjectName> objectNames = server.queryNames(new ObjectName(query.getObjectName()), null);
            for (ObjectName objectName : objectNames) {
                collectObject(query, objectName, metricPrefix, timestampSeconds, metrics);
            }
        }

        stats.collected(metrics.size(), System.nanoTime() - start);
        return metrics;
    }

    private void collectObject(JmxQuery query, ObjectName objectName, String metricPrefix, long timestampSeconds,
            List<GraphiteMetric> metrics) {
        String alias = alias(query.getResultAlias(), objectName);
        for (JmxAttributeQuery attribute : query.getAttributes()) {
            try {
                Object value = server.getAttribute(objectName, attribute.getName());
                collectAttribute(metricPrefix, alias, objectName, attribute, value, timestampSeconds, metrics);
            } catch (Exception ignored) {
                // Missing vendor-specific attributes are normal across JVMs and OSes.
            }
        }
    }

    private void collectAttribute(String metricPrefix, String alias, ObjectName objectName, JmxAttributeQuery attribute,
            Object value, long timestampSeconds, List<GraphiteMetric> metrics) {
        if (value instanceof CompositeData) {
            collectComposite(metricPrefix, alias, objectName, attribute, (CompositeData) value, timestampSeconds,
                    metrics);
        } else if (value instanceof TabularData) {
            collectTabular(metricPrefix, alias, objectName, attribute, (TabularData) value, timestampSeconds, metrics);
        } else {
            addMetric(metricPrefix, alias, objectName, attribute, null, value, timestampSeconds, metrics);
        }
    }

    private void collectComposite(String metricPrefix, String alias, ObjectName objectName, JmxAttributeQuery attribute,
            CompositeData value, long timestampSeconds, List<GraphiteMetric> metrics) {
        if (attribute.getKeys().isEmpty()) {
            for (String key : value.getCompositeType().keySet()) {
                addMetric(metricPrefix, alias, objectName, attribute, key, value.get(key), timestampSeconds, metrics);
            }
            return;
        }

        for (String key : attribute.getKeys()) {
            if (value.containsKey(key)) {
                addMetric(metricPrefix, alias, objectName, attribute, key, value.get(key), timestampSeconds, metrics);
            }
        }
    }

    private void collectTabular(String metricPrefix, String alias, ObjectName objectName, JmxAttributeQuery attribute,
            TabularData value, long timestampSeconds, List<GraphiteMetric> metrics) {
        for (Object row : value.values()) {
            if (row instanceof CompositeData) {
                collectComposite(metricPrefix, alias, objectName, attribute, (CompositeData) row, timestampSeconds,
                        metrics);
            }
        }
    }

    private void addMetric(String metricPrefix, String alias, ObjectName objectName, JmxAttributeQuery attribute,
            String key, Object value, long timestampSeconds, List<GraphiteMetric> metrics) {
        String number = number(value);
        if (number == null) {
            return;
        }
        String path = metricPath(metricPrefix, alias, objectName, attribute, key);
        metrics.add(new GraphiteMetric(path, number, timestampSeconds));
    }

    private String metricPath(String metricPrefix, String alias, ObjectName objectName, JmxAttributeQuery attribute,
            String key) {
        if (!EmbeddedJmxTransPublisher.isBlank(attribute.getResultAlias())) {
            String attributeAlias = alias(attribute.getResultAlias(), objectName).replace("%attribute%",
                    attribute.getName());
            return key == null ? GraphiteMetric.path(metricPrefix, attributeAlias)
                    : GraphiteMetric.path(metricPrefix, attributeAlias, key);
        }
        return key == null ? GraphiteMetric.path(metricPrefix, alias, attribute.getName())
                : GraphiteMetric.path(metricPrefix, alias, attribute.getName(), key);
    }

    private String alias(String resultAlias, ObjectName objectName) {
        String alias = resultAlias;
        for (String key : objectName.getKeyPropertyList().keySet()) {
            alias = alias.replace("%" + key + "%", objectName.getKeyProperty(key));
        }
        return alias;
    }

    private String number(Object value) {
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof Boolean) {
            return Boolean.TRUE.equals(value) ? "1" : "0";
        }
        if (value instanceof String) {
            try {
                Double.parseDouble((String) value);
                return (String) value;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
