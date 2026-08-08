package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;

public class IdentityLinkLock extends AbstractBlockingLockDefintion {

    public IdentityLinkLock(String linkKey) {
        super("AUTH.IDENTITY.LINK." + linkKey);
    }
}
