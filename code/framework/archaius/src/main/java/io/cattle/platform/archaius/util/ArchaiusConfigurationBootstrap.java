package io.cattle.platform.archaius.util;

public final class ArchaiusConfigurationBootstrap implements ConfigurationBootstrap {

    private ArchaiusConfigurationBootstrap() {
    }

    public static ConfigurationBootstrap create() {
        return new ArchaiusConfigurationBootstrap();
    }

    @Override
    public ConfigurationStack newStack() {
        return ArchaiusUtil.newConfigurationStackAdapter();
    }

    @Override
    public void initialize(ConfigurationStack stack) {
        ArchaiusUtil.initialize(stack);
    }

}
