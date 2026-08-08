package io.cattle.platform.archaius.startup;

import io.cattle.platform.archaius.sources.ArchaiusConfigurationSourceList;
import io.cattle.platform.archaius.sources.ConfigurationSourceList;
import io.cattle.platform.archaius.sources.RegisteredConfigSource;
import io.cattle.platform.extension.impl.ExtensionManagerImpl;

import org.apache.commons.configuration.AbstractConfiguration;

/**
 * Central registration point for Archaius configuration sources.
 */
public final class ArchaiusConfigRegistration {

    private ArchaiusConfigRegistration() {
    }

    public static <T extends AbstractConfiguration> T addConfig(ExtensionManagerImpl extensionManager, T config, String name) {
        extensionManager.addObject(ArchaiusStartup.CONFIG_KEY, AbstractConfiguration.class, config, name);
        return config;
    }

    public static RegisteredConfigSource addConfig(ExtensionManagerImpl extensionManager, RegisteredConfigSource config,
            String name) {
        extensionManager.addObject(ArchaiusStartup.CONFIG_KEY, AbstractConfiguration.class, config.asConfiguration(),
                name);
        return config;
    }

    public static ConfigurationSourceList getConfigSources(ExtensionManagerImpl extensionManager, String key) {
        return ArchaiusConfigurationSourceList.of(extensionManager.getExtensionList(key, AbstractConfiguration.class));
    }

}
