package io.cattle.platform.api.resource;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

final class ArchaiusObjectResourceManagerSettings implements ObjectResourceManagerSettings {

    private final ConfigProperty<Integer> removedDelaySeconds = ArchaiusUtil.getIntProperty(
            "api.show.removed.for.seconds");

    static ObjectResourceManagerSettings create() {
        return new ArchaiusObjectResourceManagerSettings();
    }

    @Override
    public int removedDelaySeconds() {
        return removedDelaySeconds.get();
    }
}
