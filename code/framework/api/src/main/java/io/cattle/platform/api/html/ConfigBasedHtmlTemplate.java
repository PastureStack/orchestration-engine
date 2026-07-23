package io.cattle.platform.api.html;

import io.cattle.platform.api.utils.ApiUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.doc.TypeDocumentation;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.response.impl.DefaultHtmlTemplate;
import io.github.ibuildthecloud.gdapi.url.UrlBuilder;

import java.net.URL;


public class ConfigBasedHtmlTemplate extends DefaultHtmlTemplate {

    private static final HtmlTemplateSettings DEFAULT_SETTINGS = ArchaiusHtmlTemplateSettings.create();

    private final HtmlTemplateSettings settings;

    public ConfigBasedHtmlTemplate() {
        this(DEFAULT_SETTINGS);
    }

    ConfigBasedHtmlTemplate(HtmlTemplateSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("HTML template settings are required");
        }
        this.settings = settings;
    }

    @Override
    public String getJsUrl() {
        return settings.jsUrl();
    }

    @Override
    public String getCssUrl() {
        return settings.cssUrl();
    }

    @Override
    protected String getUser(ApiRequest request, Object response) {
        return ApiUtils.getPolicy().getUserName();
    }

    @Override
    protected String getStringHeader(ApiRequest request, Object response) {
        String result = super.getStringHeader(request, response);

        UrlBuilder builder = ApiContext.getUrlBuilder();
        URL link = builder.resourceCollection(TypeDocumentation.class);

        if (link != null) {
            result = result.replace("//BEFORE DATA", String.format("var docJson = '%s';", link.toExternalForm()));
        }

        return result;
    }

}
