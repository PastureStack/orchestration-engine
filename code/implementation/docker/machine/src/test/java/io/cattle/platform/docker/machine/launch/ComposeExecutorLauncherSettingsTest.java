package io.cattle.platform.docker.machine.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.hazelcast.membership.ClusterService;
import io.cattle.platform.hazelcast.membership.ClusteredMember;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ComposeExecutorLauncherSettingsTest {

    @Test
    public void settingsAndMasterGateDriveRunAndBinaryPath() {
        StubComposeExecutorSettings settings = new StubComposeExecutorSettings()
                .withLaunchComposeExecutor(true)
                .withComposeExecutorExecutable("/usr/local/bin/compose-executor");
        ComposeExecutorLauncher launcher = new ComposeExecutorLauncher(settings);
        launcher.clusterService = new StubClusterService(true);

        assertTrue(launcher.shouldRun());
        assertEquals("/usr/local/bin/compose-executor", launcher.binaryPath());

        launcher.clusterService = new StubClusterService(false);
        assertFalse(launcher.shouldRun());

        settings.withLaunchComposeExecutor(false)
                .withComposeExecutorExecutable("/opt/compose-executor");
        launcher.clusterService = new StubClusterService(true);

        assertFalse(launcher.shouldRun());
        assertEquals("/opt/compose-executor", launcher.binaryPath());
    }

    @Test
    public void reloadSettingsKeepExistingOrder() {
        StubComposeExecutorSettings settings = new StubComposeExecutorSettings();
        ComposeExecutorLauncher launcher = new ComposeExecutorLauncher(settings);

        List<ConfigProperty<String>> reloadSettings = launcher.getReloadSettings();

        assertSame(settings.composeExecutorClientTimeoutProperty(), reloadSettings.get(0));
        assertEquals(1, reloadSettings.size());
    }

    @Test
    public void environmentKeepsCredentialsUrlAndClientTimeout() {
        StubComposeExecutorSettings settings = new StubComposeExecutorSettings()
                .withComposeExecutorClientTimeout("120");
        ComposeExecutorLauncher launcher = new TestableComposeExecutorLauncher(settings);
        Map<String, String> env = new HashMap<>();

        launcher.setEnvironment(env);

        assertEquals("public-key", env.get("CATTLE_ACCESS_KEY"));
        assertEquals("secret-key", env.get("CATTLE_SECRET_KEY"));
        assertTrue(env.get("CATTLE_URL").startsWith("http://127.0.0.1:"));
        assertEquals("120", env.get("RANCHER_CLIENT_TIMEOUT"));
    }

    private static class StubComposeExecutorSettings implements ComposeExecutorLauncherSettings {

        private final StaticConfigProperty<String> composeExecutorClientTimeout = new StaticConfigProperty<>("60");
        private String composeExecutorExecutable = "/usr/bin/compose-executor";
        private boolean launchComposeExecutor;

        StubComposeExecutorSettings withComposeExecutorExecutable(String value) {
            composeExecutorExecutable = value;
            return this;
        }

        StubComposeExecutorSettings withLaunchComposeExecutor(boolean value) {
            launchComposeExecutor = value;
            return this;
        }

        StubComposeExecutorSettings withComposeExecutorClientTimeout(String value) {
            composeExecutorClientTimeout.value = value;
            return this;
        }

        @Override
        public String composeExecutorExecutable() {
            return composeExecutorExecutable;
        }

        @Override
        public String composeExecutorClientTimeout() {
            return composeExecutorClientTimeout.get();
        }

        @Override
        public boolean launchComposeExecutor() {
            return launchComposeExecutor;
        }

        @Override
        public ConfigProperty<String> composeExecutorClientTimeoutProperty() {
            return composeExecutorClientTimeout;
        }
    }

    private static class TestableComposeExecutorLauncher extends ComposeExecutorLauncher {

        TestableComposeExecutorLauncher(ComposeExecutorLauncherSettings settings) {
            super(settings);
        }

        @Override
        public Credential getCredential() {
            return (Credential) Proxy.newProxyInstance(
                    Credential.class.getClassLoader(),
                    new Class<?>[]{Credential.class},
                    (proxy, method, args) -> {
                        if ("getPublicValue".equals(method.getName())) {
                            return "public-key";
                        }
                        if ("getSecretValue".equals(method.getName())) {
                            return "secret-key";
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static class StubClusterService implements ClusterService {

        private final boolean master;

        StubClusterService(boolean master) {
            this.master = master;
        }

        @Override
        public boolean isMaster() {
            return master;
        }

        @Override
        public boolean waitReady() {
            return true;
        }

        @Override
        public ClusteredMember getMaster() {
            return null;
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
