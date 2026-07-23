package io.cattle.platform.archaius.util;

import io.cattle.platform.archaius.sources.NamedConfigurationSource;

import java.util.Iterator;

import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.Configuration;

import com.netflix.config.ConcurrentCompositeConfiguration;
import com.netflix.config.DynamicConfiguration;

/**
 * Read-only boundary around Archaius/Commons Configuration internals.
 */
public final class ArchaiusConfigurationView implements ConfigurationView {

    private final Configuration configuration;

    private ArchaiusConfigurationView(Configuration configuration) {
        this.configuration = configuration;
    }

    public static ArchaiusConfigurationView getCurrent() {
        return from(ArchaiusUtil.getConfiguration());
    }

    public static ArchaiusConfigurationView from(Configuration configuration) {
        if (configuration == null) {
            return null;
        }

        return new ArchaiusConfigurationView(configuration);
    }

    public Iterator<String> getKeys() {
        return configuration.getKeys();
    }

    public Object getProperty(String name) {
        return configuration.getProperty(name);
    }

    public String getSourceName(String name) {
        return toSourceName(getSource(name));
    }

    private Configuration getSource(String name) {
        if (configuration instanceof ConcurrentCompositeConfiguration) {
            return ((ConcurrentCompositeConfiguration) configuration).getSource(name);
        }

        if (configuration instanceof CompositeConfiguration) {
            return ((CompositeConfiguration) configuration).getSource(name);
        }

        return configuration;
    }

    private String toSourceName(Configuration source) {
        if (source instanceof NamedConfigurationSource) {
            return ((NamedConfigurationSource) source).getSourceName();
        }

        if (source instanceof DynamicConfiguration) {
            return ((DynamicConfiguration) source).getSource().getClass().getName();
        }

        return source == null ? null : source.getClass().getName();
    }

}
