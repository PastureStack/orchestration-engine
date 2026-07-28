package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.iaas.api.manager.ArchaiusHaConfigSettings;
import io.cattle.platform.iaas.api.manager.HaConfigSettings;

public class ArchaiusCatalogLauncherSettings implements CatalogLauncherSettings {

    private static final ConfigProperty<String> CATALOG_URL = ArchaiusUtil.getStringProperty("catalog.url");
    private static final ConfigProperty<String> CATALOG_REFRESH_INTERVAL = ArchaiusUtil.getStringProperty("catalog.refresh.interval.seconds");
    private static final ConfigProperty<String> CATALOG_BINARY = ArchaiusUtil.getStringProperty("catalog.service.executable");
    private static final ConfigProperty<String> DB_TYPE = ArchaiusUtil.getStringProperty("db.cattle.database");
    private static final ConfigProperty<String> DB_PARAMS = ArchaiusUtil.getStringProperty("db.cattle.go.params");
    private static final ConfigProperty<Boolean> LAUNCH_CATALOG = ArchaiusUtil.getBooleanProperty("catalog.execute");
    private static final ConfigProperty<String> RANCHER_SERVER_VERSION = ArchaiusUtil.getStringProperty("rancher.server.version");
    private static final HaConfigSettings HA_CONFIG = ArchaiusHaConfigSettings.create();

    public static CatalogLauncherSettings create() {
        return new ArchaiusCatalogLauncherSettings();
    }

    @Override
    public ConfigProperty<String> catalogUrlProperty() {
        return CATALOG_URL;
    }

    @Override
    public ConfigProperty<String> catalogRefreshIntervalProperty() {
        return CATALOG_REFRESH_INTERVAL;
    }

    @Override
    public ConfigProperty<String> rancherServerVersionProperty() {
        return RANCHER_SERVER_VERSION;
    }

    @Override
    public String catalogExecutable() {
        return CATALOG_BINARY.get();
    }

    @Override
    public boolean launchCatalog() {
        return LAUNCH_CATALOG.get();
    }

    @Override
    public String dbType() {
        return DB_TYPE.get();
    }

    @Override
    public String dbParams() {
        return DB_PARAMS.get();
    }

    @Override
    public String dbHost() {
        return HA_CONFIG.dbHost();
    }

    @Override
    public String dbPort() {
        return HA_CONFIG.dbPort();
    }

    @Override
    public String dbName() {
        return HA_CONFIG.dbName();
    }

    @Override
    public String dbUser() {
        return HA_CONFIG.dbUser();
    }

    @Override
    public String dbPassword() {
        return HA_CONFIG.dbPassword();
    }

}
