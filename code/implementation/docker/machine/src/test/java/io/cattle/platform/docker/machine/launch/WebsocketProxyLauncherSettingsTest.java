package io.cattle.platform.docker.machine.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.model.Credential;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Path configFile = Files.createTempFile("pasturestack-api-interceptor", ".json");
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
        Path configFile = Files.createTempFile("pasturestack-api-interceptor", ".json");
        Files.writeString(configFile, "stale", StandardCharsets.UTF_8);

        StubWebsocketProxySettings settings = new StubWebsocketProxySettings()
                .withApiInterceptorConfigFile(configFile.toString())
                .withApiInterceptorConfig("   ");
        WebsocketProxyLauncher launcher = new WebsocketProxyLauncher(settings);

        assertTrue(Files.exists(configFile));

        launcher.prepareConfigFile();

        assertFalse(Files.exists(configFile));
    }

    @Test
    public void websocketProxyUsesImmutableAbsoluteExecutablePath() {
        assertEquals("/usr/bin/websocket-proxy",
                new WebsocketProxyLauncher(new StubWebsocketProxySettings()).binaryPath());
    }

    @Test
    public void portsAreCanonicalizedBeforeEnteringChildEnvironment() {
        assertEquals(8080, WebsocketProxyLauncher.parsePort("HTTP port", "08080"));
        assertEquals("443,8443", WebsocketProxyLauncher.canonicalizePortList(
                "HTTPS proxy protocol ports", "443, 8443,443"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shellPayloadIsRejectedAsPort() {
        WebsocketProxyLauncher.parsePort("HTTP port", "8080;touch /tmp/owned");
    }

    @Test(expected = IllegalArgumentException.class)
    public void outOfRangePortIsRejected() {
        WebsocketProxyLauncher.parsePort("HTTP port", "65536");
    }

    @Test
    public void nonSecretProxyConfigurationUsesSeparatedCommandArguments() throws Exception {
        String oldHttpPort = System.getProperty("cattle.http.port");
        String oldProxiedPort = System.getProperty("cattle.http.proxied.port");
        String oldHttpsPorts = System.getProperty("proxy.protocol.https.ports");
        Path configFile = Files.createTempFile("pasturestack-api-interceptor", ".json");
        try {
            System.setProperty("cattle.http.port", "18080");
            System.setProperty("cattle.http.proxied.port", "18081");
            System.setProperty("proxy.protocol.https.ports", "443, 8443,443");
            StubWebsocketProxySettings settings = new StubWebsocketProxySettings()
                    .withApiInterceptorConfigFile(configFile.toString());
            WebsocketProxyLauncher launcher = new WebsocketProxyLauncher(settings);
            ProcessBuilder process = new ProcessBuilder("/usr/bin/websocket-proxy");

            launcher.prepareProcess(process);

            assertEquals(List.of(
                    "/usr/bin/websocket-proxy",
                    "--listen-address=:18080",
                    "--tls-listen-address=:18080",
                    "--platform-address=localhost:18081",
                    "--https-proxy-protocol-ports=443,8443"), process.command());
        } finally {
            restoreProperty("cattle.http.port", oldHttpPort);
            restoreProperty("cattle.http.proxied.port", oldProxiedPort);
            restoreProperty("proxy.protocol.https.ports", oldHttpsPorts);
            Files.deleteIfExists(configFile);
        }
    }

    @Test
    public void dynamicProxyAddressesNeverEnterChildEnvironment() {
        WebsocketProxyLauncher launcher = new WebsocketProxyLauncher(new StubWebsocketProxySettings()) {
            @Override
            public Credential getCredential() {
                return credential("public-key", "secret-key");
            }
        };
        Map<String, String> environment = new HashMap<>();
        environment.put("INHERITED", "must-be-cleared");

        launcher.setEnvironment(environment);

        assertFalse(environment.containsKey("INHERITED"));
        assertFalse(environment.containsKey("PROXY_LISTEN_ADDRESS"));
        assertFalse(environment.containsKey("PROXY_TLS_LISTEN_ADDRESS"));
        assertFalse(environment.containsKey("PROXY_CATTLE_ADDRESS"));
        assertFalse(environment.containsKey("PROXY_HTTPS_PROXY_PROTOCOL_PORTS"));
        assertEquals("public-key", environment.get("PLATFORM_ACCESS_KEY"));
        assertEquals("secret-key", environment.get("PLATFORM_SECRET_KEY"));
    }

    private static Credential credential(String publicValue, String secretValue) {
        return (Credential) Proxy.newProxyInstance(
                Credential.class.getClassLoader(),
                new Class<?>[]{Credential.class},
                (proxy, method, args) -> {
                    if ("getPublicValue".equals(method.getName())) {
                        return publicValue;
                    }
                    if ("getSecretValue".equals(method.getName())) {
                        return secretValue;
                    }
                    return null;
                });
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
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
