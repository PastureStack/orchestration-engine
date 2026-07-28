package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.Account;

import org.apache.commons.lang3.StringUtils;

public class MfaPolicy {

    public static final String OPTIONAL = "optional";
    public static final String REQUIRED_ADMINS = "requiredAdmins";
    public static final String REQUIRED_ALL = "requiredAll";

    private final String enforcement;
    private final int passkeyLimit;
    private final String relyingPartyId;
    private final String origin;
    private final String relyingPartyName;
    private final String issuer;

    public MfaPolicy(String enforcement, int passkeyLimit, String relyingPartyId, String origin,
                     String relyingPartyName, String issuer) {
        this.enforcement = normalizeEnforcement(enforcement);
        this.passkeyLimit = Math.max(1, Math.min(20, passkeyLimit));
        this.relyingPartyId = StringUtils.trimToNull(relyingPartyId);
        this.origin = StringUtils.trimToNull(origin);
        this.relyingPartyName = StringUtils.defaultIfBlank(relyingPartyName, "PastureStack");
        this.issuer = StringUtils.defaultIfBlank(issuer, "PastureStack");
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

    public boolean isWebAuthnConfigured() {
        return relyingPartyId != null && origin != null;
    }

    static String normalizeEnforcement(String value) {
        if (REQUIRED_ADMINS.equals(value) || REQUIRED_ALL.equals(value)) {
            return value;
        }
        return OPTIONAL;
    }
}
