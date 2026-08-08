package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;

public class ProviderSwitchLock extends AbstractBlockingLockDefintion {

    public ProviderSwitchLock(String ticketKey) {
        super("AUTH.PROVIDER.SWITCH." + ticketKey);
    }
}
