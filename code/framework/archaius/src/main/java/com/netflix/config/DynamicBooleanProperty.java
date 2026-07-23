package com.netflix.config;

public class DynamicBooleanProperty extends AbstractDynamicProperty<Boolean> {

    public DynamicBooleanProperty(String key, boolean defaultValue) {
        super(key, Boolean.valueOf(defaultValue));
    }

    public boolean get() {
        return Boolean.parseBoolean(getStringValue(String.valueOf(defaultValue)));
    }

}
