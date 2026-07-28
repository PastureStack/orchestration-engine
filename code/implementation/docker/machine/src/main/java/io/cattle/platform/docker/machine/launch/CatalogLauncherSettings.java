package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ConfigProperty;

public interface CatalogLauncherSettings {

    ConfigProperty<String> catalogUrlProperty();

    ConfigProperty<String> catalogRefreshIntervalProperty();

    ConfigProperty<String> rancherServerVersionProperty();

    String catalogExecutable();

    boolean launchCatalog();

    String dbType();

    String dbParams();

    String dbHost();

    String dbPort();

    String dbName();

    String dbUser();

    String dbPassword();

    default String catalogUrl() {
        return catalogUrlProperty().get();
    }

    default String catalogRefreshIntervalSeconds() {
        return catalogRefreshIntervalProperty().get();
    }

    default String rancherServerVersion() {
        return rancherServerVersionProperty().get();
    }

}
