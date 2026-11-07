package io.cattle.platform.iaas.api.auth.integration.internal.rancher;

import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.AbstractTokenUtil;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.integration.external.ExternalServiceTokenUtil;
import io.cattle.platform.iaas.api.auth.integration.interfaces.AccountLookup;
import io.cattle.platform.iaas.api.auth.integration.interfaces.TokenUtil;
import io.cattle.platform.util.type.NamedUtils;
import io.cattle.platform.util.type.Priority;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

public class TokenAuthLookup implements AccountLookup, Priority {

    Map<String, TokenUtil> tokenUtilsMap = new HashMap<String, TokenUtil>();
    @Inject
    List<TokenUtil> tokenUtils;

    @Inject
    ExternalServiceTokenUtil externalTokenUtil;

    @Override
    public int getPriority() {
        return Priority.PRE;
    }

    @PostConstruct
    public void init() {
        for (TokenUtil tu : tokenUtils) {
            tokenUtilsMap.put(NamedUtils.getName(tu), tu);
        }
    }

    @Override
    public Account getAccount(ApiRequest request) {
        if (!Strings.CS.equals(AbstractTokenUtil.TOKEN, request.getType()) && isConfigured()) {
            tokenUtils().findAndSetJWT();
            Account account = getAccountAccessInternal();
            if (account != null) io.cattle.platform.api.auth.ApiCookieAuthentication.verified(request, tokenUtils().getJWT());
            return account;
        }
        return null;
    }

    @Inject io.cattle.platform.iaas.api.auth.dao.AuthTokenDao sessionTokens;

    /** Opaque, request-local authority. Never serialized or used as a login credential. */
    public static final class SessionAuthority {
        private final ApiRequest request;
        private final Long id, actor, tokenAccount;
        private final String provider, generation;
        private final byte[] valueDigest;
        private SessionAuthority(ApiRequest request, io.cattle.platform.core.model.AuthToken token) {
            this.request = request; id = token.getId(); actor = token.getAuthenticatedAsAccountId();
            tokenAccount = token.getAccountId(); provider = token.getProvider(); generation = token.getClientSessionId();
            valueDigest = digest(token.getValue());
        }
    }

    public SessionAuthority captureVerifiedSession(ApiRequest request, long expectedActor) {
        var token = currentSession(request);
        if (expectedActor <= 0 || !Long.valueOf(expectedActor).equals(token.getAuthenticatedAsAccountId())) throw sessionDenied();
        SessionAuthority authority = new SessionAuthority(request, token);
        requireCurrentSession(authority, request);
        return authority;
    }

    public void requireCurrentSession(SessionAuthority expected, ApiRequest request) {
        if (expected == null || expected.request != request) throw sessionDenied();
        var token = currentSession(request);
        if (!java.util.Objects.equals(expected.id, token.getId())
                || !java.util.Objects.equals(expected.actor, token.getAuthenticatedAsAccountId())
                || !java.util.Objects.equals(expected.tokenAccount, token.getAccountId())
                || !java.util.Objects.equals(expected.provider, token.getProvider())
                || !java.util.Objects.equals(expected.generation, token.getClientSessionId())
                || !java.security.MessageDigest.isEqual(expected.valueDigest, digest(token.getValue()))) throw sessionDenied();
        try {
            Account actor = getAccountAccess(cookie(request), request);
            if (actor == null || !expected.actor.equals(actor.getId())) throw sessionDenied();
        } catch (ClientVisibleException invalid) { throw sessionDenied(); }
    }

    private io.cattle.platform.core.model.AuthToken currentSession(ApiRequest request) {
        var context = io.github.ibuildthecloud.gdapi.context.ApiContext.getContext();
        if (context == null || context.getApiRequest() != request
                || !io.cattle.platform.api.auth.ApiCookieAuthentication.isVerified(request)
                || io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext.get(request) != null
                || request.getServletContext().getRequest().getHeader("Authorization") != null) throw sessionDenied();
        var token = sessionTokens.getTokenByKey(cookie(request));
        if (token == null || token.getId() == null || token.getId() <= 0 || token.getAccountId() == null || token.getAccountId() <= 0
                || token.getAuthenticatedAsAccountId() == null || token.getAuthenticatedAsAccountId() <= 0
                || !SecurityConstants.TOKEN_VERSION.equals(token.getVersion())
                || !org.apache.commons.lang3.StringUtils.equalsIgnoreCase(SecurityConstants.AUTH_PROVIDER.get(), token.getProvider())
                || token.getExpires() == null || !token.getExpires().after(new java.util.Date())
                || token.getValue() == null || token.getValue().isBlank()) throw sessionDenied();
        return token;
    }

    private static String cookie(ApiRequest request) {
        String selected = null;
        var cookies = request.getServletContext().getRequest().getCookies();
        if (cookies != null) for (var value : cookies) if ("token".equalsIgnoreCase(value.getName())) {
            if (selected != null || value.getValue() == null || value.getValue().isBlank()
                    || value.getValue().length() > 32768) throw sessionDenied();
            selected = value.getValue();
        }
        if (selected == null) throw sessionDenied();
        return selected;
    }

    private static byte[] digest(String value) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Session authority unavailable"); }
    }
    private static ClientVisibleException sessionDenied() { return new ClientVisibleException(403, "SessionAuthorityChanged"); }

    private Account getAccountAccessInternal() {
        return tokenUtils().getAccountFromJWT();
    }

    public Account getAccountAccess(String token, ApiRequest request) {
        if (!Strings.CS.equals(AbstractTokenUtil.TOKEN, request.getType()) && isConfigured()) {
            request.setAttribute(tokenUtils().tokenType(), token);
            return getAccountAccessInternal();
        }
        return null;
    }

    private TokenUtil tokenUtils(){
        if (SecurityConstants.INTERNAL_AUTH_PROVIDERS.contains(SecurityConstants.AUTH_PROVIDER.get())) {
            TokenUtil tokenUtil = tokenUtilsMap.get(SecurityConstants.AUTH_PROVIDER.get());
            if (tokenUtil == null || !tokenUtil.isConfigured()) {
                throw new ClientVisibleException(ResponseCodes.INTERNAL_SERVER_ERROR, "TokenUtilNotConfigured");
            }
            return tokenUtil;
        } else {
            return externalTokenUtil;
        }
    }

    @Override
    public boolean challenge(ApiRequest request) {
        return false;
    }

    @Override
    public String getName() {
        return "TokenAuth";
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.isNotBlank(SecurityConstants.AUTH_PROVIDER.get())
                && !SecurityConstants.NO_PROVIDER.equalsIgnoreCase(SecurityConstants.AUTH_PROVIDER.get());
    }

}
