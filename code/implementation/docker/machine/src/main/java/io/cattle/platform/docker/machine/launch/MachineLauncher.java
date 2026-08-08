package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.hazelcast.membership.ClusterService;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.service.launcher.GenericServiceLauncher;
import io.cattle.platform.util.type.InitializationTask;

import java.util.Map;

import jakarta.inject.Inject;

public class MachineLauncher extends GenericServiceLauncher implements InitializationTask {

    private static final ConfigProperty<String> MACHINE_BINARY = ArchaiusUtil.getStringProperty("machine.service.executable");
    private static final ConfigProperty<Boolean> LAUNCH_MACHINE = ArchaiusUtil.getBooleanProperty("machine.execute");

    @Inject
    ClusterService clusterService;

    @Override
    protected boolean shouldRun() {
        return LAUNCH_MACHINE.get() && clusterService.isMaster();
    }

    @Override
    protected String binaryPath() {
        return MACHINE_BINARY.get();
    }

    @Override
    protected void setEnvironment(Map<String, String> env) {
        Credential cred = getCredential();
        env.put("CATTLE_ACCESS_KEY", cred.getPublicValue());
        env.put("CATTLE_SECRET_KEY", cred.getSecretValue());
        env.put("CATTLE_URL", LocalCattleApi.url());
    }

    @Override
    protected LockDefinition getLock() {
        return null;
    }

    @Override
    protected boolean isReady() {
        return LocalCattleApi.isReady();
    }

}
