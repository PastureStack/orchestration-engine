package io.cattle.platform.docker.machine.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.util.ConfigProperty;

import java.util.List;

import org.junit.Test;

public class AuthServiceLauncherSettingsTest {

    @Test
    public void settingsDriveRunAndBinaryPath() {
        StubAuthServiceSettings settings = new StubAuthServiceSettings()
                .withLaunchAuthService(true)
                .withAuthServiceExecutable("/usr/local/bin/auth-service");
        AuthServiceLauncher launcher = new AuthServiceLauncher(settings);

        assertTrue(launcher.shouldRun());
        assertEquals("/usr/local/bin/auth-service", launcher.binaryPath());

        settings.withLaunchAuthService(false).withAuthServiceExecutable("/opt/auth-service");

        assertFalse(launcher.shouldRun());
        assertEquals("/opt/auth-service", launcher.binaryPath());
    }

    @Test
    public void reloadSettingsKeepExistingOrder() {
        StubAuthServiceSettings settings = new StubAuthServiceSettings();
        AuthServiceLauncher launcher = new AuthServiceLauncher(settings);

        List<ConfigProperty<String>> reloadSettings = launcher.getReloadSettings();

        assertSame(settings.authEnablerProperty(), reloadSettings.get(0));
        assertSame(settings.securityProperty(), reloadSettings.get(1));
        assertSame(settings.authServiceLogLevelProperty(), reloadSettings.get(2));
        assertSame(settings.authServiceConfigUpdateTimestampProperty(), reloadSettings.get(3));
        assertSame(settings.shibbolethRedirectWhitelistProperty(), reloadSettings.get(4));
        assertEquals(5, reloadSettings.size());
    }

    @Test
    public void disabledReloadDoesNotRequireAuthServiceUrl() {
        StubAuthServiceSettings settings = new StubAuthServiceSettings()
                .withLaunchAuthService(false)
                .withThrowOnAuthServiceUrl(true);
        AuthServiceLauncher launcher = new AuthServiceLauncher(settings);

        launcher.reload();
    }

    @Test
    public void legacyStaticCompatibilityFieldsRemainAvailable() {
        assertNotNull(AuthServiceLauncher.SECURITY_SETTING);
        assertNotNull(AuthServiceLauncher.EXTERNAL_AUTH_PROVIDER_SETTING);
        assertNotNull(AuthServiceLauncher.NO_IDENTITY_LOOKUP_SETTING);
        assertNotNull(AuthServiceLauncher.API_AUTH_SHIBBOLETH_REDIRECT_WHITELIST_SETTING);
    }

    private static class StubAuthServiceSettings implements AuthServiceLauncherSettings {

        private final StaticConfigProperty<String> authEnabler = new StaticConfigProperty<>("auth-enabler");
        private final StaticConfigProperty<String> security = new StaticConfigProperty<>("security");
        private final StaticConfigProperty<String> externalAuthProvider = new StaticConfigProperty<>("external-auth-provider");
        private final StaticConfigProperty<String> noIdentityLookup = new StaticConfigProperty<>("no-identity-lookup");
        private final StaticConfigProperty<String> authServiceLogLevel = new StaticConfigProperty<>("info");
        private final StaticConfigProperty<String> authServiceConfigUpdateTimestamp = new StaticConfigProperty<>("0");
        private final StaticConfigProperty<String> shibbolethRedirectWhitelist = new StaticConfigProperty<>("");
        private String authServiceExecutable = "/usr/bin/auth-service";
        private boolean launchAuthService;
        private boolean throwOnAuthServiceUrl;

        StubAuthServiceSettings withAuthServiceExecutable(String value) {
            authServiceExecutable = value;
            return this;
        }

        StubAuthServiceSettings withLaunchAuthService(boolean value) {
            launchAuthService = value;
            return this;
        }

        StubAuthServiceSettings withThrowOnAuthServiceUrl(boolean value) {
            throwOnAuthServiceUrl = value;
            return this;
        }

        @Override
        public String authServiceExecutable() {
            return authServiceExecutable;
        }

        @Override
        public boolean launchAuthService() {
            return launchAuthService;
        }

        @Override
        public String authServiceUrl() {
            if (throwOnAuthServiceUrl) {
                throw new AssertionError("authServiceUrl should not be read when auth-service execution is disabled");
            }
            return "http://localhost:8090";
        }

        @Override
        public ConfigProperty<String> authEnablerProperty() {
            return authEnabler;
        }

        @Override
        public ConfigProperty<String> securityProperty() {
            return security;
        }

        @Override
        public ConfigProperty<String> externalAuthProviderProperty() {
            return externalAuthProvider;
        }

        @Override
        public ConfigProperty<String> noIdentityLookupProperty() {
            return noIdentityLookup;
        }

        @Override
        public ConfigProperty<String> authServiceLogLevelProperty() {
            return authServiceLogLevel;
        }

        @Override
        public ConfigProperty<String> authServiceConfigUpdateTimestampProperty() {
            return authServiceConfigUpdateTimestamp;
        }

        @Override
        public ConfigProperty<String> shibbolethRedirectWhitelistProperty() {
            return shibbolethRedirectWhitelist;
        }
    }

    private static class StaticConfigProperty<T> implements ConfigProperty<T> {

        private final T value;

        StaticConfigProperty(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }
    }
}
