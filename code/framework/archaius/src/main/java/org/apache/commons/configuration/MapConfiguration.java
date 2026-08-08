package org.apache.commons.configuration;

import java.util.Map;
import java.util.Properties;

public class MapConfiguration extends AbstractConfiguration {

    public MapConfiguration(Map<?, ?> map) {
        putAll(map);
    }

    public MapConfiguration(Properties props) {
        putAll(props);
    }

}
