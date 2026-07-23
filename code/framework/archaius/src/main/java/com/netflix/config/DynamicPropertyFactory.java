package com.netflix.config;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DynamicPropertyFactory {

    private static final DynamicPropertyFactory INSTANCE = new DynamicPropertyFactory();
    private static final Map<String, CopyOnWriteArrayList<Runnable>> CALLBACKS =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Runnable>>();
    private static volatile AbstractConfiguration backingConfigurationSource = ConfigurationManager.getConfigInstance();

    private DynamicPropertyFactory() {
    }

    public static DynamicPropertyFactory getInstance() {
        return INSTANCE;
    }

    public static void initWithConfigurationSource(AbstractConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration is required");
        }
        backingConfigurationSource = configuration;
        ConfigurationManager.setConfigInstance(configuration);
    }

    public static Object getBackingConfigurationSource() {
        return backingConfigurationSource;
    }

    static void addCallback(String key, Runnable callback) {
        if (callback == null) {
            return;
        }
        CALLBACKS.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<Runnable>()).add(callback);
    }

    public static void firePropertyChanged(String key) {
        List<Runnable> callbacks = CALLBACKS.get(key);
        if (callbacks == null) {
            return;
        }
        for (Runnable callback : callbacks) {
            callback.run();
        }
    }

    String getStringValue(String key, String defaultValue) {
        Configuration configuration = backingConfigurationSource;
        String value = configuration == null ? null : configuration.getString(key);
        return value == null ? defaultValue : value;
    }

    public DynamicLongProperty getLongProperty(String key, long defaultValue) {
        return new DynamicLongProperty(key, defaultValue);
    }

    public DynamicIntProperty getIntProperty(String key, int defaultValue) {
        return new DynamicIntProperty(key, defaultValue);
    }

    public DynamicBooleanProperty getBooleanProperty(String key, boolean defaultValue) {
        return new DynamicBooleanProperty(key, defaultValue);
    }

    public DynamicDoubleProperty getDoubleProperty(String key, double defaultValue) {
        return new DynamicDoubleProperty(key, defaultValue);
    }

    public DynamicFloatProperty getFloatProperty(String key, float defaultValue) {
        return new DynamicFloatProperty(key, defaultValue);
    }

    public DynamicStringProperty getStringProperty(String key, String defaultValue) {
        return new DynamicStringProperty(key, defaultValue);
    }

}
