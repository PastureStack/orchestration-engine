package com.netflix.config;

public class DynamicLongProperty extends AbstractDynamicProperty<Long> {

    public DynamicLongProperty(String key, long defaultValue) {
        super(key, Long.valueOf(defaultValue));
    }

    public long get() {
        String value = getStringValue(String.valueOf(defaultValue));
        try {
            return Long.parseLong(value);
        } catch (RuntimeException e) {
            return defaultValue.longValue();
        }
    }

}
