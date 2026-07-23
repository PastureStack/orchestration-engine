package io.cattle.platform.api.servlet;

import java.util.List;

interface ApiRequestFilterSettings {
    List<String> ignorePaths();

    String projectLabel();

    String localization();

    String serverVersion();
}
