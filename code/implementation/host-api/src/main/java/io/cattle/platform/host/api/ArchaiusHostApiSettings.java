package io.cattle.platform.host.api;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

public class ArchaiusHostApiSettings implements HostApiSettings {

    private final ConfigProperty<String> authHeader = ArchaiusUtil.getStringProperty("host.api.auth.header");
    private final ConfigProperty<String> authHeaderValueFormat = ArchaiusUtil.getStringProperty("host.api.auth.header.value");
    private final ConfigProperty<String> proxyHost = ArchaiusUtil.getStringProperty("host.api.proxy.host");
    private final ConfigProperty<String> proxyBackendPath = ArchaiusUtil.getStringProperty("host.api.proxy.backend.path");

    public static HostApiSettings create() {
        return new ArchaiusHostApiSettings();
    }

    @Override
    public String authHeader() {
        return authHeader.get();
    }

    @Override
    public String authHeaderValueFormat() {
        return authHeaderValueFormat.get();
    }

    @Override
    public String proxyHost() {
        return proxyHost.get();
    }

    @Override
    public String proxyBackendPath() {
        return proxyBackendPath.get();
    }

}
