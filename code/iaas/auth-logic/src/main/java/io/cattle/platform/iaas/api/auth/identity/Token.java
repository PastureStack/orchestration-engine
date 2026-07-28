package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.AbstractTokenUtil;
import io.github.ibuildthecloud.gdapi.annotation.Field;
import io.github.ibuildthecloud.gdapi.annotation.Type;

import java.util.List;
import java.util.Map;

import io.github.ibuildthecloud.gdapi.model.FieldType;

@Type(name = AbstractTokenUtil.TOKEN)
public class Token {

    private  String jwt;
    private String code;
    private  String user;
    private Long authenticatedAsAccountId;
    private  Boolean security = SecurityConstants.SECURITY.get();
    private  String userType;
    private  String authProvider = SecurityConstants.AUTH_PROVIDER.get();
    private String loginMethod;
    private Boolean localRecoveryEnabled;
    private String originalLogin;
    private Boolean mfaRequired;
    private Boolean mfaEnrollmentRequired;
    private String mfaChallengeId;
    private List<String> mfaMethods;
    private String mfaMethod;
    private String mfaCode;
    private String webAuthnResponse;
    private String recoveryCode;
    private String emailCode;
    private String providerSwitchCode;
    private Map<String, Object> webAuthnOptions;
    private String totpProvisioningUri;
    private String totpSecret;
    private List<String> recoveryCodes;
    private String recoveryEmailMasked;
    private Boolean emailCodeSent;

    private  String accountId;
    private  Identity userIdentity;
    private  boolean enabled = security;
    private  List<Identity> identities;
    private  String redirectUrl;

    public Token(String jwt, String accountId, Identity userIdentity, List<Identity> identities, String userType, Long authenticatedAsAccountId) {
        this.jwt = jwt;
        this.userIdentity = userIdentity;
        this.accountId = accountId;
        this.identities = identities;
        this.user = userIdentity.getLogin();
        this.userType = userType;
        this.setAuthenticatedAsAccountId(authenticatedAsAccountId);
    }

    public Token() {
    }

    @Field(nullable = true)
    public String getJwt() {
        return jwt;
    }

    @Field(nullable = true)
    public void setCode(String code) {
        this.code = code;
    }

    @Field(nullable = true)
    public String getUser() {
        return user;
    }

    @Field(nullable = true)
    public Boolean getSecurity() {
        return security;
    }

    @Field(nullable = true)
    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    @Field(nullable = true)
    public String getAccountId() {
        return accountId;
    }

    @Field(nullable = true, required = true)
    public String getCode() {
        return code;
    }

    @Field(nullable = true)
    public Identity getUserIdentity() {
        return userIdentity;
    }

    public void setUserIdentity(Identity user) {
        this.userIdentity = user;
    }

    @Field(nullable = true)
    public boolean isEnabled() {
        return enabled;
    }

    @Field(nullable = true)
    public Identity[] getIdentities() {
        return identities.toArray(new Identity[identities.size()]);
    }

    public void setIdentities(List<Identity> identities) {
        this.identities = identities;
    }

    @Field(nullable = true)
    public String getAuthProvider() {
        return authProvider;
    }

    public void setAuthProvider(String authProvider) {
        this.authProvider = authProvider;
    }

    @Field(nullable = true)
    public String getLoginMethod() {
        return loginMethod;
    }

    public void setLoginMethod(String loginMethod) {
        this.loginMethod = loginMethod;
    }

    @Field(nullable = true)
    public Boolean getLocalRecoveryEnabled() {
        return localRecoveryEnabled;
    }

    public void setLocalRecoveryEnabled(Boolean localRecoveryEnabled) {
        this.localRecoveryEnabled = localRecoveryEnabled;
    }

    public void setJwt(String jwt) {
        this.jwt = jwt;
    }

    @Field(nullable = true)
    public String getRedirectUrl() {
        return redirectUrl;
    }

    public void setRedirectUrl(String redirectUrl) {
        this.redirectUrl = redirectUrl;
    }

    public Long getAuthenticatedAsAccountId() {
        return authenticatedAsAccountId;
    }

