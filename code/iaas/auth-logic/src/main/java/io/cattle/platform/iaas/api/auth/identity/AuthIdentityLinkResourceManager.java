package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.util.SettingsUtils;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.iaas.api.auth.integration.local.LocalAuthConstants;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

public class AuthIdentityLinkResourceManager extends AbstractNoOpResourceManager {

    public static final String LINK_TYPE = "authIdentityLink";
    public static final String OPERATION_TYPE = "authIdentityOperation";

    private static final String ACTION_BIND = "bind";
    private static final String ACTION_INSPECT = "inspect";
    private static final String ACTION_REASSIGN = "reassign";
    private static final String ACTION_RESTORE = "restore";
    private static final String ACTION_CANCEL_SWITCH = "cancelSwitch";
    private static final String ACTION_SWITCH_TO_LOCAL = "switchToLocal";
    private static final String DISPOSITION_KEEP = "keep";
    private static final String DISPOSITION_DISABLE = "disable";
    private static final String DISPOSITION_DISCARD = "discardPermissions";

    @Inject
    AuthDao authDao;
    @Inject
    AuthTokenDao authTokenDao;
    @Inject
    AccountDao accountDao;
    @Inject
    ObjectManager objectManager;
    @Inject
    ObjectProcessManager objectProcessManager;
    @Inject
    IdentityProofVerifier proofVerifier;
    @Inject
    ProviderSwitchTicketService switchTicketService;
    @Inject
    SettingsUtils settingsUtils;

