package io.cattle.platform.allocator.lock;

import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;
import io.cattle.platform.core.constants.PortConstants;


public class AllocateConstraintLock extends AbstractBlockingLockDefintion {
    public enum Type {
        DEPLOYMENT_UNIT,
        AFFINITY,
        PORT,
        VOLUME
    }
    public AllocateConstraintLock(Type type, String constraintId) {
        super(type == Type.PORT ? PortConstants.ALLOCATION_PORT_LOCK_PREFIX + constraintId : "ALLOCATE.CONSTRAINT." + type + "." + constraintId);
    }
}
