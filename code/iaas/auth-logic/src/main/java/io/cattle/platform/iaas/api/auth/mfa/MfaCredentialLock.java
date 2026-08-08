package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;

public class MfaCredentialLock extends AbstractBlockingLockDefintion {

    public MfaCredentialLock(String kind, String key) {
        super("AUTH.MFA." + kind + "." + key);
    }
}
