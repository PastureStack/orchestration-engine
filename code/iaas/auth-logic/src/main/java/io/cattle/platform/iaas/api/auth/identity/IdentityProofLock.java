package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;

public class IdentityProofLock extends AbstractBlockingLockDefintion {

    public IdentityProofLock(String proofKey) {
        super("AUTH.IDENTITY.PROOF." + proofKey);
    }
}
