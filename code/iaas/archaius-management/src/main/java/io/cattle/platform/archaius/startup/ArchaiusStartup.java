package io.cattle.platform.archaius.startup;

import io.cattle.platform.archaius.polling.ArchaiusConfigurationSchedulerRegistry;
import io.cattle.platform.archaius.polling.ConfigurationSchedulerRegistry;
import io.cattle.platform.archaius.polling.RefreshableFixedDelayPollingScheduler;
import io.cattle.platform.archaius.sources.ArchaiusConfigFactory;
import io.cattle.platform.archaius.sources.ConfigurationSourceList;
import io.cattle.platform.archaius.util.ArchaiusConfigurationBootstrap;
import io.cattle.platform.archaius.util.ConfigurationBootstrap;
import io.cattle.platform.archaius.util.ConfigurationStack;
import io.cattle.platform.datasource.DataSourceFactory;
import io.cattle.platform.extension.impl.ExtensionManagerImpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArchaiusStartup {

    public static final String CONFIG_KEY = "config";
    public static final String DB_CONFIG = "DatabaseConfig";

    private static Properties GLOBAL_DEFAULT = null;
    private static final Logger log = LoggerFactory.getLogger("ConsoleStatus");

    ExtensionManagerImpl extensionManager;
    ConfigurationStack baseConfig;
    DataSource configDataSource;
    DataSourceFactory dataSourceFactory;
    String dataSourceName = "config";
    String query = "SELECT distinct name, value FROM setting";
    String keyColumnName = "name";
    String valueColumnName = "value";
    List<RefreshableFixedDelayPollingScheduler> schedulers;
    ConfigurationBootstrap configurationBootstrap = ArchaiusConfigurationBootstrap.create();
    boolean init = false;

    @PostConstruct
    public void init() {
        if (init) {
            return;
        }

        if (GLOBAL_DEFAULT == null) {
            throw new IllegalStateException("setGlobalDefaults() must be set before init() is called");
        }

        baseConfig = configurationBootstrap.newStack();
        baseConfig.add(ArchaiusConfigFactory.map(getOverride()));
        baseConfig.add(ArchaiusConfigFactory.properties(GLOBAL_DEFAULT));

        configurationBootstrap.initialize(baseConfig);

        init = true;
    }

    protected Map<String, Object> getOverride() {
        Map<String, Object> override = new HashMap<String, Object>();
        override.put(CONFIG_KEY + ".exclude", DB_CONFIG);

        return override;
    }

    public void start() {
        log.info("Loading configuration");
        load(false);
        extensionManager.reset();
        load(true);
    }

    protected void load(boolean refresh) {
        ConfigurationSourceList configs = ArchaiusConfigRegistration.getConfigSources(extensionManager, CONFIG_KEY);
        configs.disableDelimiterParsing();

        if (refresh) {
            refresh(configs);
        }

        configs.replace(baseConfig);

        if (refresh) {
            newSchedulerRegistry().refreshAndRegister();
        }
    }

    protected void refresh(ConfigurationSourceList configs) {
        if (!shouldAttachJdbcSources()) {
            return;
        }

        configs.attachJdbcSources(() -> getConfigDataSource(), query, keyColumnName, valueColumnName);
    }

    protected boolean shouldAttachJdbcSources() {
        String database = baseConfig == null ? null : baseConfig.getString("db.cattle.database");
        if (database == null) {
            return true;
        }

        String normalized = database.trim().toLowerCase();
        return "mysql".equals(normalized) || "mariadb".equals(normalized) || "postgres".equals(normalized)
                || "postgresql".equals(normalized);
    }

    protected DataSource getConfigDataSource() {
        if (configDataSource == null) {
            configDataSource = dataSourceFactory.createDataSource(dataSourceName);
        }

        return configDataSource;
    }

    protected ConfigurationSchedulerRegistry newSchedulerRegistry() {
        return ArchaiusConfigurationSchedulerRegistry.of(schedulers);
    }

    public ExtensionManagerImpl getExtensionManager() {
        return extensionManager;
    }

    @Inject
    public void setExtensionManager(ExtensionManagerImpl extensionManager) {
        this.extensionManager = extensionManager;
    }

    public static Properties setGlobalDefaults(Properties props) {
        return GLOBAL_DEFAULT = props;
    }

    public static Properties getGlobalDefaults() {
        return GLOBAL_DEFAULT;
    }

    public DataSourceFactory getDataSourceFactory() {
        return dataSourceFactory;
    }

    @Inject
    public void setDataSourceFactory(DataSourceFactory dataSourceFactory) {
        this.dataSourceFactory = dataSourceFactory;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public void setDataSourceName(String dataSourceName) {
        this.dataSourceName = dataSourceName;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getKeyColumnName() {
        return keyColumnName;
    }

    public void setKeyColumnName(String keyColumnName) {
        this.keyColumnName = keyColumnName;
    }

    public String getValueColumnName() {
        return valueColumnName;
    }

    public void setValueColumnName(String valueColumnName) {
        this.valueColumnName = valueColumnName;
    }

    public List<RefreshableFixedDelayPollingScheduler> getSchedulers() {
        return schedulers;
    }

    @Inject
    public void setSchedulers(List<RefreshableFixedDelayPollingScheduler> schedulers) {
        this.schedulers = schedulers;
    }

}
