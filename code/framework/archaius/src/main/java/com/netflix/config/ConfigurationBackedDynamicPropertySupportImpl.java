package com.netflix.config;

import org.apache.commons.configuration.Configuration;

public class ConfigurationBackedDynamicPropertySupportImpl {

    private final Configuration configuration;

    public ConfigurationBackedDynamicPropertySupportImpl(Configuration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration is required");
        }
        this.configuration = configuration;
    }

    public String getString(String key) {
        return configuration.getString(key);
    }

}
