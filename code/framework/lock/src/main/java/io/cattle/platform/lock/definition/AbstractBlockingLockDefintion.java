package io.cattle.platform.lock.definition;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

public abstract class AbstractBlockingLockDefintion extends AbstractLockDefinition implements BlockingLockDefinition {

    private static final ConfigProperty<Long> DEFAULT_WAIT = ArchaiusUtil.getLongProperty("default.lock.wait.millis");

    public AbstractBlockingLockDefintion(String lockId) {
        super(lockId);
    }

    @Override
    public long getWait() {
        return DEFAULT_WAIT.get();
    }

}
