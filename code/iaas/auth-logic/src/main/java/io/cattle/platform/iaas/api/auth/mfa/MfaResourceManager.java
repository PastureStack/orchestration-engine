package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import io.github.ibuildthecloud.gdapi.util.RequestUtils;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;

public class MfaResourceManager extends AbstractNoOpResourceManager {

    public static final String STATUS_TYPE = "mfaStatus";
    public static final String FACTOR_TYPE = "mfaFactor";
    public static final String OPERATION_TYPE = "mfaOperation";

    private static final String BEGIN_TOTP = "beginTotpEnrollment";
    private static final String CONFIRM_TOTP = "confirmTotpEnrollment";
    private static final String BEGIN_WEBAUTHN = "beginPasskeyEnrollment";
    private static final String CONFIRM_WEBAUTHN = "confirmPasskeyEnrollment";
    private static final String REGENERATE_RECOVERY = "regenerateRecoveryCodes";
    private static final String BEGIN_RECOVERY_EMAIL = "beginRecoveryEmailEnrollment";
    private static final String CONFIRM_RECOVERY_EMAIL = "confirmRecoveryEmailEnrollment";
    private static final String REVOKE_RECOVERY_EMAIL = "revokeRecoveryEmail";
    private static final String REVOKE_FACTOR = "revokeFactor";
    private static final String REVOKE_ALL = "revokeAllFactors";
    private static final String BEGIN_SECURITY_CONFIRMATION = "beginSecurityConfirmation";
    private static final String CONFIRM_SECURITY_CONFIRMATION = "confirmSecurityConfirmation";

    @Inject
    MfaDao mfaDao;
    @Inject
    MfaService mfaService;
    @Inject
    MfaPolicyService policyService;
    @Inject
    WebAuthnService webAuthnService;
    @Inject
    EmailRecoveryService emailRecoveryService;
    @Inject
    AuthDao authDao;
    @Inject
    AccountDao accountDao;
    @Inject
    AuthTokenDao authTokenDao;
    @Inject
    ObjectManager objectManager;

