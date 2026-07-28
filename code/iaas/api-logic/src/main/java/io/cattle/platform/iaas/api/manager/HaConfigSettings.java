package io.cattle.platform.iaas.api.manager;

public interface HaConfigSettings {

    String dbType();

    String dbHost();

    String dbPort();

    String dbName();

    String dbUser();

    String dbPassword();

    boolean haEnabled();

    int haClusterSize();

}
