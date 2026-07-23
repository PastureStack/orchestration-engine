package io.cattle.platform.vm.api;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

public class ArchaiusConsoleActionSettings implements ConsoleActionSettings {

    private final ConfigProperty<String> consoleAgentPath = ArchaiusUtil.getStringProperty("console.agent.path");

    public static ConsoleActionSettings create() {
        return new ArchaiusConsoleActionSettings();
    }

    @Override
    public String consoleAgentPath() {
        return consoleAgentPath.get();
    }

}
