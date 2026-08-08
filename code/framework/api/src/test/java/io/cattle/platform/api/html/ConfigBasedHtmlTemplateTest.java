package io.cattle.platform.api.html;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class ConfigBasedHtmlTemplateTest {

    @Test
    public void htmlTemplateSettingsReadDynamicValuesThroughWrapper() {
        final String jsKey = "api.ui.js.url";
        final String cssKey = "api.ui.css.url";

        try {
            ConfigurationManager.getConfigInstance().setProperty(jsKey, "https://static.example/ui.js");
            ConfigurationManager.getConfigInstance().setProperty(cssKey, "https://static.example/ui.css");

            HtmlTemplateSettings settings = ArchaiusHtmlTemplateSettings.create();

            assertEquals("https://static.example/ui.js", settings.jsUrl());
            assertEquals("https://static.example/ui.css", settings.cssUrl());
        } finally {
            clearProperty(jsKey);
            clearProperty(cssKey);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingHtmlTemplateSettings() {
        new ConfigBasedHtmlTemplate(null);
    }

    @Test
    public void usesInjectedHtmlTemplateSettings() {
        ConfigBasedHtmlTemplate template = new ConfigBasedHtmlTemplate(settings("/assets/app.js", "/assets/app.css"));

        assertEquals("/assets/app.js", template.getJsUrl());
        assertEquals("/assets/app.css", template.getCssUrl());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsJavascriptResourceUrl() {
        TestableTemplate template = new TestableTemplate(
                settings("javascript:alert(1)", "/assets/app.css"));
        template.exposeSafeResourceUrl(template.getJsUrl());
    }

    @Test
    public void encodesHtmlAttributeInResourceUrl() {
        TestableTemplate template = new TestableTemplate(
                settings("/assets/app.js?value=\"unsafe\"", "/assets/app.css"));
        String safe = template.exposeSafeResourceUrl(template.getJsUrl());
        assertFalse(safe.contains("\""));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsProtocolRelativeResourceUrl() {
        TestableTemplate template = new TestableTemplate(
                settings("//untrusted.example/app.js", "/assets/app.css"));
        template.exposeSafeResourceUrl(template.getJsUrl());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsHttpsResourceUrlWithUserInfo() {
        TestableTemplate template = new TestableTemplate(
                settings("https://trusted.example@untrusted.example/app.js", "/assets/app.css"));
        template.exposeSafeResourceUrl(template.getJsUrl());
    }

    private static void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static HtmlTemplateSettings settings(String jsUrl, String cssUrl) {
        return new TestHtmlTemplateSettings(jsUrl, cssUrl);
    }

    private static final class TestHtmlTemplateSettings implements HtmlTemplateSettings {
        private final String jsUrl;
        private final String cssUrl;

        TestHtmlTemplateSettings(String jsUrl, String cssUrl) {
            this.jsUrl = jsUrl;
            this.cssUrl = cssUrl;
        }

        @Override
        public String jsUrl() {
            return jsUrl;
        }

        @Override
        public String cssUrl() {
            return cssUrl;
        }
    }

    private static final class TestableTemplate extends ConfigBasedHtmlTemplate {
        TestableTemplate(HtmlTemplateSettings settings) {
            super(settings);
        }

        String exposeSafeResourceUrl(String value) {
            return safeResourceUrl(value);
        }
    }
}
