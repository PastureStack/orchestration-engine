package io.cattle.platform.iaas.api.auth.mfa;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.framework.encryption.EncryptionConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import io.github.ibuildthecloud.gdapi.util.TransformationService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;

public class MfaSettingsResourceManager extends AbstractNoOpResourceManager {

    public static final String TYPE = "mfaSettings";

    @Inject
    MfaDao mfaDao;
    @Inject
    MfaPolicyService policyService;
    @Inject
    WebAuthnService webAuthnService;
    @Inject
    MfaMailService mailService;
    @Inject
    MfaService mfaService;
    @Inject
    AuthDao authDao;
    @Inject
    AccountDao accountDao;
    @Inject
    LockManager lockManager;
    @Inject
    TransformationService transformationService;

    @Override
    public String[] getTypes() {
        return new String[] {TYPE};
    }

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[] {MfaSettings.class};
    }

    @Override
    protected Object listInternal(SchemaFactory schemaFactory, String type, Map<Object, Object> criteria,
                                  ListOptions options) {
        requireAdministrator();
        return Collections.singletonList(resource(policyService.getConfigCredential(), "ready"));
    }

    @Override
    protected Object createInternal(String type, ApiRequest request) {
        if (!TYPE.equalsIgnoreCase(type)) {
            return null;
        }
        final Account actor = requireAdministrator();
        final Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        return lockManager.lock(new MfaCredentialLock(CredentialConstants.KIND_MFA_SYSTEM_CONFIG,
                MfaPolicyService.GLOBAL_CONFIG_KEY), new LockCallback<Resource>() {
                    @Override
                    public Resource doWithLock() {
                        Credential existing = policyService.getConfigCredential();
                        Map<String, Object> data = existing == null || existing.getData() == null
                                ? new HashMap<String, Object>() : new HashMap<>(existing.getData());
                        merge(data, input, "enforcement", "passkeyLimit", "relyingPartyId", "origin",
                                "relyingPartyName", "issuer", "smtpEnabled", "smtpHost", "smtpPort",
                                "smtpUsername", "smtpFrom", "smtpStartTls", "smtpSsl",
                                "smtpConnectionTimeoutMillis", "smtpReadTimeoutMillis",
                                "emailCodeTtlSeconds");

                        MfaPolicy candidate = new MfaPolicy(string(data.get("enforcement")),
                                integer(data.get("passkeyLimit"), 5),
                                string(data.get("relyingPartyId")), string(data.get("origin")),
                                string(data.get("relyingPartyName")), string(data.get("issuer")));
                        boolean hasAnyWebAuthnValue = StringUtils.isNotBlank(candidate.getOrigin())
                                || StringUtils.isNotBlank(candidate.getRelyingPartyId());
                        if (hasAnyWebAuthnValue) {
                            webAuthnService.validateConfiguration(candidate);
                        }
                        MfaPolicy previous = policyService.getPolicy();
                        boolean webAuthnChanged = !Objects.equals(previous.getOrigin(),
                                candidate.getOrigin())
                                || !Objects.equals(previous.getRelyingPartyId(),
                                candidate.getRelyingPartyId());
                        if (webAuthnChanged && !mfaDao.listActiveByKind(
                                CredentialConstants.KIND_MFA_WEBAUTHN).isEmpty()) {
                            throw new ClientVisibleException(ResponseCodes.CONFLICT,
                                    "WebAuthnConfigurationInUse",
                                    "Revoke or migrate existing passkeys before changing the origin or relying-party ID.",
                                    null);
                        }
                        if (!MfaPolicy.OPTIONAL.equals(candidate.getEnforcement())
                                && !mfaService.hasPrimaryFactor(actor.getId())) {
                            throw new ClientVisibleException(ResponseCodes.CONFLICT,
                                    "AdministratorMfaEnrollmentRequired",
                                    "Enroll a factor for the current administrator before enforcing MFA.",
                                    null);
                        }

                        String passwordInput = string(input.get("smtpPassword"));
                        boolean clearPassword = bool(input.get("smtpClearPassword"), false);
                        if (clearPassword && StringUtils.isNotBlank(passwordInput)) {
                            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST,
                                    "ConflictingSmtpPasswordChange",
                                    "Enter a replacement SMTP password or clear the saved password, not both.",
                                    null);
                        }
                        String protectedPassword = existing == null ? null : existing.getSecretValue();
                        if (clearPassword) {
                            protectedPassword = null;
                            if (existing != null) {
                                existing.setSecretValue(null);
                            }
                        } else if (input.containsKey("smtpPassword")
                                && StringUtils.isNotBlank(passwordInput)) {
                            protectedPassword = transformationService.transform(passwordInput,
                                    EncryptionConstants.ENCRYPT);
                        }
                        SmtpConfiguration smtp = smtp(data,
                                protectedPassword == null ? null
                                        : transformationService.untransform(protectedPassword));
                        if (smtp.isEnabled()) {
                            mailService.validate(smtp);
                        }

                        if (bool(input.get("sendTestEmail"), false)) {
                            mailService.send(smtp, string(input.get("testRecipient")),
                                    "PastureStack SMTP test",
                                    "This message confirms that security email delivery is configured.");
                        }
                        Credential saved = existing == null
                                ? mfaDao.create(actor.getId(), CredentialConstants.KIND_MFA_SYSTEM_CONFIG,
                                MfaPolicyService.GLOBAL_CONFIG_KEY, protectedPassword, data)
                                : mfaDao.save(existing, protectedPassword, data);
                        return resource(saved, "saved");
                    }
                });
    }

    private Resource resource(Credential credential, String status) {
        Map<String, Object> data = credential == null || credential.getData() == null
                ? new HashMap<String, Object>() : new HashMap<>(credential.getData());
        MfaPolicy policy = policyService.getPolicy();
        SmtpConfiguration smtp = policyService.getSmtpConfiguration();
        Map<String, Object> fields = new HashMap<>();
        fields.put("enforcement", policy.getEnforcement());
        fields.put("passkeyLimit", policy.getPasskeyLimit());
        fields.put("relyingPartyId", policy.getRelyingPartyId());
        fields.put("origin", policy.getOrigin());
        fields.put("relyingPartyName", policy.getRelyingPartyName());
        fields.put("issuer", policy.getIssuer());
        fields.put("smtpEnabled", smtp.isEnabled());
        fields.put("smtpHost", smtp.getHost());
        fields.put("smtpPort", smtp.getPort());
        fields.put("smtpUsername", smtp.getUsername());
        fields.put("smtpPasswordConfigured", StringUtils.isNotBlank(smtp.getPassword()));
        fields.put("smtpFrom", smtp.getFrom());
        fields.put("smtpStartTls", smtp.isStartTls());
        fields.put("smtpSsl", smtp.isSsl());
        fields.put("smtpConnectionTimeoutMillis", smtp.getConnectionTimeoutMillis());
        fields.put("smtpReadTimeoutMillis", smtp.getReadTimeoutMillis());
        fields.put("emailCodeTtlSeconds", smtp.getCodeTtlSeconds());
        fields.put("status", status);
        return new ResourceImpl(MfaPolicyService.GLOBAL_CONFIG_KEY, TYPE, fields);
    }

    private SmtpConfiguration smtp(Map<String, Object> data, String password) {
        return new SmtpConfiguration(
                bool(data.get("smtpEnabled"), false),
                string(data.get("smtpHost")),
                integer(data.get("smtpPort"), 587),
                string(data.get("smtpUsername")),
                password,
                string(data.get("smtpFrom")),
                bool(data.get("smtpStartTls"), true),
                bool(data.get("smtpSsl"), false),
                clamp(integer(data.get("smtpConnectionTimeoutMillis"), 5000), 1000, 30000),
                clamp(integer(data.get("smtpReadTimeoutMillis"), 10000), 1000, 30000),
                clamp(integer(data.get("emailCodeTtlSeconds"), 600), 60, 600));
    }

    private void merge(Map<String, Object> target, Map<String, Object> input, String... keys) {
        for (String key : keys) {
            if (input.containsKey(key)) {
                target.put(key, input.get(key));
            }
        }
    }

    private Account requireAdministrator() {
        if (!(ApiContext.getContext().getPolicy() instanceof Policy)) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED);
        }
        Account account = authDao.getAccountById(
                ((Policy) ApiContext.getContext().getPolicy()).getAuthenticatedAsAccountId());
        if (account == null || !accountDao.isActiveAccount(account)
                || !AccountConstants.ADMIN_KIND.equalsIgnoreCase(account.getKind())) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, "SystemAdministratorRequired",
                    "Only an active system administrator can configure MFA and SMTP.", null);
        }
        return account;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private int integer(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
