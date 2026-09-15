package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;

/**
 * Serializes replacement of interactive sessions for one effective account.
 *
 * The lock includes both account identifiers because a token may be issued in
 * the context of a project account while authenticating as the same user.
 */
public class TokenIssuanceLock extends AbstractBlockingLockDefintion {

    public TokenIssuanceLock(long tokenAccountId, long authenticatedAsAccountId) {
        super("AUTH.TOKEN.ISSUE." + tokenAccountId + "." + authenticatedAsAccountId);
    }
}
