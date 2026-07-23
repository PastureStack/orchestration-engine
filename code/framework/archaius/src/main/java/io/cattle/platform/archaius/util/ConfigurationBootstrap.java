package io.cattle.platform.archaius.util;

public interface ConfigurationBootstrap {

    ConfigurationStack newStack();

    void initialize(ConfigurationStack stack);

}
