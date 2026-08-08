package io.cattle.platform.archaius.util;

import io.cattle.platform.archaius.sources.RegisteredConfigSource;

import org.apache.commons.configuration.AbstractConfiguration;

public interface ConfigurationStack {

    void add(AbstractConfiguration source);

    void add(RegisteredConfigSource source);

    void clear();

    String getString(String key);

    AbstractConfiguration asConfiguration();

}
