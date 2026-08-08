package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ConfigProperty;

public interface WebsocketProxyLauncherSettings {

    ConfigProperty<String> accessLogProperty();

    ConfigProperty<String> apiInterceptorConfigProperty();

    ConfigProperty<String> apiInterceptorConfigFileProperty();

    default String apiInterceptorConfig() {
        return apiInterceptorConfigProperty().get();
    }

    default String apiInterceptorConfigFile() {
        return apiInterceptorConfigFileProperty().get();
    }

}
