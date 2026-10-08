package io.cattle.platform.iaas.api.auth.impl;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.AuthorizationProvider;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyDelegationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthenticationAudit;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.iaas.api.auth.integration.internal.rancher.BasicAuthImpl;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.integration.external.ExternalServiceAuthProvider;
import io.cattle.platform.iaas.api.auth.integration.interfaces.AccountLookup;
import io.cattle.platform.iaas.api.auth.integration.interfaces.IdentityProvider;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractApiRequestHandler;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import io.github.ibuildthecloud.gdapi.util.TransformationService;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ApiAuthenticator extends AbstractApiRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthenticator.class);

    private static final String ACCOUNT_ID_HEADER = "X-API-ACCOUNT-ID";
    private static final String USER_ID_HEADER = "X-API-USER-ID";
    private static final String ACCOUNT_KIND_HEADER = "X-API-ACCOUNT-KIND";
    private static final String ACCOUNT_NAME_HEADER = "X-API-ACCOUNT-NAME";

    AuthDao authDao;
    List<AccountLookup> accountLookups;
    List<IdentityProvider> identityProviders;
    List<AuthorizationProvider> authorizationProviders;
    @Inject
    ObjectManager objectManager;

    @Inject
    TransformationService transformationService;

    @Inject
    ExternalServiceAuthProvider externalAuthProvider;

    @Inject
    AccountDao accountDao;

    @Inject ApiKeyAuthorizationService apiKeyAuthorization;
    @Inject ApiKeyDelegationService delegationService;
    @Inject List<ApiKeyAuditSink> auditSinks = java.util.Collections.emptyList();

    @Override
    public void handle(ApiRequest request) throws IOException {
        try {
            authenticate(request);
        } catch (RuntimeException failure) {
            if (ApiKeyCredentialContext.get(request) != null
                    && !Boolean.TRUE.equals(request.getAttribute("apiKey.audit.admitted"))
                    && !(failure instanceof ClientVisibleException visible && "AuditUnavailable".equals(visible.getCode()))) {
                ApiKeyAuthenticationAudit.deny(request, auditSinks,
                        failure instanceof ClientVisibleException visible ? visible.getCode() : "AuthenticationUnavailable");
            }
            throw failure;
        }
    }

    protected void authenticate(ApiRequest request) throws IOException {
        if (ApiContext.getContext().getPolicy() != null) {
            return;
        }
        if (ApiContext.getContext().getTransformationService() == null){
            ApiContext.getContext().setTransformationService(transformationService);
        }
        if (delegationService != null && delegationService.handleIntrospection(request)) {
            return;
        }

        Account authenticatedAsAccount = getAccount(request);
        if (authenticatedAsAccount == null ||
                !accountDao.isActiveAccount(authenticatedAsAccount)) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED);
        }

        Set<Identity> identities = getIdentities(authenticatedAsAccount);
        if (identities == null || identities.size() == 0) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED);
        }

        Account account = getAccountRequested(authenticatedAsAccount, identities, request);
        Policy policy = getPolicy(account, authenticatedAsAccount, identities, request);
        if (policy == null) {
            log.error("Failed to find policy for [{}]", account.getId());
            throwUnauthorized();
        }

        SchemaFactory schemaFactory = getSchemaFactory(account, policy, request);
        if (schemaFactory == null) {
            log.error("Failed to find a schema for account type [{}]", account.getKind());
            if (SecurityConstants.SECURITY.get()) {
                throwUnauthorized();
            }
        } else if (request.getType() != null && request.getType().endsWith("s")) {
            //In case the SchemaFactory didn't have the type before.
            //we need to make it singular.
            String singleType = schemaFactory.getSingularName(request.getType());
            if (singleType != null) {
                request.setType(singleType);
            }
        }
        saveInContext(request, policy, schemaFactory, authenticatedAsAccount);
        apiKeyAuthorization.authorize(request, policy);
    }

    protected void throwUnauthorized() {
        throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED);
    }

    public record CurrentAuthorization(Policy policy, SchemaFactory schemas) { }

    /** Rebuild, rather than cache, RBAC for queued Key work at execution time. */
    public CurrentAuthorization currentAuthorization(long principalId, long accountId, ApiRequest request) {
        Account principal = authDao.getAccountById(principalId);
        Account account = authDao.getAccountById(accountId);
        if (principal == null || account == null || !accountDao.isActiveAccount(principal) || !accountDao.isActiveAccount(account)) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, "OwnerPermissionDenied", "The key owner no longer has access.", null);
        }
        Set<Identity> identities = getIdentities(principal);
        Policy personal = getPolicy(principal, principal, identities, request);
        if (personal == null || (principalId != accountId && !authDao.hasAccessToProject(accountId, principalId,
                personal.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS), identities))) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, "OwnerPermissionDenied", "The key owner no longer has access.", null);
        }
        Policy policy = getPolicy(account, principal, identities, request);
        SchemaFactory factory = getSchemaFactory(account, policy, request);
        if (policy == null || factory == null) throw new ClientVisibleException(ResponseCodes.FORBIDDEN);
        return new CurrentAuthorization(policy, factory);
    }

    protected void saveInContext(ApiRequest request, Policy policy, SchemaFactory schemaFactory, Account authorizedAccount) {
        if (schemaFactory != null) {
            request.setSchemaFactory(schemaFactory);
        }
        String accountId = (String) ApiContext.getContext().getIdFormatter().formatId(objectManager.getType(Account.class), policy.getAccountId());
        request.getServletContext().getResponse().addHeader(ACCOUNT_ID_HEADER, accountId);
        String userId = (String) ApiContext.getContext().getIdFormatter().formatId(objectManager.getType(Account.class), policy.getAuthenticatedAsAccountId());
        request.getServletContext().getResponse().addHeader(USER_ID_HEADER, userId);
        request.getServletContext().getResponse().addHeader(ACCOUNT_KIND_HEADER, authorizedAccount.getKind());
        request.getServletContext().getResponse().addHeader(ACCOUNT_NAME_HEADER, authorizedAccount.getName());
        ApiContext.getContext().setPolicy(policy);
    }

    protected Policy getPolicy(Account account, Account authenticatedAsAccount, Set<Identity> identities, ApiRequest request) {
        Policy policy = null;

        for (AuthorizationProvider auth : authorizationProviders) {
            policy = auth.getPolicy(account, authenticatedAsAccount, identities, request);
            if (policy != null) {
                break;
            }
        }

        return policy;
    }

    protected SchemaFactory getSchemaFactory(Account account, Policy policy, ApiRequest request) {
        SchemaFactory factory = null;

        for (AuthorizationProvider auth : authorizationProviders) {
            factory = auth.getSchemaFactory(account, policy, request);
            if (factory != null) {
                break;
            }
        }

        return factory;
    }

    protected Account getAccount(ApiRequest request) {
        Account account = null;

        String authorization = request.getServletContext().getRequest().getHeader("Authorization");
        if (BasicAuthImpl.isBasicHeader(authorization)) {
            // An explicitly supplied API credential must not silently turn into
            // the broader browser-cookie session selected by an earlier lookup.
            for (AccountLookup lookup : accountLookups) {
                if ("BasicAuth".equals(lookup.getName()) && lookup.isConfigured()) {
                    account = lookup.getAccount(request);
                    if (account != null) request.setAttribute(AccountConstants.AUTH_TYPE, lookup.getName());
                    return account;
                }
            }
            return null;
        }

        for (AccountLookup lookup : accountLookups) {
            if (lookup.isConfigured()){
                account = lookup.getAccount(request);
                if (account != null) {
                    request.setAttribute(AccountConstants.AUTH_TYPE, lookup.getName());
                    break;
                }
            }
        }

        if (account != null) {
            return account;
        }

        if (SecurityConstants.SECURITY.get()) {
            for (AccountLookup lookup : accountLookups) {
                if (lookup.challenge(request)) {
                    break;
                }
            }
        }

        return null;
    }

    private Set<Identity> getIdentities(Account account) {
        Set<Identity> identities = new HashSet<>();
        for (IdentityProvider identityProvider : identityProviders) {
           identities.addAll(identityProvider.getIdentities(account));
        }
        identities.addAll(externalAuthProvider.getIdentities(account));
        identities.remove(null);
        return identities;
    }

    private Account getAccountRequested(Account authenticatedAsAccount, Set<Identity> identities, ApiRequest request) {
        Account project;
        String parsedProjectId = null;

        String projectId = request.getServletContext().getRequest().getHeader(ProjectConstants.PROJECT_HEADER);
        if (projectId == null || projectId.isEmpty()) {
            projectId = request.getServletContext().getRequest().getParameter("projectId");
        }
        if (projectId == null || projectId.isEmpty()) {
            projectId = (String) request.getAttribute(ProjectConstants.PROJECT_HEADER);
        }

        if (projectId == null || projectId.isEmpty()) {
            String accessKey = request.getServletContext().getRequest().getHeader(ProjectConstants.CLIENT_ACCESS_KEY);
            if (StringUtils.isNotBlank(accessKey)) {
                Account account = authDao.getAccountByAccessKey(accessKey);
                if (account != null) {
                    parsedProjectId = account.getId().toString();
                }
            }
        }

        if (StringUtils.isBlank(projectId) && StringUtils.isBlank(parsedProjectId)) {
            return authenticatedAsAccount;
        }

        try {
            if (parsedProjectId == null) {
                parsedProjectId = ApiContext.getContext().getIdFormatter().parseId(projectId);
            }
        } catch (NumberFormatException e) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidFormat", "projectId header format is incorrect " + projectId, null);
        }

        if (StringUtils.isEmpty(parsedProjectId)) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN);
        }
        try {
            project = authDao.getAccountById(Long.valueOf(parsedProjectId));
            if (project == null || !accountDao.isActiveAccount(project)) {
                throw new ClientVisibleException(ResponseCodes.FORBIDDEN);
            }
            if (authenticatedAsAccount.getId().equals(project.getId())) {
                return authenticatedAsAccount;
            }
        } catch (NumberFormatException e) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN);
        }
        Policy tempPolicy = getPolicy(authenticatedAsAccount, authenticatedAsAccount, identities, request);
        if (authDao.hasAccessToProject(project.getId(), authenticatedAsAccount.getId(),
                tempPolicy.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS), identities)) {
            return project;
        }
        throw new ClientVisibleException(ResponseCodes.FORBIDDEN);
    }

    public AuthDao getAuthDao() {
        return authDao;
    }

    @Inject
    public void setAuthDao(AuthDao authDao) {
        this.authDao = authDao;
    }

    public List<AuthorizationProvider> getAuthorizationProviders() {
        return authorizationProviders;
    }

    @Inject
    public void setAuthorizationProviders(List<AuthorizationProvider> authorizationProviders) {
        this.authorizationProviders = CollectionUtils.orderList(AuthorizationProvider.class, authorizationProviders);
    }

    public List<AccountLookup> getAccountLookups() {
        return accountLookups;
    }

    @Inject
    public void setAccountLookups(List<AccountLookup> accountLookups) {
        this.accountLookups = CollectionUtils.orderList(AccountLookup.class, accountLookups);
    }

    public List<IdentityProvider> getIdentityProviders() {
        return identityProviders;
    }

    @Inject
    public void setIdentityProviders(List<IdentityProvider> identityProviders) {
        this.identityProviders = CollectionUtils.orderList(IdentityProvider.class, identityProviders);
    }

}
