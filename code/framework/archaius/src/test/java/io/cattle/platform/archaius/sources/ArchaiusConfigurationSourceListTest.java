package io.cattle.platform.archaius.sources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.polling.RefreshableFixedDelayPollingScheduler;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigurationStack;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.MapConfiguration;
import org.junit.Test;

public class ArchaiusConfigurationSourceListTest {

    @Test
    public void disablesDelimiterParsingForEachSource() {
        AbstractConfiguration first = mapConfig("first", "value");
        AbstractConfiguration second = mapConfig("second", "value");
        ConfigurationSourceList sources = ArchaiusConfigurationSourceList.of(Arrays.asList(first, second));

        sources.disableDelimiterParsing();

        assertTrue(first.isDelimiterParsingDisabled());
        assertTrue(second.isDelimiterParsingDisabled());
    }

    @Test
    public void replacesStackWithoutChangingPriorityOrder() {
        ConfigurationStack stack = ArchaiusUtil.newConfigurationStackAdapter();
        stack.add(mapConfig("shared", "old"));
        ConfigurationSourceList sources = ArchaiusConfigurationSourceList.of(Arrays.asList(
                mapConfig("shared", "first"), mapConfig("shared", "second")));

        sources.replace(stack);

        assertEquals("first", stack.getString("shared"));
    }

    @Test
    public void reportsAndAttachesLazyJdbcSources() {
        LazyJDBCSource lazySource = new LazyJDBCSource();
        NamedDynamicConfiguration databaseConfig = new NamedDynamicConfiguration(lazySource,
                new RefreshableFixedDelayPollingScheduler());
        ConfigurationSourceList sources = ArchaiusConfigurationSourceList.of(Arrays.asList(
                mapConfig("normal", "value"), databaseConfig));

        assertTrue(sources.hasLazyJdbcSource());

        sources.attachJdbcSources(unsupportedDataSource(), "select name, value from setting", "name", "value");

        assertNotNull(lazySource.getSource());
    }

    @Test
    public void reportsNoLazyJdbcSourceForStaticSources() {
        ConfigurationSourceList sources = ArchaiusConfigurationSourceList.of(Arrays.asList(mapConfig("normal", "value")));

        assertFalse(sources.hasLazyJdbcSource());
    }

    @Test
    public void doesNotRequestDataSourceSupplierWithoutLazyJdbcSource() {
        ConfigurationSourceList sources = ArchaiusConfigurationSourceList.of(Arrays.asList(mapConfig("normal", "value")));
        AtomicBoolean requested = new AtomicBoolean();

        sources.attachJdbcSources(() -> {
            requested.set(true);
            return unsupportedDataSource();
        }, "select name, value from setting", "name", "value");

        assertFalse(requested.get());
    }

    @Test
    public void requestsDataSourceSupplierForLazyJdbcSource() {
        LazyJDBCSource lazySource = new LazyJDBCSource();
        NamedDynamicConfiguration databaseConfig = new NamedDynamicConfiguration(lazySource,
                new RefreshableFixedDelayPollingScheduler());
        ConfigurationSourceList sources = ArchaiusConfigurationSourceList.of(Arrays.asList(databaseConfig));
        AtomicBoolean requested = new AtomicBoolean();

        sources.attachJdbcSources(() -> {
            requested.set(true);
            return unsupportedDataSource();
        }, "select name, value from setting", "name", "value");

        assertTrue(requested.get());
        assertNotNull(lazySource.getSource());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSourceList() {
        ArchaiusConfigurationSourceList.of(null);
    }

    private static AbstractConfiguration mapConfig(String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);
        return new MapConfiguration(props);
    }

    private static DataSource unsupportedDataSource() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] { DataSource.class },
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) {
                        return "UnsupportedDataSource";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

}
