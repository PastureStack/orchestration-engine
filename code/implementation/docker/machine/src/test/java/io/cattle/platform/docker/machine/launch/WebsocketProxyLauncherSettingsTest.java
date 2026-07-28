package io.cattle.platform.docker.machine.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.util.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class WebsocketProxyLauncherSettingsTest {

    @Test
    public void reloadSettingsKeepExistingOrder() {
        StubWebsocketProxySettings settings = new StubWebsocketProxySettings();
        WebsocketProxyLauncher launcher = new WebsocketProxyLauncher(settings);

        List<ConfigProperty<String>> reloadSettings = launcher.getReloadSettings();

        assertSame(settings.accessLogProperty(), reloadSettings.get(0));
        assertSame(settings.apiInterceptorConfigProperty(), reloadSettings.get(1));
        assertEquals(2, reloadSettings.size());
    }

    @Test
    public void prepareConfigFileWritesInterceptorConfigUnchanged() throws Exception {
        Path configFile = Files.createTempFile("rc16-api-interceptor", ".json");
        try {
            StubWebsocketProxySettings settings = new StubWebsocketProxySettings()
                    .withApiInterceptorConfigFile(configFile.toString())
                    .withApiInterceptorConfig("{\"enabled\":true,\"raw\":\"a=b&c=d\"}");
            WebsocketProxyLauncher launcher = new WebsocketProxyLauncher(settings);

            launcher.prepareConfigFile();

            assertEquals("{\"enabled\":true,\"raw\":\"a=b&c=d\"}",
                    Files.readString(configFile, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(configFile);
        }
    }

    @Test
    public void blankInterceptorConfigDeletesExistingConfigFile() throws Exception {
        Path configFile = Files.createTempFile("rc16-api-interceptor", ".json");
        Files.writeString(configFile, "stale", StandardCharsets.UTF_8);

        StubWebsocketProxySettings settings = new StubWebsocketProxySettings()
                .withApiInterceptorConfigFile(configFile.toString())
                .withApiInterceptorConfig("   ");
        WebsocketProxyLauncher launcher = new WebsocketProxyLauncher(settings);

        assertTrue(Files.exists(configFile));

        launcher.prepareConfigFile();

        assertFalse(Files.exists(configFile));
    }

    private static class StubWebsocketProxySettings implements WebsocketProxyLauncherSettings {

        private final StaticConfigProperty<String> accessLog = new StaticConfigProperty<>("false");
        private final StaticConfigProperty<String> apiInterceptorConfig = new StaticConfigProperty<>("");
        private final StaticConfigProperty<String> apiInterceptorConfigFile = new StaticConfigProperty<>("api-interceptor.json");

        StubWebsocketProxySettings withApiInterceptorConfig(String value) {
            apiInterceptorConfig.value = value;
            return this;
        }

        StubWebsocketProxySettings withApiInterceptorConfigFile(String value) {
            apiInterceptorConfigFile.value = value;
            return this;
        }

        @Override
        public ConfigProperty<String> accessLogProperty() {
            return accessLog;
        }

        @Override
        public ConfigProperty<String> apiInterceptorConfigProperty() {
            return apiInterceptorConfig;
        }

        @Override
        public ConfigProperty<String> apiInterceptorConfigFileProperty() {
            return apiInterceptorConfigFile;
        }
    }

    private static class StaticConfigProperty<T> implements ConfigProperty<T> {

        private T value;

        StaticConfigProperty(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }
    }
}
