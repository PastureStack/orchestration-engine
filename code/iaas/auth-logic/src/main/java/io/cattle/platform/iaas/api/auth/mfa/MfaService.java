package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.framework.encryption.EncryptionConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.iaas.api.auth.identity.IdentityLinkKey;
import io.cattle.platform.iaas.api.auth.identity.Token;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import io.github.ibuildthecloud.gdapi.util.TransformationService;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.lang3.StringUtils;

public class MfaService {

    public static final String PROVIDER = "mfa";
    public static final String METHOD_TOTP = "totp";
    public static final String METHOD_WEBAUTHN = "webauthn";
    public static final String METHOD_RECOVERY_CODE = "recoveryCode";
    public static final String METHOD_TOTP_ENROLLMENT = "totpEnrollment";
    public static final String METHOD_EMAIL_RECOVERY = "emailRecovery";

    private static final int RANDOM_BYTES = 32;
    private static final int RECOVERY_CODE_BYTES = 12;
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_ACTIVE_LOGIN_CHALLENGES = 5;
    private static final long CHALLENGE_TTL_MILLIS = 5L * 60L * 1000L;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base32 base32 = new Base32();

    @Inject
    MfaDao mfaDao;
    @Inject
    MfaPolicyService policyService;
    @Inject
    TotpService totpService;
    @Inject
    WebAuthnService webAuthnService;
    @Inject
    TransformationService transformationService;
    @Inject
    JsonMapper jsonMapper;
    @Inject
    LockManager lockManager;
    @Inject
    AuthDao authDao;
    @Inject
    AccountDao accountDao;
    @Inject
    AuthTokenDao authTokenDao;
    @Inject
    EmailRecoveryService emailRecoveryService;

