package io.cattle.platform.iaas.api.auth.integration.internal.rancher;

import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;

import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.integration.interfaces.AccountLookup;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.Priority;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import jakarta.inject.Inject;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

public class AdminAuthLookUp implements AccountLookup, Priority {

    private static final String ENFORCE_AUTH_HEADER = "X-ENFORCE-AUTHENTICATION";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    @Inject
    AuthDao authDao;
    @Inject
    ObjectManager objectManager;

    @Override
    public Account getAccount(ApiRequest request) {
        if (SecurityConstants.SECURITY.get()) {
            return null;
        }
        String authHeader = StringUtils.trim(request.getServletContext().getRequest().getHeader(ENFORCE_AUTH_HEADER));
        if (Strings.CS.equals("true", authHeader)) {
            return null;
        }

        Account agentAccount = getAgentAccount(request);
        if (agentAccount != null) {
            return agentAccount;
        }

        return authDao.getAdminAccount();
    }

    protected Account getAgentAccount(ApiRequest request) {
        String[] usernamePassword = BasicAuthImpl.getUsernamePassword(
                request.getServletContext().getRequest().getHeader(AUTHORIZATION_HEADER));
        if (usernamePassword == null) {
            return null;
        }

        Credential credential = objectManager.findAny(Credential.class,
                CREDENTIAL.PUBLIC_VALUE, usernamePassword[0],
                CREDENTIAL.KIND, CredentialConstants.KIND_AGENT_API_KEY,
                CREDENTIAL.STATE, CommonStatesConstants.ACTIVE);
        if (credential == null) {
            return null;
        }

        Account account = authDao.getAccountById(credential.getAccountId());
        if (account != null && isSystemCredentialAccount(account)) {
            return account;
        }

        return null;
    }

    protected boolean isSystemCredentialAccount(Account account) {
        String kind = account.getKind();
        return Strings.CI.equals(kind, AccountConstants.AGENT_KIND) ||
                Strings.CI.equals(kind, AccountConstants.SERVICE_KIND);
    }

    @Override
    public boolean challenge(ApiRequest request) {
        return false;
    }

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String getName() {
        return "AdminAuth";
    }
}
