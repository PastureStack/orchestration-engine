package io.cattle.platform.api.servlet;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

final class ArchaiusIndexFileSettings implements IndexFileSettings {

    private final ConfigProperty<String> indexUrl = ArchaiusUtil.getStringProperty("api.ui.index");

    static IndexFileSettings create() {
        return new ArchaiusIndexFileSettings();
    }

    @Override
    public String indexUrl() {
        return indexUrl.get();
    }

    @Override
    public void addIndexUrlCallback(Runnable callback) {
        indexUrl.addCallback(callback);
    }
}
