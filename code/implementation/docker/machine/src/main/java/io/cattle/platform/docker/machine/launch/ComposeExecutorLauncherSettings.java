package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ConfigProperty;

public interface ComposeExecutorLauncherSettings {

    String composeExecutorExecutable();

    String composeExecutorClientTimeout();

    boolean launchComposeExecutor();

    ConfigProperty<String> composeExecutorClientTimeoutProperty();

}
