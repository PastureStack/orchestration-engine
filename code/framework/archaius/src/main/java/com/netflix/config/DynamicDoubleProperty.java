package com.netflix.config;

public class DynamicDoubleProperty extends AbstractDynamicProperty<Double> {

    public DynamicDoubleProperty(String key, double defaultValue) {
        super(key, Double.valueOf(defaultValue));
    }

    public double get() {
        String value = getStringValue(String.valueOf(defaultValue));
        try {
            return Double.parseDouble(value);
        } catch (RuntimeException e) {
            return defaultValue.doubleValue();
        }
    }

}
