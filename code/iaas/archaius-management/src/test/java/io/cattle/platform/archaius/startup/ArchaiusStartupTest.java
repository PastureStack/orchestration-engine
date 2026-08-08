package io.cattle.platform.archaius.startup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.polling.RefreshableFixedDelayPollingScheduler;
import io.cattle.platform.archaius.sources.LazyJDBCSource;
import io.cattle.platform.archaius.sources.NamedDynamicConfiguration;
import io.cattle.platform.archaius.sources.NamedMapConfiguration;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigurationBootstrap;
import io.cattle.platform.archaius.util.ConfigurationStack;
import io.cattle.platform.extension.impl.ExtensionManagerImpl;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.commons.configuration.AbstractConfiguration;
import org.junit.Test;

public class ArchaiusStartupTest {

    @Test
    public void loadPreservesConfigOrderAndDisablesDelimiterParsing() {
        NamedMapConfiguration first = namedConfig("first", "shared", "fromFirst");
        NamedMapConfiguration second = namedConfig("second", "shared", "fromSecond");

        ArchaiusStartup startup = new ArchaiusStartup();
        startup.extensionManager = new FixedExtensionManager(first, second);
        startup.baseConfig = ArchaiusUtil.newConfigurationStackAdapter();

        startup.load(false);

        assertEquals("fromFirst", startup.baseConfig.getString("shared"));
        assertTrue(first.isDelimiterParsingDisabled());
        assertTrue(second.isDelimiterParsingDisabled());
    }

    @Test
    public void refreshAttachesJdbcSourceToLazyDatabaseConfig() {
        LazyJDBCSource lazySource = new LazyJDBCSource();
        NamedDynamicConfiguration databaseConfig = new NamedDynamicConfiguration(lazySource, new RefreshableFixedDelayPollingScheduler());
        databaseConfig.setSourceName("Database");

        ArchaiusStartup startup = new ArchaiusStartup();
        startup.extensionManager = new FixedExtensionManager(databaseConfig);
        startup.baseConfig = ArchaiusUtil.newConfigurationStackAdapter();
        startup.schedulers = Collections.emptyList();
        startup.dataSourceFactory = name -> {
            assertEquals("config", name);
            return unsupportedDataSource();
        };

        startup.load(true);

        assertNotNull(lazySource.getSource());
    }

    @Test
    public void refreshDoesNotCreateDataSourceWithoutLazyDatabaseConfig() {
        NamedMapConfiguration config = namedConfig("static", "key", "value");

        ArchaiusStartup startup = new ArchaiusStartup();
        startup.extensionManager = new FixedExtensionManager(config);
        startup.baseConfig = ArchaiusUtil.newConfigurationStackAdapter();
        startup.schedulers = Collections.emptyList();
        startup.dataSourceFactory = name -> {
            throw new AssertionError("DataSource should only be created for lazy JDBC sources");
        };

        startup.load(true);

        assertEquals("value", startup.baseConfig.getString("key"));
    }

    @Test
    public void refreshSkipsLazyDatabaseConfigForEmbeddedH2Mode() {
        LazyJDBCSource lazySource = new LazyJDBCSource();
        NamedDynamicConfiguration databaseConfig = new NamedDynamicConfiguration(lazySource, new RefreshableFixedDelayPollingScheduler());
        databaseConfig.setSourceName("Database");

        ArchaiusStartup startup = new ArchaiusStartup();
        startup.extensionManager = new FixedExtensionManager(namedConfig("env", "db.cattle.database", "h2"), databaseConfig);
        startup.baseConfig = ArchaiusUtil.newConfigurationStackAdapter();
        startup.schedulers = Collections.emptyList();
        startup.load(false);
        startup.dataSourceFactory = name -> {
            throw new AssertionError("Embedded DB mode should not attach the lazy settings datasource");
        };

        startup.load(true);

        assertEquals("h2", startup.baseConfig.getString("db.cattle.database"));
    }

    @Test
    public void refreshUsesSchedulerRegistry() {
        NamedMapConfiguration config = namedConfig("static", "key", "value");
        CountingScheduler scheduler = new CountingScheduler();

        ArchaiusStartup startup = new ArchaiusStartup();
        startup.extensionManager = new FixedExtensionManager(config);
        startup.baseConfig = ArchaiusUtil.newConfigurationStackAdapter();
        startup.schedulers = Arrays.asList(scheduler);

        startup.load(true);

        assertEquals(1, scheduler.refreshCount);
    }

    @Test
    public void initUsesConfigurationBootstrap() {
        Properties defaults = new Properties();
        defaults.setProperty("default.key", "default.value");
        ArchaiusStartup.setGlobalDefaults(defaults);
        RecordingBootstrap bootstrap = new RecordingBootstrap();

        ArchaiusStartup startup = new ArchaiusStartup();
        startup.configurationBootstrap = bootstrap;

        startup.init();

        assertEquals(1, bootstrap.newStackCalls);
        assertEquals(1, bootstrap.initializeCalls);
        assertEquals("default.value", startup.baseConfig.getString("default.key"));
    }

    private NamedMapConfiguration namedConfig(String sourceName, String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);

        NamedMapConfiguration config = new NamedMapConfiguration(props);
        config.setSourceName(sourceName);
        return config;
    }

    private DataSource unsupportedDataSource() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] { DataSource.class },
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "UnsupportedDataSource";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static class FixedExtensionManager extends ExtensionManagerImpl {

        private final List<AbstractConfiguration> configs;

        FixedExtensionManager(AbstractConfiguration... configs) {
            this.configs = Arrays.asList(configs);
        }

        @Override
        public <T> List<T> getExtensionList(String key, Class<T> type) {
            assertEquals(ArchaiusStartup.CONFIG_KEY, key);
            assertEquals(AbstractConfiguration.class, type);
            List<T> result = new ArrayList<T>(configs.size());
            for (AbstractConfiguration config : configs) {
                result.add(type.cast(config));
            }
            return result;
        }

    }

    private static class CountingScheduler extends RefreshableFixedDelayPollingScheduler {

        int refreshCount;

        @Override
        public void refresh() {
            refreshCount++;
        }

    }

    private static class RecordingBootstrap implements ConfigurationBootstrap {

        int newStackCalls;
        int initializeCalls;

        @Override
        public ConfigurationStack newStack() {
            newStackCalls++;
            return ArchaiusUtil.newConfigurationStackAdapter();
        }

        @Override
        public void initialize(ConfigurationStack stack) {
            initializeCalls++;
        }

    }

}