    public void setAuthenticatedAsAccountId(Long authenticatedAsAccountId) {
        this.authenticatedAsAccountId = authenticatedAsAccountId;
    }

    public String getOriginalLogin() {
        return originalLogin;
    }

    public void setOriginalLogin(String originalLogin) {
        this.originalLogin = originalLogin;
    }

    @Field(nullable = true)
    public Boolean getMfaRequired() {
        return mfaRequired;
    }

    public void setMfaRequired(Boolean mfaRequired) {
        this.mfaRequired = mfaRequired;
    }

    @Field(nullable = true)
    public Boolean getMfaEnrollmentRequired() {
        return mfaEnrollmentRequired;
    }

    public void setMfaEnrollmentRequired(Boolean mfaEnrollmentRequired) {
        this.mfaEnrollmentRequired = mfaEnrollmentRequired;
    }

    @Field(nullable = true)
    public String getMfaChallengeId() {
        return mfaChallengeId;
    }

    public void setMfaChallengeId(String mfaChallengeId) {
        this.mfaChallengeId = mfaChallengeId;
    }

    @Field(nullable = true)
    public List<String> getMfaMethods() {
        return mfaMethods;
    }

    public void setMfaMethods(List<String> mfaMethods) {
        this.mfaMethods = mfaMethods;
    }

    @Field(create = true, nullable = true)
    public String getMfaMethod() {
        return mfaMethod;
    }

    public void setMfaMethod(String mfaMethod) {
        this.mfaMethod = mfaMethod;
    }

    @Field(create = true, nullable = true, type = FieldType.PASSWORD)
    public String getMfaCode() {
        return mfaCode;
    }

    public void setMfaCode(String mfaCode) {
        this.mfaCode = mfaCode;
    }

    @Field(create = true, nullable = true, type = FieldType.PASSWORD)
    public String getWebAuthnResponse() {
        return webAuthnResponse;
    }

    public void setWebAuthnResponse(String webAuthnResponse) {
        this.webAuthnResponse = webAuthnResponse;
    }

    @Field(create = true, nullable = true, type = FieldType.PASSWORD)
    public String getRecoveryCode() {
        return recoveryCode;
    }

    public void setRecoveryCode(String recoveryCode) {
        this.recoveryCode = recoveryCode;
    }

    @Field(create = true, nullable = true, type = FieldType.PASSWORD)
    public String getEmailCode() {
        return emailCode;
    }

    public void setEmailCode(String emailCode) {
        this.emailCode = emailCode;
    }

    @Field(create = true, nullable = true, type = FieldType.PASSWORD)
    public String getProviderSwitchCode() {
        return providerSwitchCode;
    }

    public void setProviderSwitchCode(String providerSwitchCode) {
        this.providerSwitchCode = providerSwitchCode;
    }

    @Field(nullable = true)
    public Map<String, Object> getWebAuthnOptions() {
        return webAuthnOptions;
    }

    public void setWebAuthnOptions(Map<String, Object> webAuthnOptions) {
        this.webAuthnOptions = webAuthnOptions;
    }

    @Field(nullable = true)
    public String getTotpProvisioningUri() {
        return totpProvisioningUri;
    }

    public void setTotpProvisioningUri(String totpProvisioningUri) {
        this.totpProvisioningUri = totpProvisioningUri;
    }

    @Field(nullable = true, type = FieldType.PASSWORD)
    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    @Field(nullable = true)
    public List<String> getRecoveryCodes() {
        return recoveryCodes;
    }

    public void setRecoveryCodes(List<String> recoveryCodes) {
        this.recoveryCodes = recoveryCodes;
    }

    @Field(nullable = true)
    public String getRecoveryEmailMasked() {
        return recoveryEmailMasked;
    }

    public void setRecoveryEmailMasked(String recoveryEmailMasked) {
        this.recoveryEmailMasked = recoveryEmailMasked;
    }

    @Field(nullable = true)
    public Boolean getEmailCodeSent() {
        return emailCodeSent;
    }

    public void setEmailCodeSent(Boolean emailCodeSent) {
        this.emailCodeSent = emailCodeSent;
    }
}
