package io.cattle.platform.archaius.sources;

import org.apache.commons.configuration.AbstractConfiguration;

final class ArchaiusRegisteredConfigSource implements RegisteredConfigSource {

    private final AbstractConfiguration configuration;

    ArchaiusRegisteredConfigSource(AbstractConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration is required");
        }

        this.configuration = configuration;
    }

    @Override
    public AbstractConfiguration asConfiguration() {
        return configuration;
    }

}
