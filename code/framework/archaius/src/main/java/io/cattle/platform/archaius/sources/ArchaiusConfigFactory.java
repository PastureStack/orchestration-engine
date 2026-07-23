package io.cattle.platform.archaius.sources;

import io.cattle.platform.archaius.polling.RefreshableFixedDelayPollingScheduler;

import java.util.Map;
import java.util.Properties;

import org.apache.commons.configuration.AbstractConfiguration;

/**
 * Centralizes construction of the current Archaius-backed configuration sources.
 */
public final class ArchaiusConfigFactory {

    private ArchaiusConfigFactory() {
    }

    public static RegisteredConfigSource defaultEnvironment() {
        return source(new DefaultTransformedEnvironmentProperties());
    }

    public static RegisteredConfigSource environment() {
        return source(new TransformedEnvironmentProperties());
    }

    public static RegisteredConfigSource systemProperties() {
        return source(new NamedSystemConfiguration());
    }

    public static RegisteredConfigSource optionalProperties(String name) {
        return source(OptionalPropertiesConfigurationFactory.getConfiguration(name));
    }

    public static RegisteredConfigSource map(Map<String, Object> properties) {
        return source(new NamedMapConfiguration(properties));
    }

    public static RegisteredConfigSource properties(Properties properties) {
        return source(new NamedMapConfiguration(properties));
    }

    public static RegisteredConfigSource database(RefreshableFixedDelayPollingScheduler scheduler, String sourceName) {
        NamedDynamicConfiguration config = new NamedDynamicConfiguration(new LazyJDBCSource(), scheduler);
        config.setSourceName(sourceName);
        return source(config);
    }

    public static RegisteredConfigSource defaults(Properties properties, String sourceName) {
        NamedMapConfiguration config = new NamedMapConfiguration(properties);
        config.setSourceName(sourceName);
        return source(config);
    }

    public static RegisteredConfigSource source(AbstractConfiguration configuration) {
        return new ArchaiusRegisteredConfigSource(configuration);
    }

}
