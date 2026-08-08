package io.cattle.platform.configitem.server.impl;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.lock.definition.AbstractLockDefinition;
import io.cattle.platform.lock.definition.BlockingLockDefinition;

public class SyncSourceVersionLock extends AbstractLockDefinition implements BlockingLockDefinition {

    private static final ConfigProperty<Long> WAIT = ArchaiusUtil.getLongProperty("sync.source.version.lock.wait.millis");

    public SyncSourceVersionLock() {
        super("SYNC.SOURCE.VERSION.LOCK");
    }

    @Override
    public long getWait() {
        return WAIT.get();
    }

}
