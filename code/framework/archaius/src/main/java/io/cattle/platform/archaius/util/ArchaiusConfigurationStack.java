package io.cattle.platform.archaius.util;

import io.cattle.platform.archaius.sources.RegisteredConfigSource;

import org.apache.commons.configuration.AbstractConfiguration;

import com.netflix.config.ConcurrentCompositeConfiguration;

final class ArchaiusConfigurationStack implements ConfigurationStack {

    private final AbstractConfiguration configuration;

    ArchaiusConfigurationStack(AbstractConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration is required");
        }

        this.configuration = configuration;
    }

    @Override
    public void add(AbstractConfiguration source) {
        if (configuration instanceof ConcurrentCompositeConfiguration) {
            ((ConcurrentCompositeConfiguration) configuration).addConfiguration(source);
            return;
        }

        throw new IllegalArgumentException("Unsupported configuration stack: " + configuration);
    }

    @Override
    public void add(RegisteredConfigSource source) {
        add(source.asConfiguration());
    }

    @Override
    public void clear() {
        configuration.clear();
    }

    @Override
    public String getString(String key) {
        return configuration.getString(key);
    }

    @Override
    public AbstractConfiguration asConfiguration() {
        return configuration;
    }

}
