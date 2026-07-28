package io.cattle.platform.service.launcher;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.util.type.InitializationTask;

import java.util.List;
import java.util.Map;

public class ServiceAccountCreateStartup extends GenericServiceLauncher implements InitializationTask {

    @Override
    public void start() {
        getCredential();
    }

    @Override
    protected boolean shouldRun() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected String binaryPath() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected List<ConfigProperty<String>> getReloadSettings() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void prepareProcess(ProcessBuilder pb) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void setEnvironment(Map<String, String> env) {
    }

    @Override
    protected LockDefinition getLock() {
        return null;
    }

    @Override
    protected boolean isReady() {
        return true;
    }

}