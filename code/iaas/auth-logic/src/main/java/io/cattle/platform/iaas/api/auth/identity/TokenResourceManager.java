package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.pubsub.manager.SubscribeManager;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.iaas.api.auth.AbstractTokenUtil;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.iaas.api.auth.integration.external.ExternalServiceAuthProvider;
import io.cattle.platform.iaas.api.auth.integration.interfaces.TokenCreator;
import io.cattle.platform.iaas.api.auth.integration.internal.rancher.TokenAuthLookup;
import io.cattle.platform.iaas.api.auth.integration.local.LocalAuthConstants;
import io.cattle.platform.iaas.api.auth.mfa.MfaService;
import io.cattle.platform.iaas.event.IaasEvents;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.token.TokenService;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

public class TokenResourceManager extends AbstractNoOpResourceManager {

    public static final String PROVIDER_SWITCH = "providerSwitch";

    @Inject
    ObjectManager objectManager;

    @Inject
    AuthTokenDao authTokenDao;

    @Inject
    IdentityManager identityManager;

    @Inject
    ExternalServiceAuthProvider externalAuthProvider;

    @Inject
    TokenService tokenService;

    @Inject
    TokenAuthLookup tokenAuthLookup;

    @Inject
    AuthDao authDao;

    @Inject
    AccountDao accountDao;

    @Inject
    EventService eventService;

    @Inject
    ProviderSwitchTokenService providerSwitchTokenService;

    @Inject
    MfaService mfaService;

