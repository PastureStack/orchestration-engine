package io.cattle.platform.api.parser;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigListProperty;
import io.cattle.platform.archaius.util.ConfigProperty;

import java.util.List;

final class ArchaiusApiRequestParserSettings implements ApiRequestParserSettings {

    private final ConfigProperty<Boolean> allowOverride = ArchaiusUtil.getBooleanProperty("api.allow.client.override");
    private final ConfigListProperty<String> httpsPorts = ArchaiusUtil.getStringListProperty("proxy.protocol.https.ports");
    private final ConfigProperty<Boolean> trustForwardedHost = ArchaiusUtil.getBooleanProperty("api.trust.forwarded.host");
    private final ConfigListProperty<String> allowedForwardedHosts =
            ArchaiusUtil.getStringListProperty("api.forwarded.host.allowlist");
    private final ConfigProperty<String> apiHost = ArchaiusUtil.getStringProperty("api.host");

    static ApiRequestParserSettings create() {
        return new ArchaiusApiRequestParserSettings();
    }

    @Override
    public boolean allowClientOverrideHeaders() {
        return allowOverride.get();
    }

    @Override
    public List<String> httpsPorts() {
        return httpsPorts.get();
    }

    @Override
    public boolean trustForwardedHost() {
        return trustForwardedHost.get();
    }

    @Override
    public List<String> allowedForwardedHosts() {
        return allowedForwardedHosts.get();
    }

    @Override
    public String apiHost() {
        return apiHost.get();
    }
}
