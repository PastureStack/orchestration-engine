package io.cattle.platform.jmx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.junit.After;
import org.junit.Test;

public class JmxTransCompatibilityPublisherTest {

    private static final ObjectName TEST_OBJECT_NAME = objectName("io.cattle.test:type=Example,name=demo");

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @After
    public void unregisterTestMBeans() throws Exception {
        unregister(TEST_OBJECT_NAME);
        unregister(new ObjectName(EmbeddedJmxTransPublisher.DEFAULT_INTERNAL_MBEAN));
    }

    @Test
    public void parserReadsLegacyJmxtransJsonShape() throws Exception {
        File file = File.createTempFile("rc16-jmx", ".json");
        try {
            FileWriter writer = new FileWriter(file);
            try {
                writer.write("{\"queries\":[{\"objectName\":\"java.lang:type=Memory\","
                        + "\"resultAlias\":\"jvm.memory\","
                        + "\"attributes\":[\"ObjectPendingFinalizationCount\","
                        + "{\"name\":\"HeapMemoryUsage\",\"resultAlias\":\"jvm.heap\","
                        + "\"keys\":[\"used\",\"committed\"]}]}]}");
            } finally {
                writer.close();
            }

            List<JmxQuery> queries = new JmxTransJsonParser().parse(Collections.singletonList(file.toURI().toURL()));

            assertEquals(1, queries.size());
            assertEquals("java.lang:type=Memory", queries.get(0).getObjectName());
            assertEquals("jvm.memory", queries.get(0).getResultAlias());
            assertEquals(2, queries.get(0).getAttributes().size());
            assertEquals("ObjectPendingFinalizationCount", queries.get(0).getAttributes().get(0).getName());
            assertEquals("jvm.heap", queries.get(0).getAttributes().get(1).getResultAlias());
            assertEquals(Arrays.asList("used", "committed"), queries.get(0).getAttributes().get(1).getKeys());
        } finally {
            file.delete();
        }
    }

    @Test
    public void collectorPreservesAliasPlaceholdersAndCompositeKeys() throws Exception {
        server.registerMBean(new StandardMBean(new Example(), ExampleMBean.class), TEST_OBJECT_NAME);

        List<JmxAttributeQuery> attributes = Arrays.asList(new JmxAttributeQuery("Count", null),
                new JmxAttributeQuery("Usage", Arrays.asList("used", "committed"), "unit.%name%.memory"));
        JmxQuery query = new JmxQuery(TEST_OBJECT_NAME.toString(), "unit.%name%", attributes);

        List<GraphiteMetric> metrics = new JmxMetricCollector(server, new JmxPublisherStats())
                .collect(Collections.singletonList(query), "servers.server-a");

        assertEquals(3, metrics.size());
        assertContainsLine(metrics, "servers.server-a.unit.demo.Count 7 ");
        assertContainsLine(metrics, "servers.server-a.unit.demo.memory.used 11 ");
        assertContainsLine(metrics, "servers.server-a.unit.demo.memory.committed 22 ");
    }

    @Test
    public void publisherRegistersCompatibilityStatsMBeanWhenGraphiteIsDisabled() throws Exception {
        ObjectName objectName = new ObjectName(EmbeddedJmxTransPublisher.DEFAULT_INTERNAL_MBEAN);

        new EmbeddedJmxTransPublisher().start(new JmxPublisherConfig(Collections.<java.net.URL>emptyList(), 30, 5, "",
                2003, null, "server-a"));

        assertTrue(server.isRegistered(objectName));
        assertEquals(Long.valueOf(0L), server.getAttribute(objectName, "CollectionCount"));
    }

    private void assertContainsLine(List<GraphiteMetric> metrics, String expectedPrefix) {
        for (GraphiteMetric metric : metrics) {
            if (metric.toLine().startsWith(expectedPrefix)) {
                return;
            }
        }
        throw new AssertionError("Missing metric line prefix: " + expectedPrefix);
    }

    private void unregister(ObjectName objectName) throws Exception {
        if (server.isRegistered(objectName)) {
            server.unregisterMBean(objectName);
        }
    }

    private static ObjectName objectName(String value) {
        try {
            return new ObjectName(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public interface ExampleMBean {
        int getCount();

        CompositeData getUsage() throws Exception;
    }

    public static class Example implements ExampleMBean {

        @Override
        public int getCount() {
            return 7;
        }

        @Override
        public CompositeData getUsage() throws Exception {
            String[] names = new String[] { "used", "committed" };
            OpenType<?>[] types = new OpenType<?>[] { SimpleType.INTEGER, SimpleType.INTEGER };
            CompositeType type = new CompositeType("usage", "usage", names, names, types);
            return new CompositeDataSupport(type, names, new Object[] { Integer.valueOf(11), Integer.valueOf(22) });
        }
    }
}
