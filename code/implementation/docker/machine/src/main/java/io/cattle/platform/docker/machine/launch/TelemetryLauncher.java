package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.hazelcast.membership.ClusterService;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.service.launcher.GenericServiceLauncher;
import io.cattle.platform.util.type.InitializationTask;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

public class TelemetryLauncher extends GenericServiceLauncher implements InitializationTask {

    private static final ConfigProperty<String> TELEMETRY_BINARY = ArchaiusUtil.getStringProperty("telemetry.service.executable");
    private static final ConfigProperty<String> LAUNCH_TELEMETRY = ArchaiusUtil.getStringProperty("telemetry.opt");

    @Inject
    ClusterService clusterService;

    @Override
    protected boolean shouldRun() {
        return "in".equals(LAUNCH_TELEMETRY.get()) && clusterService.isMaster();
    }

    @Override
    protected String binaryPath() {
        return TELEMETRY_BINARY.get();
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

    @Override
    protected void prepareProcess(ProcessBuilder pb) throws IOException {
        pb.command().add("client");
    }

    @Override
    protected List<ConfigProperty<String>> getReloadSettings() {
        return Arrays.asList(LAUNCH_TELEMETRY);
    }

}
