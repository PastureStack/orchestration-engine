package io.cattle.platform.process.lock;

import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.constants.PortConstants;
import io.cattle.platform.lock.definition.AbstractLockDefinition;

public class InstancePortsLock extends AbstractLockDefinition {

    public InstancePortsLock(Instance instance) {
        super(PortConstants.INSTANCE_PORTS_LOCK_PREFIX + instance.getId());
    }

}
