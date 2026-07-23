package io.cattle.platform.api.html;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

final class ArchaiusHtmlTemplateSettings implements HtmlTemplateSettings {

    private final ConfigProperty<String> jsUrl = ArchaiusUtil.getStringProperty("api.ui.js.url");
    private final ConfigProperty<String> cssUrl = ArchaiusUtil.getStringProperty("api.ui.css.url");

    static HtmlTemplateSettings create() {
        return new ArchaiusHtmlTemplateSettings();
    }

    @Override
    public String jsUrl() {
        return jsUrl.get();
    }

    @Override
    public String cssUrl() {
        return cssUrl.get();
    }
}