    private List<TokenCreator> tokenCreators;
    private static final ConfigProperty<Boolean> RESTRICT_CONCURRENT_SESSIONS = ArchaiusUtil.getBooleanProperty("api.auth.restrict.concurrent.sessions");

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[]{Token.class};
    }

    @Override
    protected Object createInternal(String type, ApiRequest request) {
        if (!Strings.CS.equals(AbstractTokenUtil.TOKEN, request.getType())) {
            return null;
        }
        return createToken(request);
    }

    private Token createToken(ApiRequest request) {
        Token token = null;
        Map<String, Object> requestBody = CollectionUtils.toMap(request.getRequestObject());
        String requestedProvider = ObjectUtils.toString(requestBody.get("authProvider"));
        String code = ObjectUtils.toString(requestBody.get("code"));
        String providerSwitchCode = ObjectUtils.toString(requestBody.get("providerSwitchCode"));
        boolean resumedMfa = MfaService.PROVIDER.equalsIgnoreCase(requestedProvider);
        boolean localRecovery = LocalAuthConstants.CONFIG.equalsIgnoreCase(requestedProvider)
                && !LocalAuthConstants.CONFIG.equalsIgnoreCase(SecurityConstants.AUTH_PROVIDER.get())
                && SecurityConstants.SECURITY.get()
                && LocalAuthConstants.RECOVERY_ENABLED.get();

        if (SecurityConstants.AUTH_PROVIDER.get() == null || SecurityConstants.NO_PROVIDER.equalsIgnoreCase(SecurityConstants.AUTH_PROVIDER.get())) {
            throw new ClientVisibleException(ResponseCodes.INTERNAL_SERVER_ERROR,
                    "NoAuthProvider", "No Auth provider is configured.", null);
        }

        if (resumedMfa) {
            token = mfaService.completeLogin(requestBody);
        } else if (PROVIDER_SWITCH.equalsIgnoreCase(requestedProvider)) {
            token = providerSwitchTokenService.consume(code);
        } else if (localRecovery) {
            for (TokenCreator tokenCreator : tokenCreators) {
                if (LocalAuthConstants.CONFIG.equalsIgnoreCase(tokenCreator.providerType())
                        && tokenCreator.isConfigured()) {
                    token = tokenCreator.getToken(request);
                    break;
                }
            }
            if (token != null) {
                Account recoveryAccount = authDao.getAccountById(token.getAuthenticatedAsAccountId());
                if (recoveryAccount == null
                        || !accountDao.isActiveAccount(recoveryAccount)
                        || !io.cattle.platform.core.constants.AccountConstants.ADMIN_KIND.equalsIgnoreCase(
                        recoveryAccount.getKind())) {
                    throw new ClientVisibleException(ResponseCodes.FORBIDDEN, "LocalRecoveryAdminOnly",
                            "Local recovery login is restricted to active system administrators.", null);
                }
                // Session records remain associated with the active provider so
                // a provider switch still invalidates all previous sessions.
                token.setAuthProvider(SecurityConstants.AUTH_PROVIDER.get());
                token.setLoginMethod("localRecovery");
            }
        } else if (SecurityConstants.INTERNAL_AUTH_PROVIDERS.contains(SecurityConstants.AUTH_PROVIDER.get())) {
            for (TokenCreator tokenCreator : tokenCreators) {
                if (tokenCreator.isConfigured() && tokenCreator.providerType().equalsIgnoreCase(SecurityConstants.AUTH_PROVIDER.get())) {
                    if (!SecurityConstants.SECURITY.get()) {
                        tokenCreator.reset();
                    }
                    token = tokenCreator.getToken(request);
                    if (token != null) {
                        token.setLoginMethod("primary");
                    }
                    break;
                }
            }
        } else {
            //call external service
            token = externalAuthProvider.getToken(request);
            if (token != null) {
                token.setLoginMethod("primary");
            }
        }

        if (token == null){
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST,
                    "codeInvalid", "Code provided is invalid.", null);
        }
        if (!resumedMfa) {
            Identity[] identities = token.getIdentities();
            List<Identity> transFormedIdentities = new ArrayList<>();
            for (Identity identity : identities) {
                transFormedIdentities.add(identityManager.untransform(identity, true));
            }
            token.setIdentities(transFormedIdentities);
            token.setUserIdentity(identityManager.untransform(token.getUserIdentity(), true));

            if (StringUtils.isNotBlank(providerSwitchCode)) {
                providerSwitchTokenService.authorizeActivation(providerSwitchCode, token);
            }
            token = mfaService.beginLogin(token);
            if (Boolean.TRUE.equals(token.getMfaRequired())) {
                return token;
            }
        }
        if (Boolean.TRUE.equals(token.getMfaRequired())) {
            return token;
        }

        long authenticatedAsAccountId = token.getAuthenticatedAsAccountId();
        long tokenAccountId = ((Policy) ApiContext.getContext().getPolicy()).getAccountId();

        if (RESTRICT_CONCURRENT_SESSIONS.get()) {
            authTokenDao.deletePreviousTokens(authenticatedAsAccountId, tokenAccountId);
            String event = IaasEvents.appendAccount(SubscribeManager.EVENT_DISCONNECT, authenticatedAsAccountId);
            eventService.publish(EventVO.newEvent(event));
        }

        token.setJwt(authTokenDao.createToken(token.getJwt(), token.getAuthProvider(),
                ((Policy) ApiContext.getContext().getPolicy()).getAccountId(), authenticatedAsAccountId).getKey());

        return token;
    }

    @Override
    protected Object listInternal(SchemaFactory schemaFactory, String type, Map<Object, Object> criteria, ListOptions options) {
        Token token = listToken();
        return Collections.singletonList(token);
    }

    protected Token listToken() {
        Token token = new Token();

        if (SecurityConstants.AUTH_PROVIDER.get() == null || SecurityConstants.NO_PROVIDER.equalsIgnoreCase(SecurityConstants.AUTH_PROVIDER.get())) {
            return decorateLoginOptions(token);
        }

        if (SecurityConstants.INTERNAL_AUTH_PROVIDERS.contains(SecurityConstants.AUTH_PROVIDER.get())) {
            for (TokenCreator tokenCreator : tokenCreators) {
                if (tokenCreator.isConfigured() && tokenCreator.providerType().equalsIgnoreCase(SecurityConstants.AUTH_PROVIDER.get())) {
                    token = tokenCreator.getCurrentToken();
                    break;
                }
            }
            return decorateLoginOptions(token);
        } else {
            //get redirect Url from external service
            if (externalAuthProvider.isConfigured()) {
                return decorateLoginOptions(externalAuthProvider.readCurrentToken());
            }
        }
        return decorateLoginOptions(token);
    }

    private Token decorateLoginOptions(Token token) {
        if (token == null) {
            token = new Token();
        }
        token.setLocalRecoveryEnabled(SecurityConstants.SECURITY.get()
                && LocalAuthConstants.RECOVERY_ENABLED.get()
                && !LocalAuthConstants.CONFIG.equalsIgnoreCase(SecurityConstants.AUTH_PROVIDER.get()));
        return token;
    }

    public List<TokenCreator> getTokenCreators() {
        return tokenCreators;
    }

    @Inject
    public void setTokenCreators(List<TokenCreator> tokenCreators) {
        this.tokenCreators = tokenCreators;
    }

    @Override
    protected Object deleteInternal(String type, String id, Object obj, ApiRequest request) {
        if (!Strings.CS.equals(AbstractTokenUtil.TOKEN, request.getType())) {
            return null;
        }
        return deleteToken(obj, request);
    }

    protected Object deleteToken(Object obj, ApiRequest request) {
        Token token = new Token();
        String jwt = "";

        token = listToken();
        jwt = token.getJwt();

        if(StringUtils.isBlank(jwt)) {
            throw new ClientVisibleException(ResponseCodes.INTERNAL_SERVER_ERROR,
                    "JWTNotProvided", "Request does not contain JWT cookie", null);
        }

        request.setResponseCode(ResponseCodes.NO_CONTENT);
        HttpServletResponse response = request.getServletContext().getResponse();
        String cookieString="token=;Path=/;Expires=Thu, 01 Jan 1970 00:00:00 GMT;Max-Age=0;Secure;HttpOnly;SameSite=Lax";
        response.addHeader("Set-Cookie", cookieString);
        request.getServletContext().setResponse(response);
        if(authTokenDao.deleteToken(jwt)) {
            return obj;
        }
        return null;
    }
}
