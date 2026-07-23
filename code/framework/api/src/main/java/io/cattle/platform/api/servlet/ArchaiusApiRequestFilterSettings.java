package io.cattle.platform.api.servlet;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigListProperty;
import io.cattle.platform.archaius.util.ConfigProperty;

import java.util.List;

final class ArchaiusApiRequestFilterSettings implements ApiRequestFilterSettings {

    private final ConfigListProperty<String> ignorePaths = ArchaiusUtil.getStringListProperty("api.ignore.paths");
    private final ConfigProperty<String> projectLabel = ArchaiusUtil.getStringProperty("ui.pl");
    private final ConfigProperty<String> localization = ArchaiusUtil.getStringProperty("localization");
    private final ConfigProperty<String> serverVersion = ArchaiusUtil.getStringProperty("rancher.server.version");

    static ApiRequestFilterSettings create() {
        return new ArchaiusApiRequestFilterSettings();
    }

    @Override
    public List<String> ignorePaths() {
        return ignorePaths.get();
    }

    @Override
    public String projectLabel() {
        return projectLabel.get();
    }

    @Override
    public String localization() {
        return localization.get();
    }

    @Override
    public String serverVersion() {
        return serverVersion.get();
    }
}
