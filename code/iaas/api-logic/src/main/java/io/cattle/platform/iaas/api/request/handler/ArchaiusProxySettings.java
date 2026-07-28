package io.cattle.platform.iaas.api.request.handler;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigListProperty;
import io.cattle.platform.archaius.util.ConfigProperty;

import java.util.List;

final class ArchaiusProxySettings implements ProxySettings {

    private final ConfigProperty<Boolean> allowProxy = ArchaiusUtil.getBooleanProperty("api.proxy.allow");
    private final ConfigListProperty<String> whitelist = ArchaiusUtil.getStringListProperty("api.proxy.whitelist");
    private final ConfigProperty<Long> connectTimeout = ArchaiusUtil.getLongProperty("api.proxy.connect.timeout.millis");
    private final ConfigProperty<Long> requestTimeout = ArchaiusUtil.getLongProperty("api.proxy.request.timeout.millis");

    static ProxySettings create() {
        return new ArchaiusProxySettings();
    }

    @Override
    public boolean allowProxy() {
        return allowProxy.get();
    }

    @Override
    public List<String> whitelist() {
        return whitelist.get();
    }

    @Override
    public long connectTimeoutMillis() {
        return connectTimeout.get();
    }

    @Override
    public long requestTimeoutMillis() {
        return requestTimeout.get();
    }
}
