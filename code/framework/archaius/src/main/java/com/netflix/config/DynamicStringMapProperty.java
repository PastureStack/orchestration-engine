package com.netflix.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DynamicStringMapProperty extends AbstractDynamicProperty<String> {

    public DynamicStringMapProperty(String key, String defaultValue) {
        super(key, defaultValue);
    }

    public Map<String, String> getMap() {
        String value = getStringValue(defaultValue);
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<String, String>();
        String[] entries = value.split(",");
        for (String entry : entries) {
            String item = entry.trim();
            if (item.isEmpty()) {
                continue;
            }
            int index = item.indexOf('=');
            if (index < 0) {
                result.put(item, "");
            } else {
                result.put(item.substring(0, index).trim(), item.substring(index + 1).trim());
            }
        }

        return result;
    }

}
