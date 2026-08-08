package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

public class ArchaiusWebsocketProxyLauncherSettings implements WebsocketProxyLauncherSettings {

    private static final ConfigProperty<String> ACCESS_LOG = ArchaiusUtil.getStringProperty("access.log");
    private static final ConfigProperty<String> API_INTERCEPTOR_CONFIG = ArchaiusUtil.getStringProperty("api.interceptor.config");
    private static final ConfigProperty<String> API_INTERCEPTOR_CONFIG_FILE = ArchaiusUtil.getStringProperty("api.interceptor.config.file");

    public static WebsocketProxyLauncherSettings create() {
        return new ArchaiusWebsocketProxyLauncherSettings();
    }

    @Override
    public ConfigProperty<String> accessLogProperty() {
        return ACCESS_LOG;
    }

    @Override
    public ConfigProperty<String> apiInterceptorConfigProperty() {
        return API_INTERCEPTOR_CONFIG;
    }

    @Override
    public ConfigProperty<String> apiInterceptorConfigFileProperty() {
        return API_INTERCEPTOR_CONFIG_FILE;
    }

}
