package io.cattle.platform.jmx;

import static org.junit.Assert.assertEquals;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class JmxPublisherFactoryTest {

    @After
    public void clearProperties() {
        clear("graphite.host");
        clear("graphite.port");
        clear("graphite.options");
        clear("jmx.trans.export.interval.seconds");
        clear("jmx.trans.query.interval.seconds");
        clear("cattle.server.id");
    }

    @Test
    public void factoryDelegatesDynamicSettingsToPublisher() throws Exception {
        ConfigurationManager.getConfigInstance().setProperty("graphite.host", "graphite.example");
        ConfigurationManager.getConfigInstance().setProperty("graphite.port", "2203");
        ConfigurationManager.getConfigInstance().setProperty("graphite.options", "protocol=tcp,rootPrefix=rc16");
        ConfigurationManager.getConfigInstance().setProperty("jmx.trans.export.interval.seconds", "60");
        ConfigurationManager.getConfigInstance().setProperty("jmx.trans.query.interval.seconds", "7");
        ConfigurationManager.getConfigInstance().setProperty("cattle.server.id", "server-a");

        RecordingPublisher publisher = new RecordingPublisher();
        JmxPublisherFactory factory = new JmxPublisherFactory();
        factory.setResources(Arrays.asList(URI.create("file:/tmp/jmx-a.json").toURL()));
        factory.setPublisher(publisher);

        factory.init();

        assertEquals(1, publisher.starts);
        assertEquals(1, publisher.config.getResources().size());
        assertEquals("file:/tmp/jmx-a.json", publisher.config.getResources().get(0).toExternalForm());
        assertEquals(60, publisher.config.getExportIntervalSeconds());
        assertEquals(7, publisher.config.getQueryIntervalSeconds());
        assertEquals("graphite.example", publisher.config.getGraphiteHost());
        assertEquals(Integer.valueOf(2203), publisher.config.getGraphitePort());
        assertEquals("tcp", publisher.config.getGraphiteOptions().get("protocol"));
        assertEquals("rc16", publisher.config.getGraphiteOptions().get("rootPrefix"));
        assertEquals("server-a", publisher.config.getServerId());
    }

    @Test
    public void configDefensivelyCopiesMutableInputs() throws Exception {
        List<URL> resources = new ArrayList<URL>();
        resources.add(URI.create("file:/tmp/jmx-before.json").toURL());
        Map<String, String> options = new HashMap<String, String>();
        options.put("protocol", "tcp");

        JmxPublisherConfig config = new JmxPublisherConfig(resources, 30, 5, "graphite.example", 2003, options,
                "server-a");
        resources.clear();
        options.clear();

        assertEquals(1, config.getResources().size());
        assertEquals("tcp", config.getGraphiteOptions().get("protocol"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void configResourcesAreImmutable() throws Exception {
        JmxPublisherConfig config = new JmxPublisherConfig(Arrays.asList(URI.create("file:/tmp/jmx-a.json").toURL()),
                30, 5, "", 2003, null, null);

        config.getResources().clear();
    }

    @Test(expected = IllegalArgumentException.class)
    public void factoryRejectsNullPublisher() {
        new JmxPublisherFactory().setPublisher(null);
    }

    private void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static class RecordingPublisher implements JmxPublisher {
        int starts;
        JmxPublisherConfig config;

        @Override
        public void start(JmxPublisherConfig config) {
            this.starts++;
            this.config = config;
        }
    }
}
