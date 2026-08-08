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

import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecord;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.google.common.net.InternetDomainName;

public class WebAuthnService {

    private static final int RANDOM_BYTES = 32;
    private static final int MAX_ATTEMPTS = 5;
    private static final long CHALLENGE_TTL_MILLIS = 5L * 60L * 1000L;
    private static final long CLIENT_TIMEOUT_MILLIS = 120_000L;
    private static final int MAX_RESPONSE_LENGTH = 131_072;

    private final SecureRandom secureRandom = new SecureRandom();
    private final ObjectConverter objectConverter = new ObjectConverter();
    private final AttestedCredentialDataConverter credentialDataConverter =
            new AttestedCredentialDataConverter(objectConverter);
    private final WebAuthnManager webAuthnManager =
            WebAuthnManager.createNonStrictWebAuthnManager(objectConverter);

    @Inject
    MfaDao mfaDao;
    @Inject
    MfaPolicyService policyService;
    @Inject
    LockManager lockManager;
    @Inject
    TransformationService transformationService;

    public Map<String, Object> beginRegistration(Account account, String label) {
        requireAccount(account);
        MfaPolicy policy = policyService.getPolicy();
        validateConfiguration(policy);

        List<? extends Credential> factors =
                mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_WEBAUTHN);
        if (factors.size() >= policy.getPasskeyLimit()) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "PasskeyLimitReached",
                    "The configured passkey limit has been reached.", null);
        }

        byte[] handle = randomBytes();
        byte[] challenge = randomBytes();
        byte[] userHandle = existingUserHandle(factors);
        if (userHandle == null) {
            userHandle = randomBytes();
        }
        String handleCode = encode(handle);
        String challengeKey = IdentityLinkKey.create("mfa-webauthn-enrollment", "code", handleCode);
        Map<String, Object> data = new HashMap<>();
        data.put("expiresAt", System.currentTimeMillis() + CHALLENGE_TTL_MILLIS);
        data.put("attempts", 0);
        data.put("origin", policy.getOrigin());
        data.put("relyingPartyId", policy.getRelyingPartyId());
        data.put("userHandle", encode(userHandle));
        data.put("label", safeLabel(label));
        mfaDao.create(account.getId(), CredentialConstants.KIND_MFA_ENROLLMENT_CHALLENGE,
                challengeKey, transformationService.transform(encode(challenge), EncryptionConstants.ENCRYPT), data);

        Map<String, Object> result = new HashMap<>();
        result.put("challengeId", handleCode);
        result.put("publicKey", creationOptions(account, policy, challenge, userHandle, factors));
        return result;
    }

    public Credential finishRegistration(final Account account, String challengeId,
                                         final String responseJson, final String requestedLabel) {
        requireAccount(account);
        if (StringUtils.isBlank(challengeId) || StringUtils.isBlank(responseJson)
                || responseJson.length() > MAX_RESPONSE_LENGTH) {
            throw invalidCeremony();
        }
        final String key = IdentityLinkKey.create("mfa-webauthn-enrollment", "code", challengeId);
        return lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_ENROLLMENT_CHALLENGE, key),
                new LockCallback<Credential>() {
                    @Override
                    public Credential doWithLock() {
                        Credential challengeCredential = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_ENROLLMENT_CHALLENGE, key);
                        if (challengeCredential == null
                                || !account.getId().equals(challengeCredential.getAccountId())
                                || isExpired(challengeCredential)) {
                            if (challengeCredential != null) {
                                mfaDao.deactivate(challengeCredential, "expired");
                            }
                            throw invalidCeremony();
                        }
                        Map<String, Object> challengeData = mutableData(challengeCredential);
                        enforceAttempts(challengeCredential, challengeData);
                        try {
                            String origin = string(challengeData.get("origin"));
                            String rpId = string(challengeData.get("relyingPartyId"));
                            byte[] challenge = decode(transformationService.untransform(
                                    challengeCredential.getSecretValue()));
                            ServerProperty serverProperty = serverProperty(origin, rpId, challenge);
                            RegistrationData registration = webAuthnManager.verifyRegistrationResponseJSON(
                                    responseJson,
                                    new RegistrationParameters(serverProperty, supportedAlgorithms(), true, true));
                            AttestedCredentialData attested = registration.getAttestationObject()
                                    .getAuthenticatorData().getAttestedCredentialData();
                            if (attested == null || attested.getCredentialId() == null) {
                                throw invalidCeremony();
                            }
                            String credentialId = encode(attested.getCredentialId());
                            final String factorId = credentialId;
                            Credential factor = lockManager.lock(
                                    new MfaCredentialLock(CredentialConstants.KIND_MFA_WEBAUTHN,
                                            "account-" + account.getId()),
                                    new LockCallback<Credential>() {
                                        @Override
                                        public Credential doWithLock() {
                                            return lockManager.lock(
                                                    new MfaCredentialLock(
                                                            CredentialConstants.KIND_MFA_WEBAUTHN, factorId),
                                                    new LockCallback<Credential>() {
                                                        @Override
                                                        public Credential doWithLock() {
                                                            Credential existing = mfaDao.findActive(
                                                                    CredentialConstants.KIND_MFA_WEBAUTHN,
                                                                    factorId);
                                                            if (existing != null) {
                                                                throw new ClientVisibleException(
                                                                        ResponseCodes.CONFLICT,
                                                                        "PasskeyAlreadyRegistered",
                                                                        "This passkey is already registered.",
                                                                        null);
                                                            }
                                                            MfaPolicy policy = policyService.getPolicy();
                                                            if (mfaDao.listActive(account.getId(),
                                                                    CredentialConstants.KIND_MFA_WEBAUTHN)
                                                                    .size() >= policy.getPasskeyLimit()) {
                                                                throw new ClientVisibleException(
                                                                        ResponseCodes.CONFLICT,
                                                                        "PasskeyLimitReached",
                                                                        "The configured passkey limit "
                                                                                + "has been reached.",
                                                                        null);
                                                            }
                                                            Map<String, Object> factorData = new HashMap<>();
                                                            factorData.put("label", safeLabel(
                                                                    StringUtils.defaultIfBlank(
                                                                            requestedLabel,
                                                                            string(challengeData.get(
                                                                                    "label")))));
                                                            factorData.put("createdAt", new Date());
                                                            factorData.put("lastUsedAt", null);
                                                            factorData.put("origin",
                                                                    string(challengeData.get("origin")));
                                                            factorData.put("relyingPartyId",
                                                                    string(challengeData.get(
                                                                            "relyingPartyId")));
                                                            factorData.put("userHandle",
                                                                    string(challengeData.get(
                                                                            "userHandle")));
                                                            factorData.put("counter",
                                                                    registration.getAttestationObject()
                                                                            .getAuthenticatorData()
                                                                            .getSignCount());
                                                            factorData.put("uvInitialized",
                                                                    registration.getAttestationObject()
                                                                            .getAuthenticatorData()
                                                                            .isFlagUV());
                                                            factorData.put("backupEligible",
                                                                    registration.getAttestationObject()
                                                                            .getAuthenticatorData()
                                                                            .isFlagBE());
                                                            factorData.put("backedUp",
                                                                    registration.getAttestationObject()
                                                                            .getAuthenticatorData()
                                                                            .isFlagBS());
                                                            factorData.put("transports",
                                                                    transportValues(
                                                                            registration.getTransports()));
                                                            String encodedData = encode(
                                                                    credentialDataConverter.convert(attested));
                                                            return mfaDao.create(account.getId(),
                                                                    CredentialConstants.KIND_MFA_WEBAUTHN,
                                                                    factorId,
                                                                    transformationService.transform(
                                                                            encodedData,
                                                                            EncryptionConstants.ENCRYPT),
                                                                    factorData);
                                                        }
                                                    });
                                        }
                                    });
                            mfaDao.deactivate(challengeCredential, "completed");
                            return factor;
                        } catch (ClientVisibleException e) {
                            throw e;
                        } catch (RuntimeException e) {
                            recordFailure(challengeCredential, challengeData);
                            throw invalidCeremony();
                        }
                    }
                });
    }

    public Map<String, Object> requestOptions(Account account, byte[] challenge) {
        requireAccount(account);
        MfaPolicy policy = policyService.getPolicy();
        validateConfiguration(policy);
        List<? extends Credential> factors =
                matchingFactors(account.getId(), policy.getOrigin(), policy.getRelyingPartyId());
        if (factors.isEmpty()) {
            return null;
        }
        Map<String, Object> options = new HashMap<>();
        options.put("challenge", encode(challenge));
        options.put("timeout", CLIENT_TIMEOUT_MILLIS);
        options.put("rpId", policy.getRelyingPartyId());
        options.put("allowCredentials", descriptors(factors));
        options.put("userVerification", "required");
        return options;
    }

    public Credential verifyAuthentication(final Account account, final byte[] challenge,
                                           final String responseJson) {
        requireAccount(account);
        if (challenge == null || challenge.length < RANDOM_BYTES
                || StringUtils.isBlank(responseJson) || responseJson.length() > MAX_RESPONSE_LENGTH) {
            throw invalidCeremony();
        }
        final AuthenticationData parsed;
        try {
            parsed = webAuthnManager.parseAuthenticationResponseJSON(responseJson);
        } catch (RuntimeException e) {
            throw invalidCeremony();
        }
        final String credentialId = encode(parsed.getCredentialId());
        return lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_WEBAUTHN, credentialId),
                new LockCallback<Credential>() {
                    @Override
                    public Credential doWithLock() {
                        Credential factor = mfaDao.findActive(
                                CredentialConstants.KIND_MFA_WEBAUTHN, credentialId);
                        if (factor == null || !account.getId().equals(factor.getAccountId())) {
                            throw invalidCeremony();
                        }
                        Map<String, Object> data = mutableData(factor);
                        String origin = string(data.get("origin"));
                        String rpId = string(data.get("relyingPartyId"));
                        try {
                            AttestedCredentialData attested = credentialDataConverter.convert(
                                    decode(transformationService.untransform(factor.getSecretValue())));
                            long previousCounter = longValue(data.get("counter"), 0L);
                            CredentialRecord credentialRecord = new CredentialRecordImpl(
                                    new NoneAttestationStatement(),
                                    nullableBoolean(data.get("uvInitialized")),
                                    nullableBoolean(data.get("backupEligible")),
                                    nullableBoolean(data.get("backedUp")),
                                    previousCounter,
                                    attested,
                                    null,
                                    null,
                                    null,
                                    transports(data.get("transports")));
                            List<byte[]> allowed = new ArrayList<>();
                            for (Credential candidate : matchingFactors(account.getId(), origin, rpId)) {
                                allowed.add(decode(candidate.getPublicValue()));
                            }
                            AuthenticationData verified = webAuthnManager.verifyAuthenticationResponseJSON(
                                    responseJson,
                                    new AuthenticationParameters(serverProperty(origin, rpId, challenge),
                                            credentialRecord, allowed, true, true));
                            long newCounter = verified.getAuthenticatorData().getSignCount();
                            if (previousCounter > 0 && newCounter > 0 && newCounter <= previousCounter) {
                                boolean backupEligible = verified.getAuthenticatorData().isFlagBE()
                                        || Boolean.TRUE.equals(nullableBoolean(
                                        data.get("backupEligible")));
                                if (policyService.getPolicy().usesStrictPasskeyCounters()
                                        || !backupEligible) {
                                    throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED,
                                            "PasskeyCounterInvalid",
                                            "The passkey counter did not advance; the credential may be cloned.",
                                            null);
                                }
                                data.put("counterAnomalyAt", new Date());
                                data.put("counterAnomalyCount",
                                        longValue(data.get("counterAnomalyCount"), 0L) + 1L);
                            }
                            data.put("counter", Math.max(previousCounter, newCounter));
                            data.put("backupEligible", verified.getAuthenticatorData().isFlagBE());
                            data.put("backedUp", verified.getAuthenticatorData().isFlagBS());
                            data.put("lastUsedAt", new Date());
                            return mfaDao.save(factor, null, data);
                        } catch (ClientVisibleException e) {
                            throw e;
                        } catch (RuntimeException e) {
                            throw invalidCeremony();
                        }
                    }
                });
    }

    public void validateConfiguration(MfaPolicy policy) {
        if (policy == null || !policy.isWebAuthnConfigured()) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "WebAuthnNotConfigured",
                    "Passkeys require a configured HTTPS origin and relying-party ID.", null);
        }
        try {
            URI origin = URI.create(policy.getOrigin());
            String scheme = StringUtils.lowerCase(origin.getScheme());
            String host = StringUtils.lowerCase(origin.getHost());
            String rpId = StringUtils.lowerCase(policy.getRelyingPartyId());
            boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
            boolean secure = "https".equals(scheme) || ("http".equals(scheme) && loopback);
            boolean cleanOrigin = origin.getUserInfo() == null && origin.getQuery() == null
                    && origin.getFragment() == null
                    && (StringUtils.isBlank(origin.getPath()) || "/".equals(origin.getPath()));
            boolean rpMatches = StringUtils.isNotBlank(host) && StringUtils.isNotBlank(rpId)
                    && (host.equals(rpId) || host.endsWith("." + rpId));
            boolean registrableRpId = loopback || (InternetDomainName.isValid(rpId)
                    && !InternetDomainName.from(rpId).isPublicSuffix());
            if (!secure || !cleanOrigin || !rpMatches || !registrableRpId
                    || rpId.contains(":") || rpId.contains("/")) {
                throw new IllegalArgumentException("Invalid WebAuthn origin");
            }
        } catch (RuntimeException e) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidWebAuthnConfiguration",
                    "The WebAuthn origin must be HTTPS (except localhost) and match the relying-party ID.",
                    null);
        }
    }

    private Map<String, Object> creationOptions(Account account, MfaPolicy policy, byte[] challenge,
                                                byte[] userHandle, List<? extends Credential> factors) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> rp = new HashMap<>();
        rp.put("id", policy.getRelyingPartyId());
        rp.put("name", policy.getRelyingPartyName());
        result.put("rp", rp);
        Map<String, Object> user = new HashMap<>();
        user.put("id", encode(userHandle));
        user.put("name", "account-" + account.getId());
        user.put("displayName", StringUtils.defaultIfBlank(account.getName(), "PastureStack account"));
        result.put("user", user);
        result.put("challenge", encode(challenge));
        result.put("pubKeyCredParams", algorithmMaps());
        result.put("timeout", CLIENT_TIMEOUT_MILLIS);
        result.put("excludeCredentials", descriptors(factors));
        Map<String, Object> selection = new HashMap<>();
        selection.put("residentKey", "preferred");
        selection.put("userVerification", "required");
        result.put("authenticatorSelection", selection);
        result.put("attestation", "none");
        result.put("hints", java.util.Arrays.asList("client-device", "security-key", "hybrid"));
        return result;
    }

    private List<Map<String, Object>> algorithmMaps() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(algorithm(-7));
        result.add(algorithm(-257));
        result.add(algorithm(-8));
        return result;
    }

    private Map<String, Object> algorithm(int id) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", "public-key");
        result.put("alg", id);
        return result;
    }

    private List<PublicKeyCredentialParameters> supportedAlgorithms() {
        return java.util.Arrays.asList(
                new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
                        COSEAlgorithmIdentifier.ES256),
                new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
                        COSEAlgorithmIdentifier.RS256),
                new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY,
                        COSEAlgorithmIdentifier.EdDSA));
    }

    private List<Map<String, Object>> descriptors(List<? extends Credential> factors) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Credential factor : factors) {
            Map<String, Object> descriptor = new HashMap<>();
            descriptor.put("type", "public-key");
            descriptor.put("id", factor.getPublicValue());
            Object value = factor.getData() == null ? null : factor.getData().get("transports");
            if (value instanceof List<?>) {
                descriptor.put("transports", value);
            }
            result.add(descriptor);
        }
        return result;
    }

    private List<? extends Credential> matchingFactors(long accountId, String origin, String rpId) {
        List<Credential> result = new ArrayList<>();
        for (Credential factor : mfaDao.listActive(accountId, CredentialConstants.KIND_MFA_WEBAUTHN)) {
            Map<String, Object> data = factor.getData();
            if (data != null && origin.equals(string(data.get("origin")))
                    && rpId.equals(string(data.get("relyingPartyId")))) {
                result.add(factor);
            }
        }
        return result;
    }

    private byte[] existingUserHandle(List<? extends Credential> factors) {
        for (Credential factor : factors) {
            if (factor.getData() != null && factor.getData().get("userHandle") != null) {
                try {
                    return decode(String.valueOf(factor.getData().get("userHandle")));
                } catch (RuntimeException ignored) {
                    // An invalid old factor is ignored and cannot weaken verification.
                }
            }
        }
        return null;
    }

    private Set<AuthenticatorTransport> transports(Object value) {
        if (!(value instanceof List<?>)) {
            return Collections.emptySet();
        }
        Set<AuthenticatorTransport> result = new HashSet<>();
        for (Object item : (List<?>) value) {
            if (item != null) {
                result.add(AuthenticatorTransport.create(String.valueOf(item)));
            }
        }
        return result;
    }

    private List<String> transportValues(Set<AuthenticatorTransport> transports) {
        if (transports == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (AuthenticatorTransport transport : transports) {
            result.add(transport.getValue());
        }
        return result;
    }

    private ServerProperty serverProperty(String origin, String rpId, byte[] challenge) {
        return ServerProperty.builder()
                .origin(new Origin(origin))
                .rpId(rpId)
                .challenge(new DefaultChallenge(challenge))
                .build();
    }

    private void enforceAttempts(Credential credential, Map<String, Object> data) {
        if (integer(data.get("attempts"), 0) >= MAX_ATTEMPTS) {
            mfaDao.deactivate(credential, "attemptLimit");
            throw invalidCeremony();
        }
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

    private boolean isExpired(Credential credential) {
        return credential.getData() == null
                || longValue(credential.getData().get("expiresAt"), 0L) <= System.currentTimeMillis();
    }

    private Map<String, Object> mutableData(Credential credential) {
        return credential.getData() == null
                ? new HashMap<String, Object>()
                : new HashMap<>(credential.getData());
    }

    private void requireAccount(Account account) {
        if (account == null || account.getId() == null) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED, "AccountRequired",
                    "An authenticated account is required.", null);
        }
    }

    private String safeLabel(String value) {
        String label = StringUtils.defaultIfBlank(StringUtils.trim(value), "Passkey");
        return label.length() <= 64 ? label : label.substring(0, 64);
    }

    private byte[] randomBytes() {
        byte[] value = new byte[RANDOM_BYTES];
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

    private Boolean nullableBoolean(Object value) {
        return value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private ClientVisibleException invalidCeremony() {
        return new ClientVisibleException(ResponseCodes.UNAUTHORIZED, "WebAuthnVerificationFailed",
                "The passkey response is invalid, expired, or already used.", null);
    }
}
