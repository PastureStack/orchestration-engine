package io.cattle.platform.archaius.sources;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.configuration.MapConfiguration;

public abstract class AbstractTransformedEnvironmentProperties extends MapConfiguration implements NamedConfigurationSource {

    public AbstractTransformedEnvironmentProperties(String contains, String prefix) {
        super(getValues(contains, prefix));
    }

    protected static Map<String, Object> getValues(String contains, String prefix) {
        Map<String, Object> values = new HashMap<String, Object>();

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (!isBlank(contains) && !key.contains(contains)) {
                continue;
            }

            if (key.startsWith(prefix)) {
                key = key.substring(prefix.length());
            }

            key = key.replace('_', '.').toLowerCase();

            if (!isBlank(entry.getValue())) {
                values.put(key, entry.getValue());
            }
        }

        return values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
