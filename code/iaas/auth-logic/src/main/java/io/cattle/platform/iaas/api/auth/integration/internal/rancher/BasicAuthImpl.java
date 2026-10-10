package io.cattle.platform.iaas.api.auth.integration.internal.rancher;

import static io.cattle.platform.core.model.tables.AccountTable.*;
import static io.cattle.platform.core.model.tables.AgentTable.*;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.VerifiedApiCredential;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthenticationAudit;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.iaas.api.auth.integration.interfaces.AccountLookup;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.util.type.Priority;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;

public class BasicAuthImpl implements AccountLookup, Priority {

    public static final String AUTH_HEADER = "Authorization";
    public static final String CHALLENGE_HEADER = "WWW-Authenticate";
    public static final String BASIC = "Basic";
    public static final String BASIC_REALM = "Basic realm=\"%s\"";
    private static final String NO_CHALLENGE_HEADER = "X-API-No-Challenge";
    private static final String CONNECTION = "Connection";

    private static final ConfigProperty<String> REALM = ArchaiusUtil.getStringProperty("api.auth.realm");

    AuthDao authDao;
    @Inject
    AdminAuthLookUp adminAuthLookUp;
    @Inject
    ObjectManager objectManager;
    @Inject
    TokenAuthLookup tokenAuthLookUp;
    @Inject
    List<ApiKeyAuditSink> auditSinks = java.util.Collections.emptyList();

    @Override
    public Account getAccount(ApiRequest request) {
        String header = request.getServletContext().getRequest().getHeader(AUTH_HEADER);
        String[] auth = getUsernamePassword(header);
        if (auth == null) {
            if (isBasicHeader(header)) {
                ApiKeyAuthenticationAudit.deny(request, auditSinks, "MalformedBasicCredentials");
            }
            return null;
        }
        VerifiedApiCredential verified;
        try {
            verified = authDao.getVerifiedApiCredential(auth[0], auth[1], ApiContext.getContext().getTransformationService());
        } catch (RuntimeException failure) {
            ApiKeyAuthenticationAudit.deny(request, auditSinks,
                    failure instanceof ClientVisibleException visible ? visible.getCode() : "AuthenticationUnavailable");
            throw failure;
        }
        if (verified != null && verified.context() != null) {
            ApiKeyCredentialContext.attach(request, verified.context());
            if (verified.denialCode() != null) {
                ApiKeyAuthenticationAudit.deny(request, auditSinks, verified.denialCode());
                throw new ClientVisibleException(ResponseCodes.FORBIDDEN, verified.denialCode());
            }
        }
        Account account = verified == null ? null : verified.account();
        if (account != null) {
            if (verified.context() != null) {
                ApiKeyPolicy policy = verified.context().policy();
                if (policy != null && policy.getExpiresAt() != null
                        && !java.time.Instant.now().isBefore(policy.getExpiresAt())) {
                    ApiKeyAuthenticationAudit.deny(request, auditSinks, "ApiKeyExpired");
                    throw new ClientVisibleException(ResponseCodes.FORBIDDEN, "ApiKeyExpired");
                }
            }
            return switchAccount(account, request);
        } else if (auth[0].toLowerCase().startsWith(ProjectConstants.OAUTH_BASIC.toLowerCase()) && SecurityConstants.SECURITY.get()) {
            String[] splits = auth[0].split("=");
            String projectId = splits.length == 2 ? splits[1] : null;
            request.setAttribute(ProjectConstants.PROJECT_HEADER, projectId);
            account = tokenAuthLookUp.getAccountAccess(ProjectConstants.AUTH_TYPE + auth[1], request);
        } else if (auth[0].toLowerCase().startsWith(ProjectConstants.OAUTH_BASIC.toLowerCase()) && !SecurityConstants.SECURITY.get()) {
            String[] splits = auth[0].split("=");
            String projectId = splits.length == 2 ? splits[1] : null;
            request.setAttribute(ProjectConstants.PROJECT_HEADER, projectId);
            account = adminAuthLookUp.getAccount(request);
        }
        if (account == null && ApiKeyCredentialContext.get(request) == null) {
            ApiKeyAuthenticationAudit.deny(request, auditSinks, "InvalidApiCredential");
        }
        return account;
    }