    /**
     * Applies the MFA gate after primary authentication but before a session
     * record is issued. Provider-switch recovery and the account-and-identity
     * bound activation ceremony are deliberately exempt so a verified switch
     * cannot strand the last administrator mid-transition.
     */
    public Token beginLogin(Token primaryToken) {
        if (primaryToken == null || primaryToken.getAuthenticatedAsAccountId() == null
                || "providerSwitchRecovery".equals(primaryToken.getLoginMethod())
                || "providerSwitchActivation".equals(primaryToken.getLoginMethod())) {
            return primaryToken;
        }
        Account account = activeAccount(primaryToken.getAuthenticatedAsAccountId());
        List<? extends Credential> totpFactors =
                mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_TOTP);
        List<? extends Credential> webAuthnFactors =
                mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_WEBAUTHN);
        List<? extends Credential> recoveryCodes =
                mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_RECOVERY_CODE);
        MfaPolicy policy = policyService.getPolicy();
        boolean enrolled = !totpFactors.isEmpty() || !webAuthnFactors.isEmpty();
        if (!enrolled && !policy.requiresEnrollment(account)) {
            return primaryToken;
        }

        byte[] handle = randomBytes(RANDOM_BYTES);
        byte[] webAuthnChallenge = randomBytes(RANDOM_BYTES);
        String handleCode = encode(handle);
        String challengeKey = challengeKey(handleCode);
        List<String> methods = new ArrayList<>();
        String enrollmentSecret = null;
        if (!enrolled) {
            enrollmentSecret = totpService.generateSecret();
            methods.add(METHOD_TOTP_ENROLLMENT);
        } else {
            if (!totpFactors.isEmpty()) {
                methods.add(METHOD_TOTP);
            }
            if (!webAuthnFactors.isEmpty() && policy.isWebAuthnConfigured()) {
                Map<String, Object> options = webAuthnService.requestOptions(account, webAuthnChallenge);
                if (options != null) {
                    methods.add(METHOD_WEBAUTHN);
                }
            }
            if (!recoveryCodes.isEmpty()) {
                methods.add(METHOD_RECOVERY_CODE);
            }
            if (emailRecoveryService.isAvailable(account)) {
                methods.add(METHOD_EMAIL_RECOVERY);
            }
        }

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("token", tokenMap(primaryToken));
        envelope.put("webAuthnChallenge", encode(webAuthnChallenge));
        if (enrollmentSecret != null) {
            envelope.put("enrollmentSecret", enrollmentSecret);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("expiresAt", now() + CHALLENGE_TTL_MILLIS);
        data.put("attempts", 0);
        data.put("methods", new ArrayList<>(methods));
        data.put("createdAt", new Date());
        final long accountId = account.getId();
        final String protectedEnvelope = encryptJson(envelope);
        lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_LOGIN_CHALLENGE,
                "account-" + accountId), new LockCallback<Object>() {
                    @Override
                    public Object doWithLock() {
                        limitActiveLoginChallenges(accountId);
                        mfaDao.create(accountId, CredentialConstants.KIND_MFA_LOGIN_CHALLENGE,
                                challengeKey, protectedEnvelope, data);
                        return null;
                    }
                });

        Token challenge = new Token();
        challenge.setAuthProvider(primaryToken.getAuthProvider());
        challenge.setLoginMethod(primaryToken.getLoginMethod());
        challenge.setMfaRequired(true);
        challenge.setMfaEnrollmentRequired(!enrolled);
        challenge.setMfaChallengeId(handleCode);
        challenge.setMfaMethods(methods);
        if (methods.contains(METHOD_WEBAUTHN)) {
            challenge.setWebAuthnOptions(webAuthnService.requestOptions(account, webAuthnChallenge));
        }
        if (methods.contains(METHOD_EMAIL_RECOVERY)) {
            challenge.setRecoveryEmailMasked(emailRecoveryService.maskedEmail(account));
        }
        if (enrollmentSecret != null) {
            challenge.setTotpSecret(enrollmentSecret);
            challenge.setTotpProvisioningUri(totpService.provisioningUri(policy.getIssuer(),
                    StringUtils.defaultIfBlank(primaryToken.getOriginalLogin(), primaryToken.getUser()),
                    enrollmentSecret));
        }
        return challenge;
    }

    public Token completeLogin(Map<String, Object> requestBody) {
        final String challengeId = string(requestBody.get("code"));
        final String method = string(requestBody.get("mfaMethod"));
        final String mfaCode = string(requestBody.get("mfaCode"));
        final String webAuthnResponse = string(requestBody.get("webAuthnResponse"));
        final String recoveryCode = string(requestBody.get("recoveryCode"));
        final String emailCode = string(requestBody.get("emailCode"));
        if (StringUtils.isAnyBlank(challengeId, method)) {
            throw invalidChallenge();
        }
        final String key = challengeKey(challengeId);
        return lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_LOGIN_CHALLENGE, key),
                new LockCallback<Token>() {
                    @Override
                    public Token doWithLock() {
                        Credential challenge = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_LOGIN_CHALLENGE, key);
                        if (challenge == null || isExpired(challenge)) {
                            if (challenge != null) {
                                mfaDao.deactivate(challenge, "expired");
                            }
                            throw invalidChallenge();
                        }
                        Map<String, Object> data = mutableData(challenge);
                        if (integer(data.get("attempts"), 0) >= MAX_ATTEMPTS
                                || !methodAllowed(data, method)) {
                            mfaDao.deactivate(challenge, "attemptLimit");
                            throw invalidChallenge();
                        }
                        Map<String, Object> envelope = decryptJson(challenge.getSecretValue());
                        Token pending = tokenFromMap(map(envelope.get("token")));
                        Account account = activeAccount(challenge.getAccountId());
                        if (!account.getId().equals(pending.getAuthenticatedAsAccountId())) {
                            mfaDao.deactivate(challenge, "accountMismatch");
                            throw invalidChallenge();
                        }

                        if (METHOD_EMAIL_RECOVERY.equals(method) && StringUtils.isBlank(emailCode)) {
                            Token response = challengeResponse(challengeId, pending, data);
                            response.setRecoveryEmailMasked(
                                    emailRecoveryService.sendLoginRecoveryCode(account, key));
                            response.setEmailCodeSent(true);
                            return response;
                        }

                        List<String> newRecoveryCodes = null;
                        boolean recoveredByEmail = false;
                        try {
                            if (METHOD_TOTP.equals(method)) {
                                verifyTotp(account, mfaCode);
                            } else if (METHOD_WEBAUTHN.equals(method)) {
                                webAuthnService.verifyAuthentication(account,
                                        decode(string(envelope.get("webAuthnChallenge"))),
                                        webAuthnResponse);
                            } else if (METHOD_RECOVERY_CODE.equals(method)) {
                                consumeRecoveryCode(account, recoveryCode);
                            } else if (METHOD_TOTP_ENROLLMENT.equals(method)) {
                                verifyAndEnrollTotp(account,
                                        string(envelope.get("enrollmentSecret")), mfaCode,
                                        "Authenticator app");
                                newRecoveryCodes = regenerateRecoveryCodes(account);
                            } else if (METHOD_EMAIL_RECOVERY.equals(method)) {
                                emailRecoveryService.verifyLoginRecoveryCode(account, key, emailCode);
                                recoveredByEmail = true;
                            } else {
                                throw invalidChallenge();
                            }
                        } catch (ClientVisibleException e) {
                            recordFailure(challenge, data);
                            throw e;
                        } catch (RuntimeException e) {
                            recordFailure(challenge, data);
                            throw invalidChallenge();
                        }
                        mfaDao.deactivate(challenge, "completed");
                        if (recoveredByEmail) {
                            revokeAfterEmailRecovery(account);
                            pending.setLoginMethod(StringUtils.defaultIfBlank(
                                    pending.getLoginMethod(), "primary") + "+emailRecovery");
                            emailRecoveryService.sendRecoveryNotice(account);
                            return beginLogin(pending);
                        }
                        pending.setMfaRequired(false);
                        pending.setMfaEnrollmentRequired(false);
                        pending.setMfaChallengeId(null);
                        pending.setMfaMethods(null);
                        pending.setWebAuthnOptions(null);
                        pending.setTotpSecret(null);
                        pending.setTotpProvisioningUri(null);
                        pending.setRecoveryCodes(newRecoveryCodes);
                        pending.setLoginMethod(StringUtils.defaultIfBlank(pending.getLoginMethod(), "primary")
                                + "+mfa");
                        return pending;
                    }
                });
    }

    public List<String> regenerateRecoveryCodes(final Account account) {
        requireActiveAccount(account);
        return lockManager.lock(new MfaCredentialLock(
                CredentialConstants.KIND_MFA_RECOVERY_CODE, "account-" + account.getId()),
                new LockCallback<List<String>>() {
                    @Override
                    public List<String> doWithLock() {
                        for (Credential existing : mfaDao.listActive(account.getId(),
                                CredentialConstants.KIND_MFA_RECOVERY_CODE)) {
                            mfaDao.deactivate(existing, "regenerated");
                        }
                        List<String> plainCodes = new ArrayList<>();
                        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
                            String plain = recoveryCode();
                            String normalized = normalizeRecoveryCode(plain);
                            Map<String, Object> data = new HashMap<>();
                            data.put("createdAt", new Date());
                            data.put("position", i + 1);
                            mfaDao.create(account.getId(), CredentialConstants.KIND_MFA_RECOVERY_CODE,
                                    IdentityLinkKey.create("mfa-recovery", "id", encode(randomBytes(16))),
                                    transformationService.transform(normalized, EncryptionConstants.HASH),
                                    data);
                            plainCodes.add(plain);
                        }
                        return plainCodes;
                    }
                });
    }

    public Map<String, Object> beginTotpEnrollment(Account account) {
        requireActiveAccount(account);
        if (!mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_TOTP).isEmpty()) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "TotpAlreadyEnrolled",
                    "An authenticator app is already enrolled.", null);
        }
        String secret = totpService.generateSecret();
        String handle = encode(randomBytes(RANDOM_BYTES));
        String key = IdentityLinkKey.create("mfa-totp-enrollment", "code", handle);
        Map<String, Object> data = new HashMap<>();
        data.put("purpose", METHOD_TOTP_ENROLLMENT);
        data.put("expiresAt", now() + CHALLENGE_TTL_MILLIS);
        data.put("attempts", 0);
        data.put("createdAt", new Date());
        mfaDao.create(account.getId(), CredentialConstants.KIND_MFA_ENROLLMENT_CHALLENGE, key,
                transformationService.transform(secret, EncryptionConstants.ENCRYPT), data);

        MfaPolicy policy = policyService.getPolicy();
        Map<String, Object> result = new HashMap<>();
        result.put("challengeId", handle);
        result.put("totpSecret", secret);
        result.put("totpProvisioningUri", totpService.provisioningUri(policy.getIssuer(),
                StringUtils.defaultIfBlank(account.getName(), "account-" + account.getId()), secret));
        result.put("expiresAt", new Date(now() + CHALLENGE_TTL_MILLIS));
        return result;
    }

    public List<String> finishTotpEnrollment(final Account account, String challengeId,
                                             final String code, final String label) {
        requireActiveAccount(account);
        if (StringUtils.isBlank(challengeId)) {
            throw invalidChallenge();
        }
        final String key = IdentityLinkKey.create("mfa-totp-enrollment", "code", challengeId);
        return lockManager.lock(new MfaCredentialLock(
                CredentialConstants.KIND_MFA_ENROLLMENT_CHALLENGE, key),
                new LockCallback<List<String>>() {
                    @Override
                    public List<String> doWithLock() {
                        Credential challenge = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_ENROLLMENT_CHALLENGE, key);
                        if (challenge == null || !account.getId().equals(challenge.getAccountId())
                                || isExpired(challenge)
                                || !METHOD_TOTP_ENROLLMENT.equals(string(
                                challenge.getData() == null ? null : challenge.getData().get("purpose")))) {
                            if (challenge != null) {
                                mfaDao.deactivate(challenge, "expired");
                            }
                            throw invalidChallenge();
                        }
                        Map<String, Object> data = mutableData(challenge);
                        if (integer(data.get("attempts"), 0) >= MAX_ATTEMPTS) {
                            mfaDao.deactivate(challenge, "attemptLimit");
                            throw invalidChallenge();
                        }
                        try {
                            verifyAndEnrollTotp(account,
                                    transformationService.untransform(challenge.getSecretValue()),
                                    code, safeLabel(label, "Authenticator app"));
                        } catch (ClientVisibleException e) {
                            recordFailure(challenge, data);
                            throw e;
                        }
                        mfaDao.deactivate(challenge, "completed");
                        if (mfaDao.listActive(account.getId(),
                                CredentialConstants.KIND_MFA_RECOVERY_CODE).isEmpty()) {
                            return regenerateRecoveryCodes(account);
                        }
                        return Collections.emptyList();
                    }
                });
    }

    public boolean hasPrimaryFactor(long accountId) {
        return !mfaDao.listActive(accountId, CredentialConstants.KIND_MFA_TOTP,
                CredentialConstants.KIND_MFA_WEBAUTHN).isEmpty();
    }

    private void limitActiveLoginChallenges(long accountId) {
        List<? extends Credential> active = mfaDao.listActive(
                accountId, CredentialConstants.KIND_MFA_LOGIN_CHALLENGE);
        List<Credential> live = new ArrayList<>();
        for (Credential challenge : active) {
            if (isExpired(challenge)) {
                mfaDao.deactivate(challenge, "expired");
            } else {
                live.add(challenge);
            }
        }
        int excess = live.size() - MAX_ACTIVE_LOGIN_CHALLENGES + 1;
        for (int i = 0; i < excess; i++) {
            mfaDao.deactivate(live.get(i), "superseded");
        }
    }

    private void verifyTotp(final Account account, final String code) {
        List<? extends Credential> factors =
                mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_TOTP);
        if (factors.isEmpty()) {
            throw invalidChallenge();
        }
        final Credential selected = factors.get(0);
        lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_TOTP,
                selected.getPublicValue()), new LockCallback<Object>() {
                    @Override
                    public Object doWithLock() {
                        Credential factor = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_TOTP, selected.getPublicValue());
                        if (factor == null || !account.getId().equals(factor.getAccountId())) {
                            throw invalidChallenge();
                        }
                        Map<String, Object> data = mutableData(factor);
                        long lastStep = longValue(data.get("lastUsedStep"), -1L);
                        String secret = transformationService.untransform(factor.getSecretValue());
                        long matched = totpService.matchStep(secret, code, now() / 1000L, lastStep);
                        if (matched < 0) {
                            throw invalidChallenge();
                        }
                        data.put("lastUsedStep", matched);
                        data.put("lastUsedAt", new Date());
                        mfaDao.save(factor, null, data);
                        return null;
                    }
                });
    }

    private void verifyAndEnrollTotp(final Account account, final String secret, final String code,
                                     final String label) {
        if (StringUtils.isBlank(secret)) {
            throw invalidChallenge();
        }
        lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_TOTP,
                "account-" + account.getId()), new LockCallback<Object>() {
                    @Override
                    public Object doWithLock() {
                        if (!mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_TOTP).isEmpty()) {
                            throw new ClientVisibleException(ResponseCodes.CONFLICT, "TotpAlreadyEnrolled",
                                    "An authenticator app is already enrolled.", null);
                        }
                        long step = totpService.matchStep(secret, code, now() / 1000L, -1L);
                        if (step < 0) {
                            throw invalidChallenge();
                        }
                        Map<String, Object> data = new HashMap<>();
                        data.put("label", label);
                        data.put("createdAt", new Date());
                        data.put("lastUsedAt", new Date());
                        data.put("lastUsedStep", step);
                        mfaDao.create(account.getId(), CredentialConstants.KIND_MFA_TOTP,
                                IdentityLinkKey.create("mfa-totp", "id", encode(randomBytes(16))),
                                transformationService.transform(secret, EncryptionConstants.ENCRYPT), data);
                        return null;
                    }
                });
    }

    private void consumeRecoveryCode(final Account account, String code) {
        final String normalized = normalizeRecoveryCode(code);
        if (normalized.length() < 16) {
            throw invalidChallenge();
        }
        Credential matched = null;
        for (Credential candidate : mfaDao.listActive(account.getId(),
                CredentialConstants.KIND_MFA_RECOVERY_CODE)) {
            if (transformationService.compare(normalized, candidate.getSecretValue())) {
                matched = candidate;
            }
        }
        if (matched == null) {
            throw invalidChallenge();
        }
        final String publicValue = matched.getPublicValue();
        lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_RECOVERY_CODE, publicValue),
                new LockCallback<Object>() {
                    @Override
                    public Object doWithLock() {
                        Credential current = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_RECOVERY_CODE, publicValue);
                        if (current == null || !account.getId().equals(current.getAccountId())
                                || !transformationService.compare(normalized, current.getSecretValue())) {
                            throw invalidChallenge();
                        }
                        mfaDao.deactivate(current, "consumed");
                        return null;
                    }
                });
    }

    private void revokeAfterEmailRecovery(Account account) {
        for (Credential credential : mfaDao.listActive(account.getId(),
                CredentialConstants.KIND_MFA_TOTP,
                CredentialConstants.KIND_MFA_WEBAUTHN,
                CredentialConstants.KIND_MFA_RECOVERY_CODE,
                CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE)) {
            mfaDao.deactivate(credential, "emailRecovery");
        }
        authTokenDao.deleteTokensForAccount(account.getId());
    }

    private Token challengeResponse(String challengeId, Token pending, Map<String, Object> data) {
        Token response = new Token();
        response.setAuthProvider(pending.getAuthProvider());
        response.setLoginMethod(pending.getLoginMethod());
        response.setMfaRequired(true);
        response.setMfaChallengeId(challengeId);
        response.setMfaMethods(stringList(data.get("methods")));
        return response;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private Map<String, Object> tokenMap(Token token) {
        Map<String, Object> result = new HashMap<>();
        result.put("jwt", token.getJwt());
        result.put("accountId", token.getAccountId());
        result.put("userType", token.getUserType());
        result.put("authenticatedAsAccountId", token.getAuthenticatedAsAccountId());
        result.put("authProvider", token.getAuthProvider());
        result.put("loginMethod", token.getLoginMethod());
        result.put("originalLogin", token.getOriginalLogin());
        result.put("userIdentity", identityMap(token.getUserIdentity()));
        List<Map<String, Object>> identities = new ArrayList<>();
        if (token.getIdentities() != null) {
            for (Identity identity : token.getIdentities()) {
                identities.add(identityMap(identity));
            }
        }
        result.put("identities", identities);
        return result;
    }

    private Token tokenFromMap(Map<String, Object> value) {
        Identity user = identityFromMap(map(value.get("userIdentity")));
        List<Identity> identities = new ArrayList<>();
        Object raw = value.get("identities");
        if (raw instanceof List<?>) {
            for (Object item : (List<?>) raw) {
                identities.add(identityFromMap(map(item)));
            }
        }
        Token token = new Token(string(value.get("jwt")), string(value.get("accountId")),
                user, identities, string(value.get("userType")),
                longObject(value.get("authenticatedAsAccountId")));
        token.setAuthProvider(string(value.get("authProvider")));
        token.setLoginMethod(string(value.get("loginMethod")));
        token.setOriginalLogin(string(value.get("originalLogin")));
        return token;
    }

    private Map<String, Object> identityMap(Identity identity) {
        if (identity == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("externalIdType", identity.getExternalIdType());
        result.put("externalId", identity.getExternalId());
        result.put("name", identity.getName());
        result.put("profileUrl", identity.getProfileUrl());
        result.put("profilePicture", identity.getProfilePicture());
        result.put("login", identity.getLogin());
        result.put("role", identity.getRole());
        result.put("projectId", identity.getProjectId());
        result.put("user", identity.getUser());
        return result;
    }

    private Identity identityFromMap(Map<String, Object> value) {
        Identity base = new Identity(string(value.get("externalIdType")),
                string(value.get("externalId")), string(value.get("name")),
                string(value.get("profileUrl")), string(value.get("profilePicture")),
                string(value.get("login")), Boolean.parseBoolean(string(value.get("user"))));
        String role = string(value.get("role"));
        String projectId = string(value.get("projectId"));
        return role == null && projectId == null ? base : new Identity(base, role, projectId);
    }

    private String encryptJson(Map<String, Object> value) {
        try {
            return transformationService.transform(jsonMapper.writeValueAsString(value),
                    EncryptionConstants.ENCRYPT);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to protect the pending MFA login", e);
        }
    }

    private Map<String, Object> decryptJson(String value) {
        try {
            return jsonMapper.readValue(transformationService.untransform(value));
        } catch (IOException | RuntimeException e) {
            throw invalidChallenge();
        }
    }

    private Account activeAccount(Long id) {
        Account account = authDao.getAccountById(id);
        requireActiveAccount(account);
        return account;
    }

    private void requireActiveAccount(Account account) {
        if (account == null || !accountDao.isActiveAccount(account)) {
            throw invalidChallenge();
        }
    }

    private boolean methodAllowed(Map<String, Object> data, String method) {
        Object methods = data.get("methods");
        return methods instanceof List<?> && ((List<?>) methods).contains(method);
    }

    private void recordFailure(Credential challenge, Map<String, Object> data) {
        int attempts = integer(data.get("attempts"), 0) + 1;
        data.put("attempts", attempts);
        data.put("lastFailureAt", new Date());
        if (attempts >= MAX_ATTEMPTS) {
            mfaDao.deactivate(challenge, "attemptLimit");
        } else {
            mfaDao.save(challenge, null, data);
        }
    }

    private boolean isExpired(Credential challenge) {
        return challenge.getData() == null
                || longValue(challenge.getData().get("expiresAt"), 0L) <= now();
    }

    private Map<String, Object> mutableData(Credential credential) {
        return credential.getData() == null
                ? new HashMap<String, Object>()
                : new HashMap<>(credential.getData());
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw invalidChallenge();
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw invalidChallenge();
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private String recoveryCode() {
        String raw = base32.encodeToString(randomBytes(RECOVERY_CODE_BYTES))
                .replace("=", "").toUpperCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                result.append('-');
            }
            result.append(raw.charAt(i));
        }
        return result.toString();
    }

    private String normalizeRecoveryCode(String value) {
        return StringUtils.defaultString(value).replace("-", "")
                .replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private String safeLabel(String value, String fallback) {
        String result = StringUtils.defaultIfBlank(StringUtils.trim(value), fallback);
        return result.length() <= 64 ? result : result.substring(0, 64);
    }

    private String challengeKey(String code) {
        return IdentityLinkKey.create("mfa-login", "code", code);
    }

    private byte[] randomBytes(int size) {
        byte[] value = new byte[size];
        secureRandom.nextBytes(value);
        return value;
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longObject(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (RuntimeException e) {
            throw invalidChallenge();
        }
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

    private ClientVisibleException invalidChallenge() {
        return new ClientVisibleException(ResponseCodes.UNAUTHORIZED, "MfaVerificationFailed",
                "The verification response is invalid, expired, or already used.", null);
    }
}
