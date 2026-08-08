package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Credential;
import io.github.ibuildthecloud.gdapi.util.TransformationService;

import java.util.Map;

import jakarta.inject.Inject;

public class MfaPolicyService {

    static final String GLOBAL_CONFIG_KEY = "global";

    private static final ConfigProperty<String> DEFAULT_ENFORCEMENT =
            ArchaiusUtil.getStringProperty("api.auth.mfa.enforcement");
    private static final ConfigProperty<Integer> DEFAULT_PASSKEY_LIMIT =
            ArchaiusUtil.getIntProperty("api.auth.mfa.passkey.limit");
    private static final ConfigProperty<String> DEFAULT_RP_ID =
            ArchaiusUtil.getStringProperty("api.auth.mfa.webauthn.rp.id");
    private static final ConfigProperty<String> DEFAULT_ORIGIN =
            ArchaiusUtil.getStringProperty("api.auth.mfa.webauthn.origin");
    private static final ConfigProperty<String> DEFAULT_RP_NAME =
            ArchaiusUtil.getStringProperty("api.auth.mfa.webauthn.rp.name");
    private static final ConfigProperty<String> DEFAULT_ISSUER =
            ArchaiusUtil.getStringProperty("api.auth.mfa.totp.issuer");
    private static final ConfigProperty<Integer> DEFAULT_MAXIMUM_FAILED_ATTEMPTS =
            ArchaiusUtil.getIntProperty("api.auth.mfa.maximum.failed.attempts");
    private static final ConfigProperty<Integer> DEFAULT_LOCKOUT_SECONDS =
            ArchaiusUtil.getIntProperty("api.auth.mfa.lockout.seconds");
    private static final ConfigProperty<Integer> DEFAULT_SECURITY_CONFIRMATION_TTL_SECONDS =
            ArchaiusUtil.getIntProperty("api.auth.mfa.security.confirmation.ttl.seconds");
    private static final ConfigProperty<String> DEFAULT_FEDERATED_MFA_MODE =
            ArchaiusUtil.getStringProperty("api.auth.mfa.federated.mode");
    private static final ConfigProperty<String> DEFAULT_TRUSTED_AMR =
            ArchaiusUtil.getStringProperty("api.auth.mfa.federated.trusted.amr");
    private static final ConfigProperty<String> DEFAULT_TRUSTED_ACR =
            ArchaiusUtil.getStringProperty("api.auth.mfa.federated.trusted.acr");
    private static final ConfigProperty<Integer> DEFAULT_FEDERATED_AUTH_AGE_SECONDS =
            ArchaiusUtil.getIntProperty("api.auth.mfa.federated.maximum.age.seconds");
    private static final ConfigProperty<String> DEFAULT_PASSKEY_COUNTER_POLICY =
            ArchaiusUtil.getStringProperty("api.auth.mfa.passkey.counter.policy");
    private static final ConfigProperty<String> DEFAULT_SECURITY_EMAIL_LOCALE =
            ArchaiusUtil.getStringProperty("api.auth.mfa.security.email.locale");

    @Inject
    MfaDao mfaDao;
    @Inject
    TransformationService transformationService;

    public MfaPolicy getPolicy() {
        Credential config = mfaDao.findActive(CredentialConstants.KIND_MFA_SYSTEM_CONFIG, GLOBAL_CONFIG_KEY);
        Map<String, Object> data = config == null ? null : config.getData();
        return new MfaPolicy(
                string(data, "enforcement", DEFAULT_ENFORCEMENT.get()),
                integer(data, "passkeyLimit", defaultPasskeyLimit()),
                string(data, "relyingPartyId", DEFAULT_RP_ID.get()),
                string(data, "origin", DEFAULT_ORIGIN.get()),
                string(data, "relyingPartyName", DEFAULT_RP_NAME.get()),
                string(data, "issuer", DEFAULT_ISSUER.get()),
                integer(data, "maximumFailedAttempts", defaultInteger(DEFAULT_MAXIMUM_FAILED_ATTEMPTS, 10)),
                integer(data, "lockoutSeconds", defaultInteger(DEFAULT_LOCKOUT_SECONDS, 300)),
                integer(data, "securityConfirmationTtlSeconds",
                        defaultInteger(DEFAULT_SECURITY_CONFIRMATION_TTL_SECONDS, 300)),
                string(data, "federatedMfaMode", DEFAULT_FEDERATED_MFA_MODE.get()),
                string(data, "trustedAuthenticationMethods", DEFAULT_TRUSTED_AMR.get()),
                string(data, "trustedAuthenticationContexts", DEFAULT_TRUSTED_ACR.get()),
                integer(data, "maximumFederatedAuthenticationAgeSeconds",
                        defaultInteger(DEFAULT_FEDERATED_AUTH_AGE_SECONDS, 300)),
                string(data, "passkeyCounterPolicy", DEFAULT_PASSKEY_COUNTER_POLICY.get()),
                string(data, "securityEmailLocale", DEFAULT_SECURITY_EMAIL_LOCALE.get()));
    }

    Credential getConfigCredential() {
        return mfaDao.findActive(CredentialConstants.KIND_MFA_SYSTEM_CONFIG, GLOBAL_CONFIG_KEY);
    }

    public SmtpConfiguration getSmtpConfiguration() {
        Credential config = getConfigCredential();
        Map<String, Object> data = config == null ? null : config.getData();
        String password = config == null || config.getSecretValue() == null
                ? null : transformationService.untransform(config.getSecretValue());
        return new SmtpConfiguration(
                bool(data, "smtpEnabled", false),
                string(data, "smtpHost", null),
                integer(data, "smtpPort", 587),
                string(data, "smtpUsername", null),
                password,
                string(data, "smtpFrom", null),
                bool(data, "smtpStartTls", true),
                bool(data, "smtpSsl", false),
                clamp(integer(data, "smtpConnectionTimeoutMillis", 5000), 1000, 30000),
                clamp(integer(data, "smtpReadTimeoutMillis", 10000), 1000, 30000),
                clamp(integer(data, "emailCodeTtlSeconds", 600), 60, 600));
    }

    private int defaultPasskeyLimit() {
        Integer value = DEFAULT_PASSKEY_LIMIT.get();
        return value == null ? 5 : value.intValue();
    }

    private int defaultInteger(ConfigProperty<Integer> property, int fallback) {
        Integer value = property.get();
        return value == null ? fallback : value.intValue();
    }

    private String string(Map<String, Object> data, String key, String fallback) {
        if (data == null || data.get(key) == null) {
            return fallback;
        }
        return String.valueOf(data.get(key));
    }

    private int integer(Map<String, Object> data, String key, int fallback) {
        if (data == null || data.get(key) == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(data.get(key)));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private boolean bool(Map<String, Object> data, String key, boolean fallback) {
        if (data == null || data.get(key) == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(data.get(key)));
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
