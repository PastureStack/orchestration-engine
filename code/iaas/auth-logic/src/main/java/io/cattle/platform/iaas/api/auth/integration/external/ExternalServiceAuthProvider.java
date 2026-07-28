package io.cattle.platform.iaas.api.auth.integration.external;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.AuthToken;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.iaas.api.auth.identity.Token;
import io.cattle.platform.iaas.api.auth.integration.util.AuthHttpClient;
import io.cattle.platform.iaas.api.auth.integration.util.AuthHttpClient.Response;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.token.TokenException;
import io.cattle.platform.token.TokenService;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class ExternalServiceAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(ExternalServiceAuthProvider.class);
    private static final String GENERIC_ERROR_MESSAGE = "Error communicating with authentication provider";
    private static final String UNAUTHORIZED_ERROR_MESSAGE = "Username or Password incorrect";
    private static final String FORBIDDEN_ERROR_MESSAGE = "Your account does not have access";

    @Inject
    private JsonMapper jsonMapper;

    @Inject
    TokenService tokenService;

    @Inject
    ExternalServiceTokenUtil tokenUtil;
    @Inject
    private AuthTokenDao authTokenDao;

    public Token getToken(ApiRequest request) {
        Map<String, Object> requestBody = CollectionUtils.toMap(request.getRequestObject());
        String code = ObjectUtils.toString(requestBody.get(SecurityConstants.CODE));

        //get the token from the auth service
        StringBuilder authUrl = new StringBuilder(ServiceAuthConstants.AUTH_SERVICE_URL.get());
        authUrl.append("/token");

        try {
            Map<String, String> data = new HashMap<String, String>();
            data.put("code", code);
            String jsonString = jsonMapper.writeValueAsString(data);

            Response response = AuthHttpClient.postJson(authUrl.toString(), jsonString,
                    ServiceAuthConstants.ACCEPT, ServiceAuthConstants.APPLICATION_JSON);
            Map<String, Object> jsonData = readTokenResponse(response, true);

            String encryptedToken = (String)jsonData.get(ServiceAuthConstants.JWT_KEY);
            Map<String, Object> decryptedToken = tokenService.getJsonPayload(encryptedToken, false);
            String originalLogin = (String)jsonData.get("originalLogin");
            String accessToken = (String)decryptedToken.get("access_token");
            request.setAttribute(ServiceAuthConstants.ACCESS_TOKEN, accessToken);
            List<?> identityList = CollectionUtils.toList(jsonData.get("identities"));
            Set<Identity> identities = new HashSet<>();
            if (identityList != null && !identityList.isEmpty())
            {
                for(Object identity : identityList) {
                    Map<String, Object> jsonIdentity = CollectionUtils.toMap(identity);
                    identities.add(tokenUtil.jsonToIdentity(jsonIdentity));
                }
            }

            Token token = tokenUtil.createToken(identities, null, originalLogin);
            return token;
        } catch (IOException e) {
            if (isAuthServiceConnectionFailure(e)) {
                return null;
            }
            log.error("Failed to get token from Auth Service.", e);
            throw new ClientVisibleException(ResponseCodes.INTERNAL_SERVER_ERROR, ServiceAuthConstants.AUTH_ERROR,
                    "Failed to get Auth token.", null);
        } catch (TokenException e) {
            log.error("Failed to decrypt the token from Auth Service.", e);
            return null;
        }
    }

    public Token refreshToken(String accessToken) {
        //get the token from the auth service
        StringBuilder authUrl = new StringBuilder(ServiceAuthConstants.AUTH_SERVICE_URL.get());
        authUrl.append("/token");

        try {
            Map<String, String> data = new HashMap<String, String>();
            data.put("accessToken", accessToken);
            String jsonString = jsonMapper.writeValueAsString(data);

            Response response = AuthHttpClient.postJson(authUrl.toString(), jsonString,
                    ServiceAuthConstants.ACCEPT, ServiceAuthConstants.APPLICATION_JSON);
            Map<String, Object> jsonData = readRefreshTokenResponse(response);

            String encryptedToken = (String)jsonData.get(ServiceAuthConstants.JWT_KEY);
            Map<String, Object> decryptedToken = tokenService.getJsonPayload(encryptedToken, false);
            String newAccessToken = (String)decryptedToken.get("access_token");
            ApiRequest request = ApiContext.getContext().getApiRequest();
            request.setAttribute(ServiceAuthConstants.ACCESS_TOKEN, newAccessToken);

            List<?> identityList = CollectionUtils.toList(jsonData.get("identities"));
            Set<Identity> identities = new HashSet<>();
            if (identityList != null && !identityList.isEmpty())
            {
                for(Object identity : identityList) {
                    Map<String, Object> jsonIdentity = CollectionUtils.toMap(identity);
                    identities.add(tokenUtil.jsonToIdentity(jsonIdentity));
                }
            }
            Token token = tokenUtil.createToken(identities, null, null);
            return token;
        } catch (IOException e) {
            if (isAuthServiceConnectionFailure(e)) {
                return null;
            }
            log.error("Failed to get token from Auth Service.", e);
            throw new ClientVisibleException(ResponseCodes.INTERNAL_SERVER_ERROR, ServiceAuthConstants.AUTH_ERROR,
                    "Failed to get Auth token.", null);
        } catch (TokenException e) {
            log.error("Failed to decrypt the token from Auth Service.", e);
            return null;
        }
    }

    public List<Identity> searchIdentities(String name, boolean exactMatch) {
        if (!isConfigured()) {
            return new ArrayList<Identity>();
        }
        List<Identity> identities = new ArrayList<>();
        StringBuilder authUrl = new StringBuilder(ServiceAuthConstants.AUTH_SERVICE_URL.get());
        try {
            authUrl.append("/identities?name=").append(URLEncoder.encode(name, StandardCharsets.UTF_8));
            String externalAccessToken = (String) ApiContext.getContext().getApiRequest().getAttribute(ServiceAuthConstants.ACCESS_TOKEN);
            String bearerToken = " Bearer "+ externalAccessToken;
            Response response = AuthHttpClient.get(authUrl.toString(),
                    ServiceAuthConstants.ACCEPT, ServiceAuthConstants.APPLICATION_JSON,
                    ServiceAuthConstants.AUTHORIZATION, bearerToken);
            Map<String, Object> jsonData = readNullableResponse("searchIdentities", response);
            if (jsonData == null) {
                return identities;
            }

            List<?> identityList = CollectionUtils.toList(jsonData.get("data"));
            if (identityList != null && !identityList.isEmpty())
            {
                for(Object identity : identityList) {
                    Map<String, Object> jsonIdentity = CollectionUtils.toMap(identity);
                    identities.add(tokenUtil.jsonToIdentity(jsonIdentity));
                }
            }

        } catch (ClientVisibleException e) {
            log.error("Failed to search identities from Auth Service.", e);
        } catch (IOException e) {
            if (!isAuthServiceConnectionFailure(e)) {
                log.error("Failed to search identities from Auth Service.", e);
            }
        } catch (Exception e) {
            log.error("Failed to search identities from Auth Service.", e);
        }
        return identities;
    }

    public Identity getIdentity(String id, String scope) {
        if (!isConfigured()) {
            return null;
        }
        //check if the setting 'support.identity.lookup = false', if yes then lookup the identity from token

        if(ServiceAuthConstants.NO_IDENTITY_LOOKUP_SUPPORTED.get()) {
            // This means it is saml (among github and saml)
            log.debug("Identity lookup is not supported at the provider");
            if (tokenUtil.findAndSetJWT()) {
                Set<Identity> identitiesInToken = tokenUtil.getIdentities();
                log.debug("Found identitiesInToken {}" , identitiesInToken);
                for (Identity identity : identitiesInToken) {
                    if(identity != null && id.equals(identity.getExternalId()) && scope.equals(identity.getExternalIdType())) {
                        if (Strings.CS.equals(identity.getExternalIdType(), ServiceAuthConstants.USER_TYPE.get())) {
                            identity.setUser(true);
                        }
                        return identity;
                    }
                }
            }
        }

        StringBuilder authUrl = new StringBuilder(ServiceAuthConstants.AUTH_SERVICE_URL.get());

        try {
            authUrl.append("/identities?externalId=").append(URLEncoder.encode(id, StandardCharsets.UTF_8)).append("&externalIdType=")
            .append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
            String externalAccessToken = (String) ApiContext.getContext().getApiRequest().getAttribute(ServiceAuthConstants.ACCESS_TOKEN);
            String bearerToken = " Bearer "+ externalAccessToken;
            Response response = AuthHttpClient.get(authUrl.toString(),
                    ServiceAuthConstants.ACCEPT, ServiceAuthConstants.APPLICATION_JSON,
                    ServiceAuthConstants.AUTHORIZATION, bearerToken);
            Map<String, Object> jsonData = readNullableResponse("getIdentity", response);

            if (jsonData == null) {
                return null;
            }
            return tokenUtil.jsonToIdentity(jsonData);

        } catch (IOException e) {
            if (!isAuthServiceConnectionFailure(e)) {
                log.error("Failed to get token from Auth Service.", e);
            }
            return null;
        }
    }

    public Set<Identity> getIdentities(Account account) {
        if (!isConfigured()) {
            return new HashSet<>();
        }
        String accessToken = (String) DataAccessor.fields(account).withKey(ServiceAuthConstants.ACCESS_TOKEN).get();
        if (tokenUtil.findAndSetJWT()) {
            ApiRequest request = ApiContext.getContext().getApiRequest();
            request.setAttribute(ServiceAuthConstants.ACCESS_TOKEN, accessToken);
            return tokenUtil.getIdentities();
        } else if(ServiceAuthConstants.NO_IDENTITY_LOOKUP_SUPPORTED.get()) {
            Set<Identity> identities = new HashSet<>();
            if (Strings.CS.equals(account.getExternalIdType(), ServiceAuthConstants.USER_TYPE.get())) {
                identities.add(new Identity(account.getExternalIdType(), account.getExternalId(), null, null, null, null, true));
            }
            return identities;
        }
        String jwt = null;
        if (SecurityConstants.SECURITY.get() && !StringUtils.isBlank(accessToken)) {
                AuthToken authToken = authTokenDao.getTokenByAccountId(account.getId());
                if (authToken == null) {
                        //refresh token API.
                        Token token = refreshToken(accessToken);
                        if (token != null) {
                            jwt = ProjectConstants.AUTH_TYPE + token.getJwt();
                            authToken = authTokenDao.createToken(token.getJwt(), token.getAuthProvider(), account.getId(), account.getId());
                            jwt = authToken.getKey();
                            accessToken = (String) DataAccessor.fields(account).withKey(ServiceAuthConstants.ACCESS_TOKEN).get();
                        }
                } else {
                    jwt = authToken.getKey();
                }
            }
        if (StringUtils.isBlank(jwt)){
            return Collections.emptySet();
        }
        ApiRequest request = ApiContext.getContext().getApiRequest();
        request.setAttribute(tokenUtil.tokenType(), jwt);
        request.setAttribute(ServiceAuthConstants.ACCESS_TOKEN, accessToken);
        return tokenUtil.getIdentities();
    }

    public boolean isConfigured() {
        if (SecurityConstants.AUTH_PROVIDER.get() != null
                && !SecurityConstants.NO_PROVIDER.equalsIgnoreCase(SecurityConstants.AUTH_PROVIDER.get())
                && !SecurityConstants.INTERNAL_AUTH_PROVIDERS.contains(SecurityConstants.AUTH_PROVIDER.get())
                && ServiceAuthConstants.IS_EXTERNAL_AUTH_PROVIDER.get()) {
            return true;
        }
        return false;
    }

    public Identity untransform(Identity identity) {
        return identity;
    }

    public Identity transform(Identity identity) {
        return identity;
    }

    public String getRedirectUrl() {
        StringBuilder authUrl = new StringBuilder(ServiceAuthConstants.AUTH_SERVICE_URL.get());
        authUrl.append("/redirectUrl");

        try {
            Response response = AuthHttpClient.get(authUrl.toString(),
                    ServiceAuthConstants.ACCEPT, ServiceAuthConstants.APPLICATION_JSON);
            Map<String, Object> jsonData = readNullableResponse("getRedirectUrl", response);

            if( jsonData != null && !jsonData.isEmpty()) {
                if (jsonData.containsKey("redirectUrl")) {
                    return (String)jsonData.get("redirectUrl");
                }
            };
        } catch (IOException e) {
            if (!isAuthServiceConnectionFailure(e)) {
                log.error("Failed to get the redirectUrl from Auth Service.", e);
            }
        }
        return "";
    }

    private Map<String, Object> readTokenResponse(Response response, boolean configuredProviderErrors)
            throws IOException {
        int statusCode = response.getStatusCode();
        if (statusCode >= 300) {
            String message = authServiceMessage(response, statusCode);
            log.error("Got error from Auth service. statusCode: {}, message: {}", statusCode, message);
            if (configuredProviderErrors && SecurityConstants.SECURITY.get() && isConfigured()) {
                if(statusCode == 401) {
                    throw new ClientVisibleException(statusCode, ServiceAuthConstants.AUTH_ERROR,
                            UNAUTHORIZED_ERROR_MESSAGE, null);
                } else if (statusCode == 403) {
                    throw new ClientVisibleException(statusCode, ServiceAuthConstants.AUTH_ERROR,
                            FORBIDDEN_ERROR_MESSAGE, null);
                } else {
                    throw new ClientVisibleException(statusCode, ServiceAuthConstants.AUTH_ERROR,
                        GENERIC_ERROR_MESSAGE, null);
                }
            } else {
                throw new ClientVisibleException(statusCode, ServiceAuthConstants.AUTH_ERROR, message, null);
            }
        }
        return jsonMapper.readValue(response.getBody());
    }

    private Map<String, Object> readRefreshTokenResponse(Response response) throws IOException {
        int statusCode = response.getStatusCode();
        if (statusCode >= 300) {
            String message = authServiceMessage(response, statusCode);
            log.error("Got error from Auth service. statusCode: {}, message: {}", statusCode, message);
            if(statusCode == 401) {
                throw new ClientVisibleException(statusCode, ServiceAuthConstants.AUTH_ERROR,
                        UNAUTHORIZED_ERROR_MESSAGE, null);
            } else {
                throw new ClientVisibleException(statusCode, ServiceAuthConstants.AUTH_ERROR,
                    GENERIC_ERROR_MESSAGE, null);
            }
        }
        return jsonMapper.readValue(response.getBody());
    }

    private Map<String, Object> readNullableResponse(String operation, Response response) throws IOException {
        int statusCode = response.getStatusCode();
        if(statusCode >= 300) {
            log.error("{}: Got error from Auth service. statusCode: {}", operation, statusCode);
            return null;
        }
        return jsonMapper.readValue(response.getBody());
    }

    private String authServiceMessage(Response response, int statusCode) throws IOException {
        String message = "Error Response from Auth service: " + Integer.toString(statusCode);
        if (StringUtils.isNotBlank(response.getBody())) {
            Map<String, Object> respData = jsonMapper.readValue(response.getBody());
            if(respData != null && respData.containsKey("message")) {
                message = (String) respData.get("message");
            }
        }
        return message;
    }

    private boolean isAuthServiceConnectionFailure(IOException e) {
        if (AuthHttpClient.isConnectionFailure(e)) {
            log.error("Auth Service not reachable at [{}]", ServiceAuthConstants.AUTH_SERVICE_URL);
            return true;
        }
        return false;
    }

    public Token readCurrentToken() {
        Token token = new Token();
        token = tokenUtil.retrieveCurrentToken();
        if (token != null) {
            String redirect = getRedirectUrl();
            token.setRedirectUrl(redirect);
        }
        return token;
    }
}
