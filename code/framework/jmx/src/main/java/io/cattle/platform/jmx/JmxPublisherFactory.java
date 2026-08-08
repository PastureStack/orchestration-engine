package io.cattle.platform.jmx;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigMapProperty;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.server.context.ServerContext;

import java.net.URL;
import java.util.List;

import jakarta.annotation.PostConstruct;

public class JmxPublisherFactory {

    private static final ConfigProperty<String> GRAPHITE_HOST = ArchaiusUtil.getStringProperty("graphite.host");
    private static final ConfigProperty<Integer> JMX_TRANS_EXPORT_INTERVAL = ArchaiusUtil.getIntProperty("jmx.trans.export.interval.seconds");
    private static final ConfigProperty<Integer> JMX_TRANS_QUERY_INTERVAL = ArchaiusUtil.getIntProperty("jmx.trans.query.interval.seconds");

    private static final ConfigProperty<Integer> GRAPHITE_PORT = ArchaiusUtil.getIntProperty("graphite.port");
    private static final ConfigMapProperty<String, String> GRAPHITE_OPTIONS = ArchaiusUtil.getStringMapProperty("graphite.options");

    List<URL> resources;
    JmxPublisher publisher = new EmbeddedJmxTransPublisher();

    @PostConstruct
    public void init() throws Exception {
        publisher.start(new JmxPublisherConfig(resources, JMX_TRANS_EXPORT_INTERVAL.get(),
                JMX_TRANS_QUERY_INTERVAL.get(), GRAPHITE_HOST.get(), GRAPHITE_PORT.get(), GRAPHITE_OPTIONS.get(),
                ServerContext.SERVER_ID.get()));
    }

    public List<URL> getResources() {
        return resources;
    }

    public void setResources(List<URL> resources) {
        this.resources = resources;
    }

    public void setPublisher(JmxPublisher publisher) {
        if (publisher == null) {
            throw new IllegalArgumentException("publisher is required");
        }
        this.publisher = publisher;
    }

}
