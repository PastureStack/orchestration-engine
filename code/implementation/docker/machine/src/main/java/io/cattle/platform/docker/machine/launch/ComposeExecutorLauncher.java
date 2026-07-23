package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.hazelcast.membership.ClusterService;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.service.launcher.GenericServiceLauncher;
import io.cattle.platform.util.type.InitializationTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;


public class ComposeExecutorLauncher extends GenericServiceLauncher implements InitializationTask {

    private static final ComposeExecutorLauncherSettings DEFAULT_SETTINGS = ArchaiusComposeExecutorLauncherSettings.create();

    @Inject
    ClusterService clusterService;

    private ComposeExecutorLauncherSettings settings;

    public ComposeExecutorLauncher() {
        this(DEFAULT_SETTINGS);
    }

    ComposeExecutorLauncher(ComposeExecutorLauncherSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void setSettings(ComposeExecutorLauncherSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    protected boolean shouldRun() {
        return settings.launchComposeExecutor() && clusterService.isMaster();
    }

    @Override
    protected String binaryPath() {
        return settings.composeExecutorExecutable();
    }

    @Override
    protected void setEnvironment(Map<String, String> env) {
        Credential cred = getCredential();
        env.put("CATTLE_ACCESS_KEY", cred.getPublicValue());
        env.put("CATTLE_SECRET_KEY", cred.getSecretValue());
        env.put("CATTLE_URL", LocalCattleApi.url());
        env.put("RANCHER_CLIENT_TIMEOUT", settings.composeExecutorClientTimeout());
    }

    @Override
    protected LockDefinition getLock() {
        return null;
    }

    @Override
    protected boolean isReady() {
        return LocalCattleApi.isReady();
    }
    
    @Override
    protected List<ConfigProperty<String>> getReloadSettings() {
        List<ConfigProperty<String>> list = new ArrayList<>();
        list.add(settings.composeExecutorClientTimeoutProperty());
        return list;
    }

}
