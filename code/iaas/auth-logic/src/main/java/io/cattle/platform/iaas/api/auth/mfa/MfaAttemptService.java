package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.iaas.api.auth.identity.IdentityLinkKey;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import jakarta.inject.Inject;

/**
 * Persists failed MFA verification state per account. Starting a new login
 * challenge therefore cannot reset the throttling counter.
 */
public class MfaAttemptService {

    @Inject
    MfaDao mfaDao;
    @Inject
    MfaPolicyService policyService;
    @Inject
    LockManager lockManager;

    public void assertAllowed(long accountId) {
        Credential state = mfaDao.findActive(CredentialConstants.KIND_MFA_ATTEMPT_STATE,
                key(accountId));
        if (state == null) {
            return;
        }
        long lockedUntil = longValue(state.getData() == null ? null
                : state.getData().get("lockedUntil"), 0L);
        if (lockedUntil > now()) {
            throw locked(lockedUntil);
        }
    }

    public void recordFailure(final long accountId, final String challengeKind,
                              final String challengeKey, final int challengeAttemptLimit) {
        lockManager.lock(
                new MfaCredentialLock(CredentialConstants.KIND_MFA_ATTEMPT_STATE,
                        key(accountId)), new LockCallback<Object>() {
                    @Override
                    public Object doWithLock() {
                        recordChallengeFailure(accountId, challengeKind, challengeKey,
                                challengeAttemptLimit);
                        MfaPolicy policy = policyService.getPolicy();
                        Credential state = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_ATTEMPT_STATE, key(accountId));
                        Map<String, Object> data = state == null || state.getData() == null
                                ? new HashMap<String, Object>() : new HashMap<>(state.getData());
                        long currentLock = longValue(data.get("lockedUntil"), 0L);
                        int failures;
                        if (currentLock > now()) {
                            failures = integer(data.get("failures"), 0);
                        } else if (currentLock > 0L) {
                            failures = 1;
                            data.remove("lockedUntil");
                        } else {
                            failures = integer(data.get("failures"), 0) + 1;
                        }
                        data.put("failures", failures);
                        data.put("lastFailureAt", new Date());
                        long newLockedUntil = 0L;
                        if (failures >= policy.getMaximumFailedAttempts()) {
                            newLockedUntil = now() + policy.getLockoutSeconds() * 1000L;
                            data.put("lockedUntil", newLockedUntil);
                        }
                        if (state == null) {
                            mfaDao.create(accountId, CredentialConstants.KIND_MFA_ATTEMPT_STATE,
                                    key(accountId), null, data);
                        } else {
                            mfaDao.save(state, null, data);
                        }
                        return null;
                    }
                });
    }

    private void recordChallengeFailure(long accountId, String challengeKind,
                                        String challengeKey, int challengeAttemptLimit) {
        Credential challenge = mfaDao.findActive(challengeKind, challengeKey);
        if (challenge == null || challenge.getAccountId() == null
                || challenge.getAccountId() != accountId) {
            return;
        }
        Map<String, Object> data = challenge.getData() == null
                ? new HashMap<String, Object>() : new HashMap<>(challenge.getData());
        int attempts = integer(data.get("attempts"), 0) + 1;
        data.put("attempts", attempts);
        data.put("lastFailureAt", new Date());
        if (attempts >= Math.max(1, challengeAttemptLimit)) {
            mfaDao.deactivate(challenge, "attemptLimit");
        } else {
            mfaDao.save(challenge, null, data);
        }
    }

    public void recordSuccess(final long accountId) {
        lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_ATTEMPT_STATE,
                key(accountId)), new LockCallback<Object>() {
                    @Override
                    public Object doWithLock() {
                        Credential state = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_ATTEMPT_STATE, key(accountId));
                        if (state != null) {
                            mfaDao.deactivate(state, "verificationSucceeded");
                        }
                        return null;
                    }
                });
    }

    private String key(long accountId) {
        return IdentityLinkKey.create("mfa-attempt", "account", String.valueOf(accountId));
    }

    private int integer(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private long longValue(Object value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    long now() {
        return System.currentTimeMillis();
    }

    private ClientVisibleException locked(long lockedUntil) {
        return new ClientVisibleException(ResponseCodes.TOO_MANY_REQUESTS,
                "MfaTemporarilyLocked",
                "Too many failed verification attempts. Try again after the temporary lock expires.",
                String.valueOf(lockedUntil));
    }
}
