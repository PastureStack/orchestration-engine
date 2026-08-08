package io.cattle.platform.iaas.api.request.handler;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigListProperty;

import java.util.List;

final class ArchaiusRequestOptionsSettings implements RequestOptionsSettings {

    private final ConfigListProperty<String> options = ArchaiusUtil.getStringListProperty("api.request.options");

    static RequestOptionsSettings create() {
        return new ArchaiusRequestOptionsSettings();
    }

    @Override
    public List<String> options() {
        return options.get();
    }
}