    @Override
    public String[] getTypes() {
        return new String[] {LINK_TYPE, OPERATION_TYPE};
    }

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[] {AuthIdentityLink.class, AuthIdentityOperation.class};
    }

    @Override
    protected Object listInternal(SchemaFactory schemaFactory, String type, Map<Object, Object> criteria,
                                  ListOptions options) {
        requireSystemAdministrator();
        if (!LINK_TYPE.equalsIgnoreCase(type)) {
            return Collections.emptyList();
        }

        Long accountId = criteriaAccountId(criteria);
        if (accountId == null) {
            accountId = currentPolicy().getAuthenticatedAsAccountId();
        }
        Account account = requireAccount(accountId, false);
        List<Resource> result = new ArrayList<>();
        for (Credential credential : authDao.getIdentityLinks(account.getId())) {
            result.add(toResource(credential));
        }
        return result;
    }

    @Override
    protected Object createInternal(String type, ApiRequest request) {
        if (!OPERATION_TYPE.equalsIgnoreCase(type)) {
            return null;
        }
        Account actor = requireSystemAdministrator();
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        String action = string(input.get("operation"));

        if (ACTION_INSPECT.equals(action)) {
            return inspect(input);
        }
        if (ACTION_BIND.equals(action)) {
            return bind(actor, input);
        }
        if (ACTION_REASSIGN.equals(action)) {
            return reassign(actor, input);
        }
        if (ACTION_RESTORE.equals(action)) {
            return restore(input);
        }
        if (ACTION_CANCEL_SWITCH.equals(action)) {
            return cancelSwitch(input);
        }
        if (ACTION_SWITCH_TO_LOCAL.equals(action)) {
            return switchToLocal(input);
        }
        throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidIdentityOperation",
                "The requested identity operation is not supported.", null);
    }

    private Resource inspect(Map<String, Object> input) {
        VerifiedIdentityProof proof = proofVerifier.verify(string(input.get("identityProof")));
        String linkKey = IdentityLinkKey.create(proof.getProvider(), proof.getExternalIdType(),
                proof.getExternalId());
        Credential existing = authDao.getIdentityLink(linkKey);
        Map<String, Object> fields = new HashMap<>();
        fields.put("status", "verified");
        fields.put("verifiedIdentity", proofIdentityFields(proof));
        fields.put("matchedAccountId", existing == null ? null : formattedAccountId(existing.getAccountId()));
        return new ResourceImpl("inspect-" + linkKey, OPERATION_TYPE, fields);
    }

    private Resource bind(Account actor, Map<String, Object> input) {
        VerifiedIdentityProof proof = proofVerifier.verify(string(input.get("identityProof")));
        Account target = requireAccount(accountId(input.get("targetAccountId"), actor.getId()), true);
        String linkKey = IdentityLinkKey.create(proof.getProvider(), proof.getExternalIdType(), proof.getExternalId());

        Credential existing = authDao.getIdentityLink(linkKey);
        if (existing != null && !target.getId().equals(existing.getAccountId())) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "IdentityAlreadyLinked",
                    "The verified identity is already linked to another account. Use the reassignment workflow.",
                    null);
        }

        authDao.consumeIdentityProof(proof.getReplayKey(), actor.getId());
        Identity identity = proofIdentity(proof);
        Credential linked = existing == null
                ? authDao.linkIdentity(target, identity, proof.getProvider(), linkKey)
                : existing;
        PreparedProviderSwitch providerSwitch = prepareProviderSwitch(input, target, proof);
        return operationResult("bound", linked, 0, false, providerSwitch);
    }

    private Resource reassign(Account actor, Map<String, Object> input) {
        VerifiedIdentityProof proof = proofVerifier.verify(string(input.get("identityProof")));
        Account source = requireAccount(accountId(input.get("sourceAccountId"), null), false);
        Account target = requireAccount(accountId(input.get("targetAccountId"), null), true);
        if (source.getId().equals(target.getId())) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "SameIdentityAccount",
                    "Source and target accounts must be different.", null);
        }

        String disposition = StringUtils.defaultIfBlank(string(input.get("oldAccountDisposition")),
                DISPOSITION_KEEP);
        if (!DISPOSITION_KEEP.equals(disposition) && !DISPOSITION_DISABLE.equals(disposition)
                && !DISPOSITION_DISCARD.equals(disposition)) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidAccountDisposition",
                    "Old account disposition must be keep, disable, or discardPermissions.", null);
        }
        boolean transferPermissions = input.get("transferPermissions") == null
                || BooleanUtils.toBoolean(string(input.get("transferPermissions")));

        String linkKey = IdentityLinkKey.create(proof.getProvider(), proof.getExternalIdType(), proof.getExternalId());
        Credential existing = authDao.getIdentityLink(linkKey);
        if (existing == null || !source.getId().equals(existing.getAccountId())) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "IdentityLinkSourceMismatch",
                    "The verified identity is not linked to the selected source account.", null);
        }

        boolean removesSourceAccess = DISPOSITION_DISABLE.equals(disposition)
                || DISPOSITION_DISCARD.equals(disposition);
        boolean sourceIsAdministrator = AccountConstants.ADMIN_KIND.equalsIgnoreCase(source.getKind());
        boolean targetIsAdministrator = AccountConstants.ADMIN_KIND.equalsIgnoreCase(target.getKind());
        if (removesSourceAccess && sourceIsAdministrator && !targetIsAdministrator
                && !transferPermissions && authDao.countActiveAdminAccounts() <= 1) {
            throw new ClientVisibleException(ResponseCodes.CONFLICT, "LastAdministrator",
                    "Transfer administrator access before disabling the last active system administrator.",
                    null);
        }

        authDao.consumeIdentityProof(proof.getReplayKey(), actor.getId());

        int membershipsCopied = 0;
        boolean administratorTransferred = false;
        if (transferPermissions) {
            membershipsCopied = authDao.copyDirectProjectMemberships(source, target);
            if (AccountConstants.ADMIN_KIND.equalsIgnoreCase(source.getKind())
                    && !AccountConstants.ADMIN_KIND.equalsIgnoreCase(target.getKind())) {
                target = authDao.updateAccount(target, null, AccountConstants.ADMIN_KIND, null, null);
                administratorTransferred = true;
            }
        }

        Credential moved = authDao.moveIdentityLink(existing, source, target, actor.getId());
        authTokenDao.deleteTokensForAccount(source.getId());

        if (DISPOSITION_DISCARD.equals(disposition)) {
            authDao.removeDirectProjectMemberships(source);
            if (AccountConstants.ADMIN_KIND.equalsIgnoreCase(source.getKind())) {
                source = authDao.updateAccount(source, null, AccountConstants.USER_KIND, null, null);
            }
            deactivate(source);
        } else if (DISPOSITION_DISABLE.equals(disposition)) {
            deactivate(source);
        }

        PreparedProviderSwitch providerSwitch = prepareProviderSwitch(input, target, proof);
        return operationResult("reassigned", moved, membershipsCopied, administratorTransferred, providerSwitch);
    }

    private Resource restore(Map<String, Object> input) {
        Account account = requireAccount(accountId(input.get("targetAccountId"), null), false);
        if (!accountDao.isActiveAccount(account)) {
            objectProcessManager.executeStandardProcess(StandardProcess.ACTIVATE, account, null);
            account = objectManager.reload(account);
        }
        Map<String, Object> fields = new HashMap<>();
        fields.put("status", "restored");
        fields.put("targetAccountId", formattedAccountId(account.getId()));
        return new ResourceImpl("restore-" + account.getId(), OPERATION_TYPE, fields);
    }

    private Resource cancelSwitch(Map<String, Object> input) {
        switchTicketService.cancel(string(input.get("providerSwitchCode")));
        Map<String, Object> fields = new HashMap<>();
        fields.put("status", "cancelled");
        return new ResourceImpl("cancelled", OPERATION_TYPE, fields);
    }

    private Resource switchToLocal(Map<String, Object> input) {
        String username = string(input.get("localUsername"));
        String password = string(input.get("localPassword"));
        if (StringUtils.isAnyBlank(username, password)) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "LocalCredentialsRequired",
                    "An existing local administrator username and password are required.", null);
        }

        Account localAccount = authDao.getAccountByLogin(username, password,
                ApiContext.getContext().getTransformationService());
        if (localAccount == null || !accountDao.isActiveAccount(localAccount)
                || !AccountConstants.ADMIN_KIND.equalsIgnoreCase(localAccount.getKind())) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED, "InvalidLocalAdministrator",
                    "The supplied credentials do not belong to an active system administrator.", null);
        }

        Identity localIdentity = new Identity(ProjectConstants.RANCHER_ID, String.valueOf(localAccount.getId()),
                localAccount.getName(), null, null, username, true);
        PreparedProviderSwitch providerSwitch = switchTicketService.prepare(localAccount,
                LocalAuthConstants.CONFIG, localIdentity);

        // Security is never disabled during a provider change.  The caller
        // receives a short-lived one-use ticket, changes to the local provider,
        // and immediately establishes a session for the verified local admin.
        settingsUtils.changeSetting(SecurityConstants.SECURITY_SETTING, true);
        settingsUtils.changeSetting(LocalAuthConstants.RECOVERY_ENABLED_SETTING, true);
        settingsUtils.changeSetting(SecurityConstants.AUTH_PROVIDER_SETTING, LocalAuthConstants.CONFIG);

        Map<String, Object> fields = new HashMap<>();
        fields.put("status", "switchedToLocal");
        fields.put("targetAccountId", formattedAccountId(localAccount.getId()));
        fields.put("providerSwitchCode", providerSwitch.getCode());
        fields.put("providerSwitchExpiresAt", new java.util.Date(providerSwitch.getExpiresAt()));
        return new ResourceImpl("switch-to-local-" + localAccount.getId(), OPERATION_TYPE, fields);
    }

    private void deactivate(Account account) {
        if (accountDao.isActiveAccount(account)) {
            objectProcessManager.executeStandardProcess(StandardProcess.DEACTIVATE, account, null);
        }
        authTokenDao.deleteTokensForAccount(account.getId());
    }

    private PreparedProviderSwitch prepareProviderSwitch(Map<String, Object> input, Account target,
                                                         VerifiedIdentityProof proof) {
        if (!BooleanUtils.toBoolean(string(input.get("prepareProviderSwitch")))) {
            return null;
        }
        return switchTicketService.prepare(target, proof);
    }

    private Resource operationResult(String status, Credential credential, int membershipsCopied,
                                     boolean administratorTransferred,
                                     PreparedProviderSwitch providerSwitch) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("status", status);
        fields.put("identityLink", toResource(credential));
        fields.put("membershipsCopied", membershipsCopied);
        fields.put("administratorTransferred", administratorTransferred);
        if (providerSwitch != null) {
            fields.put("providerSwitchCode", providerSwitch.getCode());
            fields.put("providerSwitchExpiresAt", new java.util.Date(providerSwitch.getExpiresAt()));
        }
        return new ResourceImpl(status + "-" + credential.getId(), OPERATION_TYPE, fields);
    }

    private Map<String, Object> proofIdentityFields(VerifiedIdentityProof proof) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("provider", proof.getProvider());
        fields.put("externalIdType", proof.getExternalIdType());
        fields.put("externalId", proof.getExternalId());
        fields.put("name", proof.getName());
        fields.put("login", proof.getLogin());
        return fields;
    }

    private Resource toResource(Credential credential) {
        Map<String, Object> data = credential.getData() == null
                ? Collections.<String, Object>emptyMap()
                : credential.getData();
        Map<String, Object> fields = new HashMap<>();
        fields.put("accountId", formattedAccountId(credential.getAccountId()));
        fields.put("provider", data.get("provider"));
        fields.put("externalIdType", data.get("externalIdType"));
        fields.put("externalId", data.get("externalId"));
        fields.put("name", data.get("name"));
        fields.put("login", data.get("login"));
        fields.put("linkedAt", data.get("linkedAt"));
        fields.put("lastLoginAt", data.get("lastLoginAt"));
        fields.put("state", credential.getState());
        return new ResourceImpl(String.valueOf(credential.getId()), LINK_TYPE, fields);
    }

    private Identity proofIdentity(VerifiedIdentityProof proof) {
        return new Identity(proof.getExternalIdType(), proof.getExternalId(), proof.getName(),
                null, null, proof.getLogin(), true);
    }

    private Account requireSystemAdministrator() {
        Policy policy = currentPolicy();
        Account actor = authDao.getAccountById(policy.getAuthenticatedAsAccountId());
        if (actor == null || !accountDao.isActiveAccount(actor)
                || !AccountConstants.ADMIN_KIND.equalsIgnoreCase(actor.getKind())) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, "SystemAdministratorRequired",
                    "Only an active system administrator can manage login identities.", null);
        }
        return actor;
    }

    private Policy currentPolicy() {
        if (!(ApiContext.getContext().getPolicy() instanceof Policy)) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED);
        }
        return (Policy) ApiContext.getContext().getPolicy();
    }

    private Account requireAccount(Long accountId, boolean active) {
        if (accountId == null) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "AccountRequired",
                    "A target account is required.", null);
        }
        Account account = authDao.getAccountById(accountId);
        if (account == null || ProjectConstants.TYPE.equalsIgnoreCase(account.getKind())
                || (active && !accountDao.isActiveAccount(account))) {
            throw new ClientVisibleException(ResponseCodes.NOT_FOUND, "AccountNotFound",
                    "The selected account does not exist or is not active.", null);
        }
        return account;
    }

    private Long criteriaAccountId(Map<Object, Object> criteria) {
        return criteria == null ? null
                : accountId(RequestUtils.makeSingularStringIfCan(criteria.get("accountId")), null);
    }

    private Long accountId(Object value, Long defaultValue) {
        String supplied = string(value);
        if (StringUtils.isBlank(supplied)) {
            return defaultValue;
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
