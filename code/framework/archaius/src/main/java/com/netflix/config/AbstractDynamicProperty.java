package com.netflix.config;

abstract class AbstractDynamicProperty<T> {

    protected final String key;
    protected final T defaultValue;

    AbstractDynamicProperty(String key, T defaultValue) {
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public void addCallback(Runnable callback) {
        DynamicPropertyFactory.addCallback(key, callback);
    }

    protected String getStringValue(String fallback) {
        return DynamicPropertyFactory.getInstance().getStringValue(key, fallback);
    }

}
