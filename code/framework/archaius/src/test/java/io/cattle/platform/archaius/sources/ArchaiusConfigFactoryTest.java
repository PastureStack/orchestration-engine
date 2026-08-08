package io.cattle.platform.archaius.sources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.polling.RefreshableFixedDelayPollingScheduler;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.configuration.AbstractConfiguration;
import org.junit.Test;

public class ArchaiusConfigFactoryTest {

    @Test
    public void createsNamedDatabaseConfigurationWithLazyJdbcSource() {
        RegisteredConfigSource source = ArchaiusConfigFactory.database(new RefreshableFixedDelayPollingScheduler(),
                "Database");
        AbstractConfiguration config = source.asConfiguration();

        assertTrue(config instanceof NamedDynamicConfiguration);
        NamedDynamicConfiguration named = (NamedDynamicConfiguration) config;
        assertEquals("Database", named.getSourceName());
        assertTrue(named.getSource() instanceof LazyJDBCSource);
    }

    @Test
    public void createsNamedDefaultsConfigurationWithoutChangingValues() {
        Properties properties = new Properties();
        properties.setProperty("sample.key", "sample.value");

        AbstractConfiguration config = ArchaiusConfigFactory.defaults(properties, "Code Packaged Defaults")
                .asConfiguration();

        assertTrue(config instanceof NamedMapConfiguration);
        assertEquals("Code Packaged Defaults", ((NamedMapConfiguration) config).getSourceName());
        assertEquals("sample.value", config.getString("sample.key"));
    }

    @Test
    public void createsUnnamedMapAndPropertiesConfigurationsWithoutChangingValues() {
        Properties properties = new Properties();
        properties.setProperty("properties.key", "properties.value");

        assertEquals("map.value", ArchaiusConfigFactory.map(mapOf("map.key", "map.value"))
                .asConfiguration().getString("map.key"));
        assertEquals("properties.value", ArchaiusConfigFactory.properties(properties)
                .asConfiguration().getString("properties.key"));
    }

    @Test
    public void keepsEnvironmentAndOptionalPropertySourceTypesBehindFactory() {
        assertTrue(ArchaiusConfigFactory.defaultEnvironment()
                .asConfiguration() instanceof DefaultTransformedEnvironmentProperties);
        assertTrue(ArchaiusConfigFactory.environment().asConfiguration() instanceof TransformedEnvironmentProperties);
        assertTrue(ArchaiusConfigFactory.systemProperties().asConfiguration() instanceof NamedSystemConfiguration);
        assertTrue(ArchaiusConfigFactory.optionalProperties("missing-rc16-test.properties")
                .asConfiguration() instanceof AbstractConfiguration);
    }

    private static Map<String, Object> mapOf(String key, String value) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put(key, value);
        return result;
    }

}
