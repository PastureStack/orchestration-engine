package com.netflix.config;

public class DynamicStringProperty extends AbstractDynamicProperty<String> {

    public DynamicStringProperty(String key, String defaultValue) {
        super(key, defaultValue);
    }

    public String get() {
        return getStringValue(defaultValue);
    }

}
