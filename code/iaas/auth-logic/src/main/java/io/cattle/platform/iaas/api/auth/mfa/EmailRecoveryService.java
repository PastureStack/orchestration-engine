package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.framework.encryption.EncryptionConstants;
import io.cattle.platform.iaas.api.auth.identity.IdentityLinkKey;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import io.github.ibuildthecloud.gdapi.util.TransformationService;

import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmailRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(EmailRecoveryService.class);
    private static final String PRIMARY_EMAIL = "primary";
    private static final int MAX_ATTEMPTS = 5;
    private static final long MIN_RESEND_MILLIS = 60_000L;
    private static final int MAX_ACTIVE_CODES = 3;

    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    MfaDao mfaDao;
    @Inject
    MfaPolicyService policyService;
    @Inject
    MfaMailService mailService;
    @Inject
    TransformationService transformationService;
    @Inject
    LockManager lockManager;

    public Map<String, Object> beginEnrollment(final Account account, String requestedEmail) {
        requireAccount(account);
        final String email = mailService.validateAddress(requestedEmail);
        final String handle = randomCodeHandle();
        final String key = IdentityLinkKey.create("mfa-email-enrollment", "code", handle);
        return lockManager.lock(new MfaCredentialLock(
                CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE, "account-" + account.getId()),
                new LockCallback<Map<String, Object>>() {
                    @Override
                    public Map<String, Object> doWithLock() {
                        enforceSendRate(account);
                        String code = sixDigitCode();
                        mailService.send(email, "PastureStack recovery email verification",
                                securityCodeBody(code, policyService.getSmtpConfiguration()
                                        .getCodeTtlSeconds()));
                        Map<String, Object> data = codeData("enrollment", email);
                        mfaDao.create(account.getId(),
                                CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE, key,
                                transformationService.transform(code, EncryptionConstants.HASH), data);
                        Map<String, Object> result = new HashMap<>();
                        result.put("challengeId", handle);
                        result.put("recoveryEmailMasked", mask(email));
                        result.put("emailCodeSent", true);
                        result.put("expiresAt", new Date(longValue(data.get("expiresAt"), 0L)));
                        return result;
                    }
                });
    }

    public void confirmEnrollment(final Account account, String challengeId, final String code) {
        requireAccount(account);
        if (StringUtils.isBlank(challengeId) || !isSixDigits(code)) {
            throw invalidCode();
        }
        final String key = IdentityLinkKey.create("mfa-email-enrollment", "code", challengeId);
        lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE, key),
                new LockCallback<Object>() {
                    @Override
                    public Object doWithLock() {
                        Credential challenge = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE, key);
                        if (!validChallenge(challenge, account, "enrollment")) {
                            throw invalidCode();
                        }
                        Map<String, Object> data = mutableData(challenge);
                        if (!transformationService.compare(code, challenge.getSecretValue())) {
                            recordFailure(challenge, data);
                            throw invalidCode();
                        }
                        String email = transformationService.untransform(
                                string(data.get("encryptedEmail")));
                        for (Credential existing : mfaDao.listActive(account.getId(),
                                CredentialConstants.KIND_MFA_RECOVERY_EMAIL)) {
                            mfaDao.deactivate(existing, "replaced");
                        }
                        Map<String, Object> verified = new HashMap<>();
                        verified.put("maskedEmail", mask(email));
                        verified.put("verifiedAt", new Date());
                        mfaDao.create(account.getId(), CredentialConstants.KIND_MFA_RECOVERY_EMAIL,
                                PRIMARY_EMAIL,
                                transformationService.transform(email, EncryptionConstants.ENCRYPT),
                                verified);
                        mfaDao.deactivate(challenge, "completed");
                        sendBestEffortNotice(account, email,
                                "PastureStack recovery email enabled",
                                "This address was verified as the recovery email for a PastureStack account. "
                                        + "If you did not make this change, contact a system administrator.");
                        return null;
                    }
                });
    }

    public String sendLoginRecoveryCode(final Account account, final String loginChallengeKey) {
        requireAccount(account);
        final String email = verifiedEmail(account);
        if (email == null) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "RecoveryEmailUnavailable",
                    "No verified recovery email is available.", null);
        }
        final String key = loginCodeKey(loginChallengeKey);
        return lockManager.lock(new MfaCredentialLock(
                CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE, key),
                new LockCallback<String>() {
                    @Override
                    public String doWithLock() {
                        Credential previous = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE, key);
                        if (previous != null && previous.getData() != null
                                && longValue(previous.getData().get("createdAtMillis"), 0L)
                                > System.currentTimeMillis() - MIN_RESEND_MILLIS) {
                            throw new ClientVisibleException(ResponseCodes.TOO_MANY_REQUESTS,
                                    "EmailCodeRateLimited",
                                    "Wait before requesting another security code.", null);
                        }
                        enforceSendRate(account);
                        String code = sixDigitCode();
                        mailService.send(email, "PastureStack account recovery code",
                                securityCodeBody(code, policyService.getSmtpConfiguration()
                                        .getCodeTtlSeconds())
                                        + "\n\nUsing this code revokes existing MFA factors and requires re-enrollment.");
                        if (previous != null) {
                            mfaDao.deactivate(previous, "replaced");
                        }
                        mfaDao.create(account.getId(),
                                CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE, key,
                                transformationService.transform(code, EncryptionConstants.HASH),
                                codeData("loginRecovery", email));
                        return mask(email);
                    }
                });
    }

    public void verifyLoginRecoveryCode(final Account account, final String loginChallengeKey,
                                        final String code) {
        requireAccount(account);
        if (!isSixDigits(code)) {
            throw invalidCode();
        }
        final String key = loginCodeKey(loginChallengeKey);
        lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE, key),
                new LockCallback<Object>() {
                    @Override
                    public Object doWithLock() {
                        Credential challenge = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE, key);
                        if (!validChallenge(challenge, account, "loginRecovery")) {
                            throw invalidCode();
                        }
                        Map<String, Object> data = mutableData(challenge);
                        if (!transformationService.compare(code, challenge.getSecretValue())) {
                            recordFailure(challenge, data);
                            throw invalidCode();
                        }
                        mfaDao.deactivate(challenge, "completed");
                        return null;
                    }
                });
    }

    public boolean isAvailable(Account account) {
        if (account == null || !policyService.getSmtpConfiguration().isEnabled()) {
            return false;
        }
        return verifiedEmailCredential(account) != null;
    }

    public String maskedEmail(Account account) {
        Credential credential = verifiedEmailCredential(account);
        if (credential == null || credential.getData() == null) {
            return null;
        }
        return string(credential.getData().get("maskedEmail"));
    }

    public void sendRecoveryNotice(Account account) {
        String email = verifiedEmail(account);
        if (email != null) {
            sendBestEffortNotice(account, email,
                    "PastureStack account recovery completed",
                    "Email recovery was completed. Existing MFA factors and active sessions were revoked. "
                            + "Re-enroll trusted factors immediately.");
        }
    }

    public void sendSecurityChangeNotice(Account account, String change) {
        String email = verifiedEmail(account);
        if (email == null) {
            return;
        }
        sendBestEffortNotice(account, email,
                "PastureStack authentication settings changed",
                StringUtils.defaultIfBlank(change,
                        "An authentication or account-recovery setting was changed.")
                        + "\n\nIf you did not expect this change, contact a system administrator "
                        + "and revoke active sessions.");
    }

    private void sendBestEffortNotice(Account account, String email, String subject, String body) {
        try {
            mailService.send(email, subject, body);
        } catch (RuntimeException e) {
            // A notification failure must not strand a recovered account or
            // make an emergency factor revocation impossible. The security
            // operation remains in the audit log; no address or secret is
            // included here.
            log.warn("Unable to deliver an authentication security notice for account {}",
                    account == null ? null : account.getId());
        }
    }

    private Map<String, Object> codeData(String purpose, String email) {
        long now = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("purpose", purpose);
        data.put("createdAt", new Date(now));
        data.put("createdAtMillis", now);
        data.put("expiresAt", now + policyService.getSmtpConfiguration().getCodeTtlSeconds() * 1000L);
        data.put("attempts", 0);
        data.put("encryptedEmail",
                transformationService.transform(email, EncryptionConstants.ENCRYPT));
        return data;
    }

    private void enforceSendRate(Account account) {
        long now = System.currentTimeMillis();
        int active = 0;
        for (Credential credential : mfaDao.listActive(account.getId(),
                CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE)) {
            if (credential.getData() == null
                    || longValue(credential.getData().get("expiresAt"), 0L) <= now) {
                mfaDao.deactivate(credential, "expired");
                continue;
            }
            active++;
            if (longValue(credential.getData().get("createdAtMillis"), 0L)
                    > now - MIN_RESEND_MILLIS) {
                throw new ClientVisibleException(ResponseCodes.TOO_MANY_REQUESTS,
                        "EmailCodeRateLimited",
                        "Wait before requesting another security code.", null);
            }
        }
        if (active >= MAX_ACTIVE_CODES) {
            throw new ClientVisibleException(ResponseCodes.TOO_MANY_REQUESTS,
                    "EmailCodeRateLimited",
                    "Too many active email security codes exist. Wait for them to expire.", null);
        }
    }

    private boolean validChallenge(Credential credential, Account account, String purpose) {
        return credential != null && account.getId().equals(credential.getAccountId())
                && credential.getData() != null
                && purpose.equals(string(credential.getData().get("purpose")))
                && longValue(credential.getData().get("expiresAt"), 0L) > System.currentTimeMillis()
                && integer(credential.getData().get("attempts"), 0) < MAX_ATTEMPTS;
    }

    private void recordFailure(Credential credential, Map<String, Object> data) {
        int attempts = integer(data.get("attempts"), 0) + 1;
        data.put("attempts", attempts);
        data.put("lastFailureAt", new Date());
        if (attempts >= MAX_ATTEMPTS) {
            mfaDao.deactivate(credential, "attemptLimit");
        } else {
            mfaDao.save(credential, null, data);
        }
    }

    private Credential verifiedEmailCredential(Account account) {
        List<? extends Credential> credentials =
                mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_RECOVERY_EMAIL);
        return credentials.isEmpty() ? null : credentials.get(0);
    }

    private String verifiedEmail(Account account) {
        Credential credential = verifiedEmailCredential(account);
        return credential == null ? null
                : transformationService.untransform(credential.getSecretValue());
    }

    private String loginCodeKey(String loginChallengeKey) {
        return IdentityLinkKey.create("mfa-email-login", "challenge", loginChallengeKey);
    }

    private String randomCodeHandle() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sixDigitCode() {
        return String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
    }

    private boolean isSixDigits(String value) {
        return value != null && value.matches("\\d{6}");
    }

    private String securityCodeBody(String code, int ttlSeconds) {
        return "Your PastureStack security code is " + code + ". It expires in "
                + Math.max(1, ttlSeconds / 60) + " minutes and can be used once. "
                + "If you did not request it, do not share the code.";
    }

    private String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String visible = local.length() <= 2 ? local.substring(0, 1)
                : local.substring(0, 2);
        return visible + "***" + email.substring(at);
    }

    private void requireAccount(Account account) {
        if (account == null || account.getId() == null) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED);
        }
    }

    private Map<String, Object> mutableData(Credential credential) {
        return credential.getData() == null ? new HashMap<String, Object>()
                : new HashMap<>(credential.getData());
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private ClientVisibleException invalidCode() {
        return new ClientVisibleException(ResponseCodes.UNAUTHORIZED, "EmailSecurityCodeInvalid",
                "The email security code is invalid, expired, or already used.", null);
    }
}
