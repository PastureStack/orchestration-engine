package io.cattle.platform.archaius.util;

import io.cattle.platform.archaius.polling.RefreshableFixedDelayPollingScheduler;
import io.cattle.platform.archaius.sources.LazyJDBCSource;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.Configuration;

import com.netflix.config.ConcurrentCompositeConfiguration;
import com.netflix.config.DynamicConfiguration;
import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicDoubleProperty;
import com.netflix.config.DynamicFloatProperty;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicLongProperty;
import com.netflix.config.DynamicStringListProperty;
import com.netflix.config.DynamicStringProperty;
import com.netflix.config.sources.JDBCConfigurationSource;

public class ArchaiusUtil {

    private static List<RefreshableFixedDelayPollingScheduler> schedulers = new ArrayList<RefreshableFixedDelayPollingScheduler>();
    private static volatile DynamicPropertyProvider provider = new ArchaiusDynamicPropertyProvider();

    public static DynamicLongProperty getLong(String key) {
        return legacyProvider().getLong(key, 0);
    }

    public static DynamicIntProperty getInt(String key) {
        return legacyProvider().getInt(key, 0);
    }

    public static DynamicBooleanProperty getBoolean(String key) {
        return legacyProvider().getBoolean(key, false);
    }

    public static DynamicDoubleProperty getDouble(String key) {
        return legacyProvider().getDouble(key, 0);
    }

    public static DynamicFloatProperty getFloat(String key) {
        return legacyProvider().getFloat(key, 0);
    }

    public static DynamicStringProperty getString(String key) {
        return legacyProvider().getString(key, null);
    }

    public static Configuration getConfiguration() {
        return provider.getConfiguration();
    }

    public static ConfigurationView getConfigurationView() {
        return ArchaiusConfigurationView.getCurrent();
    }

    public static AbstractConfiguration newConfigurationStack() {
        return new ConcurrentCompositeConfiguration();
    }

    public static ConfigurationStack newConfigurationStackAdapter() {
        return new ArchaiusConfigurationStack(newConfigurationStack());
    }

    public static void initialize(AbstractConfiguration configuration) {
        provider.initialize(configuration);
    }

    public static void initialize(ConfigurationStack configurationStack) {
        initialize(configurationStack.asConfiguration());
    }

    public static void addConfiguration(AbstractConfiguration target, AbstractConfiguration source) {
        new ArchaiusConfigurationStack(target).add(source);
    }

    public static boolean isLazyJdbcSource(AbstractConfiguration config) {
        return config instanceof DynamicConfiguration && ((DynamicConfiguration) config).getSource() instanceof LazyJDBCSource;
    }

    public static boolean setJdbcConfigurationSource(AbstractConfiguration config, DataSource dataSource, String query,
            String keyColumnName, String valueColumnName) {
        if (!isLazyJdbcSource(config)) {
            return false;
        }

        LazyJDBCSource source = (LazyJDBCSource) ((DynamicConfiguration) config).getSource();
        source.setSource(new JDBCConfigurationSource(dataSource, query, keyColumnName, valueColumnName));
        return true;
    }

    /**
     * Please only use this as a static variable. Calling getList(..).get()
     * repeatedly will probably cause a memory leak
     *
     * @param key
     * @return
     */
    public static DynamicStringListProperty getList(String key) {
        return legacyProvider().getList(key, null);
    }

    public static ConfigListProperty<String> getStringListProperty(String key) {
        return getStringListProperty(key, null);
    }

    public static ConfigListProperty<String> getStringListProperty(String key, String defaultValue) {
        return provider.getStringListProperty(key, defaultValue);
    }

    public static ConfigMapProperty<String, String> getStringMapProperty(String key) {
        return getStringMapProperty(key, null);
    }

    public static ConfigMapProperty<String, String> getStringMapProperty(String key, String defaultValue) {
        return provider.getStringMapProperty(key, defaultValue);
    }

    public static ConfigProperty<Integer> getIntProperty(String key) {
        return getIntProperty(key, 0);
    }

    public static ConfigProperty<Integer> getIntProperty(String key, int defaultValue) {
        return provider.getIntProperty(key, defaultValue);
    }

    public static ConfigProperty<String> getStringProperty(String key) {
        return getStringProperty(key, null);
    }

    public static ConfigProperty<String> getStringProperty(String key, String defaultValue) {
        return provider.getStringProperty(key, defaultValue);
    }

    public static ConfigProperty<Long> getLongProperty(String key) {
        return getLongProperty(key, 0);
    }

    public static ConfigProperty<Long> getLongProperty(String key, long defaultValue) {
        return provider.getLongProperty(key, defaultValue);
    }

    public static ConfigProperty<Boolean> getBooleanProperty(String key) {
        return getBooleanProperty(key, false);
    }

    public static ConfigProperty<Boolean> getBooleanProperty(String key, boolean defaultValue) {
        return provider.getBooleanProperty(key, defaultValue);
    }

    public static ConfigProperty<Double> getDoubleProperty(String key) {
        return getDoubleProperty(key, 0);
    }

    public static ConfigProperty<Double> getDoubleProperty(String key, double defaultValue) {
        return provider.getDoubleProperty(key, defaultValue);
    }

    public static ConfigProperty<Float> getFloatProperty(String key) {
        return getFloatProperty(key, 0);
    }

    public static ConfigProperty<Float> getFloatProperty(String key, float defaultValue) {
        return provider.getFloatProperty(key, defaultValue);
    }

    public static void addSchedulers(List<RefreshableFixedDelayPollingScheduler> schedulers) {
        for (RefreshableFixedDelayPollingScheduler scheduler : schedulers) {
            if (!ArchaiusUtil.schedulers.contains(scheduler)) {
                ArchaiusUtil.schedulers.add(scheduler);
            }
        }
    }

    public static void refresh() {
        for (RefreshableFixedDelayPollingScheduler scheduler : schedulers) {
            scheduler.refresh();
        }
    }


    private static LegacyDynamicPropertyProvider legacyProvider() {
        if (provider instanceof LegacyDynamicPropertyProvider) {
            return (LegacyDynamicPropertyProvider) provider;
        }

        throw new IllegalStateException("Configured provider does not support legacy Archaius dynamic property APIs");
    }

    static void setProviderForTesting(DynamicPropertyProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        ArchaiusUtil.provider = provider;
    }

    static void resetProviderForTesting() {
        ArchaiusUtil.provider = new ArchaiusDynamicPropertyProvider();
    }
}
