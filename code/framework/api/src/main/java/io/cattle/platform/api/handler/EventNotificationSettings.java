package io.cattle.platform.api.handler;

import java.util.List;

interface EventNotificationSettings {

    List<String> excludeTypes();

    void addExcludeTypesCallback(Runnable callback);
}
