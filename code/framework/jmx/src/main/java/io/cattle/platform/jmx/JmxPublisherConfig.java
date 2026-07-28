package io.cattle.platform.jmx;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JmxPublisherConfig {

    private final List<URL> resources;
    private final int exportIntervalSeconds;
    private final int queryIntervalSeconds;
    private final String graphiteHost;
    private final Integer graphitePort;
    private final Map<String, String> graphiteOptions;
    private final String serverId;

    public JmxPublisherConfig(List<URL> resources, int exportIntervalSeconds, int queryIntervalSeconds,
            String graphiteHost, Integer graphitePort, Map<String, String> graphiteOptions, String serverId) {
        this.resources = resources == null ? Collections.<URL>emptyList()
                : Collections.unmodifiableList(new ArrayList<URL>(resources));
        this.exportIntervalSeconds = exportIntervalSeconds;
        this.queryIntervalSeconds = queryIntervalSeconds;
        this.graphiteHost = graphiteHost;
        this.graphitePort = graphitePort;
        this.graphiteOptions = graphiteOptions == null ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new HashMap<String, String>(graphiteOptions));
        this.serverId = serverId;
    }

    public List<URL> getResources() {
        return resources;
    }

    public int getExportIntervalSeconds() {
        return exportIntervalSeconds;
    }

    public int getQueryIntervalSeconds() {
        return queryIntervalSeconds;
    }

    public String getGraphiteHost() {
        return graphiteHost;
    }

    public Integer getGraphitePort() {
        return graphitePort;
    }

    public Map<String, String> getGraphiteOptions() {
        return graphiteOptions;
    }

    public String getServerId() {
        return serverId;
    }

}
