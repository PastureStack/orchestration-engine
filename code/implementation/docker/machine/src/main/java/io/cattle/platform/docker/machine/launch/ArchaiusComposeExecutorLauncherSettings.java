package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

public class ArchaiusComposeExecutorLauncherSettings implements ComposeExecutorLauncherSettings {

    private static final ConfigProperty<String> COMPOSE_EXECUTOR_BINARY =
            ArchaiusUtil.getStringProperty("compose.executor.service.executable");
    private static final ConfigProperty<String> COMPOSE_EXECUTOR_CLIENT_TIMEOUT =
            ArchaiusUtil.getStringProperty("compose.executor.service.executable.timeout");
    private static final ConfigProperty<Boolean> LAUNCH_COMPOSE_EXECUTOR =
            ArchaiusUtil.getBooleanProperty("compose.executor.execute");

    public static ComposeExecutorLauncherSettings create() {
        return new ArchaiusComposeExecutorLauncherSettings();
    }

    @Override
    public String composeExecutorExecutable() {
        return COMPOSE_EXECUTOR_BINARY.get();
    }

    @Override
    public String composeExecutorClientTimeout() {
        return COMPOSE_EXECUTOR_CLIENT_TIMEOUT.get();
    }

    @Override
    public boolean launchComposeExecutor() {
        return LAUNCH_COMPOSE_EXECUTOR.get();
    }

    @Override
    public ConfigProperty<String> composeExecutorClientTimeoutProperty() {
        return COMPOSE_EXECUTOR_CLIENT_TIMEOUT;
    }

}