    @Override
    public String[] getTypes() {
        return new String[] {STATUS_TYPE, FACTOR_TYPE, OPERATION_TYPE};
    }

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[] {MfaStatus.class, MfaFactor.class, MfaOperation.class};
    }

    @Override
    protected Object listInternal(SchemaFactory schemaFactory, String type, Map<Object, Object> criteria,
                                  ListOptions options) {
        Account actor = currentAccount();
        Account target = targetAccount(actor, criteria == null ? null
                : RequestUtils.makeSingularStringIfCan(criteria.get("accountId")));
        if (STATUS_TYPE.equalsIgnoreCase(type)) {
            return Collections.singletonList(status(target));
        }
        if (FACTOR_TYPE.equalsIgnoreCase(type)) {
            return factors(target);
        }
        return Collections.emptyList();
    }

    @Override
    protected Object createInternal(String type, ApiRequest request) {
        if (!OPERATION_TYPE.equalsIgnoreCase(type)) {
            return null;
        }
        Account actor = currentAccount();
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        String operation = string(input.get("operation"));
        if (BEGIN_SECURITY_CONFIRMATION.equals(operation)) {
            return result("securityConfirmationStarted",
                    mfaService.beginSecurityConfirmation(actor));
        }
        if (CONFIRM_SECURITY_CONFIRMATION.equals(operation)) {
            return result("securityConfirmed", mfaService.finishSecurityConfirmation(actor,
                    string(input.get("challengeId")), string(input.get("method")),
                    string(input.get("verificationCode")),
                    string(input.get("webAuthnResponse")),
                    string(input.get("recoveryCode"))));
        }
        Account target = targetAccount(actor, input.get("accountId"));
        if (requiresAccountHolder(operation)) {
            requireAccountHolder(actor, target);
        }
        requireSecurityConfirmation(actor, target, operation,
                string(input.get("securityConfirmation")));

        if (BEGIN_TOTP.equals(operation)) {
            return result("totpEnrollmentStarted", mfaService.beginTotpEnrollment(target));
        }
        if (CONFIRM_TOTP.equals(operation)) {
            List<String> recoveryCodes = mfaService.finishTotpEnrollment(target,
                    string(input.get("challengeId")), string(input.get("verificationCode")),
                    string(input.get("label")));
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", "totpEnrolled");
            fields.put("recoveryCodes", recoveryCodes);
            notifyChange(actor, target, "An authenticator app was registered.");
            return result("totpEnrolled", fields);
        }
        if (BEGIN_WEBAUTHN.equals(operation)) {
            return result("passkeyEnrollmentStarted",
                    webAuthnService.beginRegistration(target, string(input.get("label"))));
        }
        if (CONFIRM_WEBAUTHN.equals(operation)) {
            Credential factor = webAuthnService.finishRegistration(target,
                    string(input.get("challengeId")), string(input.get("webAuthnResponse")),
                    string(input.get("label")));
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", "passkeyEnrolled");
            fields.put("factor", factorResource(factor));
            if (mfaDao.listActive(target.getId(),
                    CredentialConstants.KIND_MFA_RECOVERY_CODE).isEmpty()) {
                fields.put("recoveryCodes", mfaService.regenerateRecoveryCodes(target));
            }
            notifyChange(actor, target, "A passkey was registered.");
            return result("passkeyEnrolled", fields);
        }
        if (REGENERATE_RECOVERY.equals(operation)) {
            requireRegisteredFactor(target);
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", "recoveryCodesRegenerated");
            fields.put("recoveryCodes", mfaService.regenerateRecoveryCodes(target));
            authTokenDao.deleteTokensForAccount(target.getId());
            notifyChange(actor, target, "A new set of recovery codes was generated.");
            return result("recoveryCodesRegenerated", fields);
        }
        if (BEGIN_RECOVERY_EMAIL.equals(operation)) {
            return result("recoveryEmailVerificationSent",
                    emailRecoveryService.beginEnrollment(target, string(input.get("email"))));
        }
        if (CONFIRM_RECOVERY_EMAIL.equals(operation)) {
            emailRecoveryService.confirmEnrollment(target, string(input.get("challengeId")),
                    string(input.get("emailCode")));
            authTokenDao.deleteTokensForAccount(target.getId());
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", "recoveryEmailVerified");
            fields.put("recoveryEmailMasked", emailRecoveryService.maskedEmail(target));
            return result("recoveryEmailVerified", fields);
        }
        if (REVOKE_RECOVERY_EMAIL.equals(operation)) {
            notifyChange(actor, target, "The verified recovery email address was removed.");
            for (Credential email : mfaDao.listActive(target.getId(),
                    CredentialConstants.KIND_MFA_RECOVERY_EMAIL,
                    CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE)) {
                mfaDao.deactivate(email, "revokedByAccount-" + actor.getId());
            }
            authTokenDao.deleteTokensForAccount(target.getId());
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", "recoveryEmailRevoked");
            return result("recoveryEmailRevoked", fields);
        }
        if (REVOKE_FACTOR.equals(operation)) {
            Credential factor = requireOwnedFactor(target, string(input.get("factorId")));
            enforceSafeSelfRevocation(actor, target);
            mfaDao.deactivate(factor, "revokedByAccount-" + actor.getId());
            authTokenDao.deleteTokensForAccount(target.getId());
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", "factorRevoked");
            fields.put("factorId", factor.getPublicValue());
            notifyChange(actor, target, "A registered authentication factor was removed.");
            return result("factorRevoked", fields);
        }
        if (REVOKE_ALL.equals(operation)) {
            requireAdministrator(actor);
            notifyChange(actor, target,
                    "All authentication factors and account-recovery methods were removed.");
            for (Credential credential : mfaDao.listActive(target.getId(),
                    CredentialConstants.KIND_MFA_TOTP,
                    CredentialConstants.KIND_MFA_WEBAUTHN,
                    CredentialConstants.KIND_MFA_RECOVERY_CODE,
                    CredentialConstants.KIND_MFA_RECOVERY_EMAIL,
                    CredentialConstants.KIND_MFA_RECOVERY_EMAIL_CODE)) {
                mfaDao.deactivate(credential, "revokedByAccount-" + actor.getId());
            }
            authTokenDao.deleteTokensForAccount(target.getId());
            Map<String, Object> fields = new HashMap<>();
            fields.put("status", "allFactorsRevoked");
            return result("allFactorsRevoked", fields);
        }
        throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidMfaOperation",
                "The requested MFA operation is not supported.", null);
    }

    private Resource status(Account account) {
        List<? extends Credential> totp =
                mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_TOTP);
        List<? extends Credential> passkeys =
                mfaDao.listActive(account.getId(), CredentialConstants.KIND_MFA_WEBAUTHN);
        int recovery = mfaDao.listActive(account.getId(),
                CredentialConstants.KIND_MFA_RECOVERY_CODE).size();
        MfaPolicy policy = policyService.getPolicy();
        Map<String, Object> fields = new HashMap<>();
        fields.put("accountId", formattedAccountId(account.getId()));
        fields.put("enrolled", !totp.isEmpty() || !passkeys.isEmpty());
        fields.put("enrollmentRequired", policy.requiresEnrollment(account));
        fields.put("enforcement", policy.getEnforcement());
        fields.put("totpCount", totp.size());
        fields.put("passkeyCount", passkeys.size());
        fields.put("passkeyLimit", policy.getPasskeyLimit());
        fields.put("recoveryCodesRemaining", recovery);
        fields.put("webAuthnConfigured", policy.isWebAuthnConfigured());
        fields.put("emailRecoveryAvailable", emailRecoveryService.isAvailable(account));
        fields.put("recoveryEmailEnrollmentAvailable",
                policyService.getSmtpConfiguration().isEnabled());
        fields.put("recoveryEmailMasked", emailRecoveryService.maskedEmail(account));
        return new ResourceImpl(String.valueOf(account.getId()), STATUS_TYPE, fields);
    }

    private List<Resource> factors(Account account) {
        List<Resource> result = new ArrayList<>();
        for (Credential credential : mfaDao.listActive(account.getId(),
                CredentialConstants.KIND_MFA_TOTP, CredentialConstants.KIND_MFA_WEBAUTHN)) {
            result.add(factorResource(credential));
        }
        return result;
    }

    private Resource factorResource(Credential credential) {
        Map<String, Object> data = credential.getData() == null
                ? Collections.<String, Object>emptyMap() : credential.getData();
        Map<String, Object> fields = new HashMap<>();
        fields.put("accountId", formattedAccountId(credential.getAccountId()));
        fields.put("factorType", CredentialConstants.KIND_MFA_TOTP.equals(credential.getKind())
                ? "totp" : "passkey");
        fields.put("label", data.get("label"));
        fields.put("createdAt", data.get("createdAt"));
        fields.put("lastUsedAt", data.get("lastUsedAt"));
        fields.put("backupEligible", data.get("backupEligible"));
        fields.put("backedUp", data.get("backedUp"));
        fields.put("state", credential.getState());
        return new ResourceImpl(credential.getPublicValue(), FACTOR_TYPE, fields);
    }

    private Credential requireOwnedFactor(Account target, String factorId) {
        if (StringUtils.isBlank(factorId)) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "MfaFactorRequired",
                    "A factor identifier is required.", null);
        }
        Credential factor = mfaDao.findActive(CredentialConstants.KIND_MFA_TOTP, factorId);
        if (factor == null) {
            factor = mfaDao.findActive(CredentialConstants.KIND_MFA_WEBAUTHN, factorId);
        }
        if (factor == null || !target.getId().equals(factor.getAccountId())) {
            throw new ClientVisibleException(ResponseCodes.NOT_FOUND, "MfaFactorNotFound",
                    "The selected factor does not exist for this account.", null);
        }
        return factor;
    }

    private void enforceSafeSelfRevocation(Account actor, Account target) {
        if (!actor.getId().equals(target.getId()) || !policyService.getPolicy().requiresEnrollment(target)) {
            return;
        }
        int count = mfaDao.listActive(target.getId(), CredentialConstants.KIND_MFA_TOTP,
                CredentialConstants.KIND_MFA_WEBAUTHN).size();
        if (count <= 1) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "LastMfaFactor",
                    "Enroll another factor before removing the last required factor.", null);
        }
    }

    boolean requiresAccountHolder(String operation) {
        return BEGIN_TOTP.equals(operation)
                || CONFIRM_TOTP.equals(operation)
                || BEGIN_WEBAUTHN.equals(operation)
                || CONFIRM_WEBAUTHN.equals(operation)
                || REGENERATE_RECOVERY.equals(operation)
                || BEGIN_RECOVERY_EMAIL.equals(operation)
                || CONFIRM_RECOVERY_EMAIL.equals(operation);
    }

    void requireAccountHolder(Account actor, Account target) {
        if (actor == null || target == null || !actor.getId().equals(target.getId())) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, "MfaAccountHolderRequired",
                    "Only the account holder can register authentication factors, verify a recovery "
                            + "address, or generate recovery codes.", null);
        }
    }

    private void requireRegisteredFactor(Account target) {
        if (mfaDao.listActive(target.getId(), CredentialConstants.KIND_MFA_TOTP,
                CredentialConstants.KIND_MFA_WEBAUTHN).isEmpty()) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "MfaFactorRequired",
                    "Register an authenticator app or passkey before generating recovery codes.", null);
        }
    }

    private void requireSecurityConfirmation(Account actor, Account target, String operation,
                                             String confirmation) {
        if (!requiresSecurityConfirmation(operation)) {
            return;
        }
        if (!mfaService.hasPrimaryFactor(actor.getId())) {
            if (!actor.getId().equals(target.getId())) {
                throw new ClientVisibleException(ResponseCodes.CONFLICT,
                        "AdministratorMfaEnrollmentRequired",
                        "Register an authentication factor for the administrator before managing "
                                + "another account's security.",
                        null);
            }
            return;
        }
        mfaService.consumeSecurityConfirmation(actor, confirmation);
    }

    private boolean requiresSecurityConfirmation(String operation) {
        return BEGIN_TOTP.equals(operation)
                || BEGIN_WEBAUTHN.equals(operation)
                || REGENERATE_RECOVERY.equals(operation)
                || BEGIN_RECOVERY_EMAIL.equals(operation)
                || REVOKE_RECOVERY_EMAIL.equals(operation)
                || REVOKE_FACTOR.equals(operation)
                || REVOKE_ALL.equals(operation);
    }

    private Account targetAccount(Account actor, Object suppliedId) {
        Long targetId = accountId(suppliedId, actor.getId());
        if (!actor.getId().equals(targetId)) {
            requireAdministrator(actor);
        }
        Account target = authDao.getAccountById(targetId);
        if (target == null || !accountDao.isActiveAccount(target)) {
            throw new ClientVisibleException(ResponseCodes.NOT_FOUND, "AccountNotFound",
                    "The selected account does not exist or is not active.", null);
        }
        return target;
    }

    private Account currentAccount() {
        if (!(ApiContext.getContext().getPolicy() instanceof Policy)) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED);
        }
        Policy policy = (Policy) ApiContext.getContext().getPolicy();
        Account account = authDao.getAccountById(policy.getAuthenticatedAsAccountId());
        if (account == null || !accountDao.isActiveAccount(account)) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED);
        }
        return account;
    }

    private void requireAdministrator(Account account) {
        if (account == null || !AccountConstants.ADMIN_KIND.equalsIgnoreCase(account.getKind())) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, "SystemAdministratorRequired",
                    "Only an active system administrator can manage another account's factors.", null);
        }
    }

    private void notifyChange(Account actor, Account target, String change) {
        String actorContext = actor != null && target != null
                && !actor.getId().equals(target.getId())
                ? "A system administrator made this change. " : "";
        emailRecoveryService.sendSecurityChangeNotice(target, actorContext + change);
    }

    private Resource result(String id, Map<String, Object> fields) {
        Map<String, Object> copy = fields == null ? new HashMap<String, Object>() : new HashMap<>(fields);
        if (!copy.containsKey("status")) {
            copy.put("status", id);
        }
        return new ResourceImpl(id + "-" + System.currentTimeMillis(), OPERATION_TYPE, copy);
    }

    private Long accountId(Object value, Long fallback) {
        String supplied = string(value);
        if (StringUtils.isBlank(supplied)) {
            return fallback;
        }
        try {
            String parsed = ApiContext.getContext().getIdFormatter().parseId(supplied);
            return Long.valueOf(StringUtils.defaultIfBlank(parsed, supplied));
        } catch (RuntimeException e) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidAccountId",
                    "The account identifier is invalid.", null);
        }
    }

    private String formattedAccountId(Long accountId) {
        return String.valueOf(ApiContext.getContext().getIdFormatter()
                .formatId(objectManager.getType(Account.class), accountId));
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