    protected Account switchAccount(Account account, ApiRequest request) {
        boolean shouldSwitch = DataAccessor.fromDataFieldOf(account)
            .withKey(AccountConstants.DATA_ACT_AS_RESOURCE_ACCOUNT)
            .withDefault(false)
                .as(Boolean.class);

        boolean projectAdmin = false;
        if (DataAccessor.fromDataFieldOf(account)
                .withKey(AccountConstants.DATA_ACT_AS_RESOURCE_ADMIN_ACCOUNT)
                .withDefault(false)
                .as(Boolean.class)) {
            shouldSwitch = true;
            projectAdmin = true;
        }

        if (shouldSwitch) {
            Long agentOwnerId = DataAccessor.fromDataFieldOf(account).withKey(AccountConstants.DATA_AGENT_OWNER_ID).as(Long.class);
            Agent agent = null;
            if (agentOwnerId != null) {
                agent = objectManager.findAny(Agent.class, AGENT.ID, agentOwnerId);
            } else {
                agent = objectManager.findAny(Agent.class, AGENT.ACCOUNT_ID, account.getId()); 
            }

            if (agent != null) {
                Long resourceAccId = DataAccessor.fromDataFieldOf(agent)
                        .withKey(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID)
                        .as(Long.class);
                if (resourceAccId != null) {
                    List<Object> activeStates = new ArrayList<>();
                    activeStates.add(CommonStatesConstants.ACTIVE);
                    activeStates.add(ServiceConstants.STATE_UPGRADING);
                    Account resourceAccount = objectManager.findAny(Account.class,
                            ACCOUNT.ID, resourceAccId,
                            ACCOUNT.STATE, new Condition(ConditionType.IN, activeStates));
                    if (resourceAccount == null) {
                        return null;
                    }
                    if (projectAdmin) {
                        resourceAccount.setKind("projectadmin");
                    } else {
                        resourceAccount.setKind("environment");
                    }
                    return resourceAccount;
                }
            }
        }

        return account;
    }

    @Override
    public boolean challenge(ApiRequest request) {
        if ("upgrade".equalsIgnoreCase(request.getServletContext().getRequest().getHeader(CONNECTION))) {
            return false;
        }
        if ("true".equalsIgnoreCase(request.getServletContext().getRequest().getHeader(NO_CHALLENGE_HEADER))) {
            return false;
        }
        HttpServletResponse response = request.getServletContext().getResponse();
        String realm = REALM.get();

        if (realm == null) {
            response.setHeader(CHALLENGE_HEADER, BASIC);
        } else {
            response.setHeader(CHALLENGE_HEADER, String.format(BASIC_REALM, realm));
        }

        return true;
    }

    protected String getRealm(ApiRequest request) {
        return REALM.get();
    }

    public static String[] getUsernamePassword(ApiRequest request) {
        return getUsernamePassword(request.getServletContext().getRequest().getHeader(AUTH_HEADER));
    }

    public static String[] getUsernamePassword(String auth) {
        if (auth == null)
            return null;

        String[] parts = StringUtils.split(auth);

        if (parts.length != 2) {
            return null;
        }

        if (!parts[0].equalsIgnoreCase(BASIC))
            return null;

        try {
            String text = new String(Base64.decodeBase64(parts[1]), "UTF-8");
            int i = text.indexOf(":");
            if (i == -1) {
                return null;
            }

            return new String[]{text.substring(0, i), text.substring(i + 1)};
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    @Override
    public int getPriority() {
        return Priority.DEFAULT;
    }

    public AuthDao getAuthDao() {
        return authDao;
    }

    @Inject
    public void setAuthDao(AuthDao authDao) {
        this.authDao = authDao;
    }

    public static boolean isBasicHeader(String header) {
        String[] parts = StringUtils.split(header);
        return parts != null && parts.length > 0 && BASIC.equalsIgnoreCase(parts[0]);
    }

    public void setAuditSinks(List<ApiKeyAuditSink> sinks) {
        auditSinks = sinks == null ? java.util.Collections.emptyList() : List.copyOf(sinks);
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String getName() {
        return "BasicAuth";
    }
}
