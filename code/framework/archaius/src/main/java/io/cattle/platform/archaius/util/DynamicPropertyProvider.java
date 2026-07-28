package io.cattle.platform.archaius.util;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.Configuration;

public interface DynamicPropertyProvider {

    ConfigListProperty<String> getStringListProperty(String key, String defaultValue);

    ConfigMapProperty<String, String> getStringMapProperty(String key, String defaultValue);

    void initialize(AbstractConfiguration configuration);

    Configuration getConfiguration();

    ConfigProperty<Long> getLongProperty(String key, long defaultValue);

    ConfigProperty<Integer> getIntProperty(String key, int defaultValue);

    ConfigProperty<Boolean> getBooleanProperty(String key, boolean defaultValue);

    ConfigProperty<Double> getDoubleProperty(String key, double defaultValue);

    ConfigProperty<Float> getFloatProperty(String key, float defaultValue);

    ConfigProperty<String> getStringProperty(String key, String defaultValue);

}
