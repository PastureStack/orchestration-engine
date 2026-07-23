package com.netflix.config;

public class DynamicFloatProperty extends AbstractDynamicProperty<Float> {

    public DynamicFloatProperty(String key, float defaultValue) {
        super(key, Float.valueOf(defaultValue));
    }

    public float get() {
        String value = getStringValue(String.valueOf(defaultValue));
        try {
            return Float.parseFloat(value);
        } catch (RuntimeException e) {
            return defaultValue.floatValue();
        }
    }

}
