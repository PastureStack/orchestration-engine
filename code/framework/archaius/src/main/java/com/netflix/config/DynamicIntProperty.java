package com.netflix.config;

public class DynamicIntProperty extends AbstractDynamicProperty<Integer> {

    public DynamicIntProperty(String key, int defaultValue) {
        super(key, Integer.valueOf(defaultValue));
    }

    public int get() {
        String value = getStringValue(String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException e) {
            return defaultValue.intValue();
        }
    }

}
