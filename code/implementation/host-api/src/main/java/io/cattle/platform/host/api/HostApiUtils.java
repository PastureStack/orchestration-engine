package io.cattle.platform.host.api;

import io.cattle.platform.archaius.util.ConfigProperty;


public class HostApiUtils {

    private static final HostApiSettings SETTINGS = ArchaiusHostApiSettings.create();

    /*
     * Legacy public fields kept for source compatibility with old Rancher 1.6
     * integrations. New code should call the accessor methods below.
     */
    public static final ConfigProperty<String> HOST_API_PROXY_HOST = new ConfigProperty<String>() {
        @Override
        public String get() {
            return SETTINGS.proxyHost();
        }
    };

    public static final ConfigProperty<String> HOST_API_PROXY_BACKEND = new ConfigProperty<String>() {
        @Override
        public String get() {
            return SETTINGS.proxyBackendPath();
        }
    };

    public static String getHostApiProxyHost() {
        return SETTINGS.proxyHost();
    }

    public static String getHostApiProxyBackendPath() {
        return SETTINGS.proxyBackendPath();
    }
}
