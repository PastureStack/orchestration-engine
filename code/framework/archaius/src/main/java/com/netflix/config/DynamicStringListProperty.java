package com.netflix.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DynamicStringListProperty extends AbstractDynamicProperty<String> {

    public DynamicStringListProperty(String key, String defaultValue) {
        super(key, defaultValue);
    }

    public List<String> get() {
        String value = getStringValue(defaultValue);
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts = value.split(",");
        List<String> result = new ArrayList<String>(parts.length);
        for (String part : parts) {
            String item = part.trim();
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

}
