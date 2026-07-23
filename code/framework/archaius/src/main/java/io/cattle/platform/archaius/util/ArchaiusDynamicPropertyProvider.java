package io.cattle.platform.archaius.util;

import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.Configuration;

import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicDoubleProperty;
import com.netflix.config.DynamicFloatProperty;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicLongProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicStringListProperty;
import com.netflix.config.DynamicStringProperty;
import com.netflix.config.DynamicStringMapProperty;

public class ArchaiusDynamicPropertyProvider implements DynamicPropertyProvider, LegacyDynamicPropertyProvider {

    @Override
    public void initialize(AbstractConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration is required");
        }

        DynamicPropertyFactory.initWithConfigurationSource(configuration);
    }

    @Override
    public DynamicLongProperty getLong(String key, long defaultValue) {
        return DynamicPropertyFactory.getInstance().getLongProperty(key, defaultValue);
    }

    @Override
    public DynamicIntProperty getInt(String key, int defaultValue) {
        return DynamicPropertyFactory.getInstance().getIntProperty(key, defaultValue);
    }

    @Override
    public DynamicBooleanProperty getBoolean(String key, boolean defaultValue) {
        return DynamicPropertyFactory.getInstance().getBooleanProperty(key, defaultValue);
    }

    @Override
    public DynamicDoubleProperty getDouble(String key, double defaultValue) {
        return DynamicPropertyFactory.getInstance().getDoubleProperty(key, defaultValue);
    }

    @Override
    public DynamicFloatProperty getFloat(String key, float defaultValue) {
        return DynamicPropertyFactory.getInstance().getFloatProperty(key, defaultValue);
    }

    @Override
    public DynamicStringProperty getString(String key, String defaultValue) {
        return DynamicPropertyFactory.getInstance().getStringProperty(key, defaultValue);
    }

    @Override
    public DynamicStringListProperty getList(String key, String defaultValue) {
        return new DynamicStringListProperty(key, defaultValue);
    }

    @Override
    public ConfigListProperty<String> getStringListProperty(String key, String defaultValue) {
        final DynamicStringListProperty property = getList(key, defaultValue);
        return new ConfigListProperty<String>() {
            @Override
            public List<String> get() {
                return property.get();
            }

            @Override
            public void addCallback(Runnable callback) {
                property.addCallback(callback);
            }
        };
    }

    @Override
    public ConfigMapProperty<String, String> getStringMapProperty(String key, String defaultValue) {
        final DynamicStringMapProperty property = new DynamicStringMapProperty(key, defaultValue);
        return new ConfigMapProperty<String, String>() {
            @Override
            public Map<String, String> get() {
                return property.getMap();
            }

            @Override
            public void addCallback(Runnable callback) {
                property.addCallback(callback);
            }
        };
    }

    @Override
    public ConfigProperty<Long> getLongProperty(String key, long defaultValue) {
        final DynamicLongProperty property = getLong(key, defaultValue);
        return new ConfigProperty<Long>() {
            @Override
            public Long get() {
                return property.get();
            }

            @Override
            public void addCallback(Runnable callback) {
                property.addCallback(callback);
            }
        };
    }

    @Override
    public ConfigProperty<Integer> getIntProperty(String key, int defaultValue) {
        final DynamicIntProperty property = getInt(key, defaultValue);
        return new ConfigProperty<Integer>() {
            @Override
            public Integer get() {
                return property.get();
            }

            @Override
            public void addCallback(Runnable callback) {
                property.addCallback(callback);
            }
        };
    }

    @Override
    public ConfigProperty<Boolean> getBooleanProperty(String key, boolean defaultValue) {
        final DynamicBooleanProperty property = getBoolean(key, defaultValue);
        return new ConfigProperty<Boolean>() {
            @Override
            public Boolean get() {
                return property.get();
            }

            @Override
            public void addCallback(Runnable callback) {
                property.addCallback(callback);
            }
        };
    }

    @Override
    public ConfigProperty<Double> getDoubleProperty(String key, double defaultValue) {
        final DynamicDoubleProperty property = getDouble(key, defaultValue);
        return new ConfigProperty<Double>() {
            @Override
            public Double get() {
                return property.get();
            }

            @Override
            public void addCallback(Runnable callback) {
                property.addCallback(callback);
            }
        };
    }

    @Override
    public ConfigProperty<Float> getFloatProperty(String key, float defaultValue) {
        final DynamicFloatProperty property = getFloat(key, defaultValue);
        return new ConfigProperty<Float>() {
            @Override
            public Float get() {
                return property.get();
            }

            @Override
            public void addCallback(Runnable callback) {
                property.addCallback(callback);
            }
        };
    }

    @Override
    public ConfigProperty<String> getStringProperty(String key, String defaultValue) {
        final DynamicStringProperty property = getString(key, defaultValue);
        return new ConfigProperty<String>() {
            @Override
            public String get() {
                return property.get();
            }

            @Override
            public void addCallback(Runnable callback) {
                property.addCallback(callback);
            }
        };
    }

    @Override
    public Configuration getConfiguration() {
        Object source = DynamicPropertyFactory.getBackingConfigurationSource();
        return source instanceof Configuration ? (Configuration) source : null;
    }

}
