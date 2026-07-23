package io.cattle.platform.iaas.api.manager;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

public class ArchaiusHaConfigSettings implements HaConfigSettings {

    private static final ConfigProperty<String> DB = ArchaiusUtil.getStringProperty("db.cattle.database");
    private static final ConfigProperty<String> DB_HOST = "mysql".equals(DB.get())
            ? ArchaiusUtil.getStringProperty("db.cattle.mysql.host")
            : ArchaiusUtil.getStringProperty("db.cattle.postgres.host");
    private static final ConfigProperty<String> DB_PORT = "mysql".equals(DB.get())
            ? ArchaiusUtil.getStringProperty("db.cattle.mysql.port")
            : ArchaiusUtil.getStringProperty("db.cattle.postgres.port");
    private static final ConfigProperty<String> DB_NAME = "mysql".equals(DB.get())
            ? ArchaiusUtil.getStringProperty("db.cattle.mysql.name")
            : ArchaiusUtil.getStringProperty("db.cattle.postgres.name");
    private static final ConfigProperty<String> DB_USER = ArchaiusUtil.getStringProperty("db.cattle.username");
    private static final ConfigProperty<String> DB_PASS = ArchaiusUtil.getStringProperty("db.cattle.password");
    private static final ConfigProperty<Boolean> HA_ENABLED = ArchaiusUtil.getBooleanProperty("ha.enabled");
    private static final ConfigProperty<Integer> HA_CLUSTER_SIZE = ArchaiusUtil.getIntProperty("ha.cluster.size");

    public static HaConfigSettings create() {
        return new ArchaiusHaConfigSettings();
    }

    @Override
    public String dbType() {
        return DB.get();
    }

    @Override
    public String dbHost() {
        return DB_HOST.get();
    }

    @Override
    public String dbPort() {
        return DB_PORT.get();
    }

    @Override
    public String dbName() {
        return DB_NAME.get();
    }

    @Override
    public String dbUser() {
        return DB_USER.get();
    }

    @Override
    public String dbPassword() {
        return DB_PASS.get();
    }

    @Override
    public boolean haEnabled() {
        return HA_ENABLED.get();
    }

    @Override
    public int haClusterSize() {
        return HA_CLUSTER_SIZE.get();
    }

}
