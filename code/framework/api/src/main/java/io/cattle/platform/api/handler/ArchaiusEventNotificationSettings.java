package io.cattle.platform.api.handler;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigListProperty;

import java.util.List;

final class ArchaiusEventNotificationSettings implements EventNotificationSettings {

    private final ConfigListProperty<String> excludeTypes = ArchaiusUtil.getStringListProperty(
            "api.event.change.exclude.types");

    static EventNotificationSettings create() {
        return new ArchaiusEventNotificationSettings();
    }

    @Override
    public List<String> excludeTypes() {
        return excludeTypes.get();
    }

    @Override
    public void addExcludeTypesCallback(Runnable callback) {
        excludeTypes.addCallback(callback);
    }
}
