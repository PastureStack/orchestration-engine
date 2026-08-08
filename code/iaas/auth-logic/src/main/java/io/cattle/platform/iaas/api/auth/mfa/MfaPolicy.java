package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.Account;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

public class MfaPolicy {

    public static final String OPTIONAL = "optional";
    public static final String REQUIRED_ADMINS = "requiredAdmins";
    public static final String REQUIRED_ALL = "requiredAll";
    public static final String FEDERATED_MFA_PLATFORM = "platform";
    public static final String FEDERATED_MFA_TRUSTED_CLAIMS = "trustedClaims";
    public static final String PASSKEY_COUNTER_RISK_AWARE = "riskAware";
    public static final String PASSKEY_COUNTER_STRICT = "strict";

    private final String enforcement;
    private final int passkeyLimit;
    private final String relyingPartyId;
    private final String origin;
    private final String relyingPartyName;
    private final String issuer;
    private final int maximumFailedAttempts;
    private final int lockoutSeconds;
    private final int securityConfirmationTtlSeconds;
    private final String federatedMfaMode;
    private final List<String> trustedAuthenticationMethods;
    private final List<String> trustedAuthenticationContexts;
    private final int maximumFederatedAuthenticationAgeSeconds;
    private final String passkeyCounterPolicy;
    private final String securityEmailLocale;

    public MfaPolicy(String enforcement, int passkeyLimit, String relyingPartyId, String origin,
                     String relyingPartyName, String issuer) {
        this(enforcement, passkeyLimit, relyingPartyId, origin, relyingPartyName, issuer,
                10, 300, 300, FEDERATED_MFA_PLATFORM, "mfa,otp,hwk,webauthn",
                "", 300, PASSKEY_COUNTER_RISK_AWARE, "zh-tw");
    }

    public MfaPolicy(String enforcement, int passkeyLimit, String relyingPartyId, String origin,
                     String relyingPartyName, String issuer, int maximumFailedAttempts,
                     int lockoutSeconds, int securityConfirmationTtlSeconds,
                     String federatedMfaMode, String trustedAuthenticationMethods,
                     String trustedAuthenticationContexts,
                     int maximumFederatedAuthenticationAgeSeconds, String passkeyCounterPolicy,
                     String securityEmailLocale) {
        this.enforcement = normalizeEnforcement(enforcement);
        this.passkeyLimit = Math.max(1, Math.min(20, passkeyLimit));
        this.relyingPartyId = StringUtils.trimToNull(relyingPartyId);
        this.origin = StringUtils.trimToNull(origin);
        this.relyingPartyName = StringUtils.defaultIfBlank(relyingPartyName, "PastureStack");
        this.issuer = StringUtils.defaultIfBlank(issuer, "PastureStack");
        this.maximumFailedAttempts = clamp(maximumFailedAttempts, 5, 50);
        this.lockoutSeconds = clamp(lockoutSeconds, 30, 3600);
        this.securityConfirmationTtlSeconds = clamp(securityConfirmationTtlSeconds, 60, 900);
        this.federatedMfaMode = FEDERATED_MFA_TRUSTED_CLAIMS.equals(federatedMfaMode)
                ? FEDERATED_MFA_TRUSTED_CLAIMS : FEDERATED_MFA_PLATFORM;
        this.trustedAuthenticationMethods = values(trustedAuthenticationMethods);
        this.trustedAuthenticationContexts = values(trustedAuthenticationContexts);
        this.maximumFederatedAuthenticationAgeSeconds =
                clamp(maximumFederatedAuthenticationAgeSeconds, 60, 3600);
        this.passkeyCounterPolicy = PASSKEY_COUNTER_STRICT.equals(passkeyCounterPolicy)
                ? PASSKEY_COUNTER_STRICT : PASSKEY_COUNTER_RISK_AWARE;
        this.securityEmailLocale = normalizeLocale(securityEmailLocale);
    }

    public boolean requiresEnrollment(Account account) {
        if (account == null || OPTIONAL.equals(enforcement)) {
            return false;
        }
        return REQUIRED_ALL.equals(enforcement)
                || (REQUIRED_ADMINS.equals(enforcement)
                && AccountConstants.ADMIN_KIND.equalsIgnoreCase(account.getKind()));
    }

    public String getEnforcement() {
        return enforcement;
    }

    public int getPasskeyLimit() {
        return passkeyLimit;
    }

    public String getRelyingPartyId() {
        return relyingPartyId;
    }

    public String getOrigin() {
        return origin;
    }

    public String getRelyingPartyName() {
        return relyingPartyName;
    }

    public String getIssuer() {
        return issuer;
    }

    public int getMaximumFailedAttempts() {
        return maximumFailedAttempts;
    }

    public int getLockoutSeconds() {
        return lockoutSeconds;
    }

    public int getSecurityConfirmationTtlSeconds() {
        return securityConfirmationTtlSeconds;
    }

    public String getFederatedMfaMode() {
        return federatedMfaMode;
    }

    public List<String> getTrustedAuthenticationMethods() {
        return trustedAuthenticationMethods;
    }

    public List<String> getTrustedAuthenticationContexts() {
        return trustedAuthenticationContexts;
    }

    public int getMaximumFederatedAuthenticationAgeSeconds() {
        return maximumFederatedAuthenticationAgeSeconds;
    }

    public String getPasskeyCounterPolicy() {
        return passkeyCounterPolicy;
    }

    public String getSecurityEmailLocale() {
        return securityEmailLocale;
    }

    public boolean trustsFederatedClaims() {
        return FEDERATED_MFA_TRUSTED_CLAIMS.equals(federatedMfaMode);
    }

    public boolean usesStrictPasskeyCounters() {
        return PASSKEY_COUNTER_STRICT.equals(passkeyCounterPolicy);
    }

    public boolean isWebAuthnConfigured() {
        return relyingPartyId != null && origin != null;
    }

    static String normalizeEnforcement(String value) {
        if (REQUIRED_ADMINS.equals(value) || REQUIRED_ALL.equals(value)) {
            return value;
        }
        return OPTIONAL;
    }

    private static List<String> values(String value) {
        if (StringUtils.isBlank(value)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String item : StringUtils.split(value, ',')) {
            String normalized = StringUtils.lowerCase(StringUtils.trim(item), Locale.ROOT);
            if (StringUtils.isNotBlank(normalized) && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String normalizeLocale(String value) {
        String normalized = StringUtils.lowerCase(
                StringUtils.defaultIfBlank(StringUtils.trim(value), "zh-tw"), Locale.ROOT);
        return "zh-tw".equals(normalized) ? "zh-tw" : "en-us";
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
