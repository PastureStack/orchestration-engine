package io.cattle.platform.iaas.api.request.handler;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.MachineDriver;
import io.cattle.platform.iaas.api.servlet.filter.ProxyPreFilter;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.util.net.UrlUtils;
import io.cattle.platform.util.type.Named;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractResponseGenerator;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;


public class GenericWhitelistedProxy extends AbstractResponseGenerator implements Named {

    public static final String ALLOWED_HOST = GenericWhitelistedProxy.class.getName() + "allowed.host";
    public static final String SET_HOST_CURRENT_HOST = GenericWhitelistedProxy.class.getName() + "set_host_current_host";
    public static final String REDIRECTS = GenericWhitelistedProxy.class.getName() + "redirects";
    public static final String PARSE_FORM = GenericWhitelistedProxy.class.getName() + "parseform";
    public static final String REQUIRE_ROLE = GenericWhitelistedProxy.class.getName() + "roles";
    public static final String METHOD_ROLE = GenericWhitelistedProxy.class.getName() + "methodRoles";

    private static final ProxySettings DEFAULT_SETTINGS = ArchaiusProxySettings.create();

    private static final String FORWARD_PROTO = "X-Forwarded-Proto";
    private static final String API_AUTH = "X-API-AUTH-HEADER";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final Set<String> MANAGED_HEADERS = new HashSet<>(Arrays.asList("host", "connection", "content-length",
            "expect", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
            "transfer-encoding", "upgrade", API_AUTH.toLowerCase()));
    private static final String AUTH_ACCESS_TOKEN = "access_token";

    private List<String> allowedPaths;
    private boolean noAuthProxy = false;
    private String name;
    private final ProxySettings settings;

    Cache<String, Boolean> allowCache = CacheBuilder.newBuilder()
            .expireAfterAccess(java.time.Duration.ofHours(24))
            .maximumSize(100)
            .build();

    @Inject
    ObjectManager objectManager;

    public GenericWhitelistedProxy(String name) {
        this(name, DEFAULT_SETTINGS);
    }

    GenericWhitelistedProxy(String name, ProxySettings settings) {
        super();
        if (settings == null) {
            throw new IllegalArgumentException("Proxy settings are required");
        }
        this.name = name;
        this.settings = settings;
    }

    protected boolean isAllowed(HttpServletRequest servletRequest, String host) {
        boolean allowHost = Boolean.TRUE.equals(servletRequest.getAttribute(ALLOWED_HOST));
        if (allowHost) {
            return true;
        }

        if (isWhitelisted(host)) {
            return true;
        }

        Boolean value = allowCache.getIfPresent(host);
        if (value == null) {
            List<MachineDriver> drivers = objectManager.find(MachineDriver.class, ObjectMetaDataManager.STATE_FIELD,
                    new Condition(ConditionType.NE, CommonStatesConstants.PURGED));
            for (MachineDriver driver : drivers) {
                String url = DataAccessor.fieldString(driver, "uiUrl");
                if (url != null) {
                    try {
                        URL parsed = UrlUtils.toURL(url);
                        allowCache.put(parsed.getHost(), true);
                    } catch (MalformedURLException e) {
                    }
                }
            }
        }
        value = allowCache.getIfPresent(host);
        return value == null ? false : value;
    }

    @Override
    protected void generate(final ApiRequest request) throws IOException {
        if (!settings.allowProxy())
            return;

        if (!"proxy".equals(request.getType())) {
            return;
        }

        HttpServletRequest servletRequest = request.getServletContext().getRequest();
        boolean setCurrentHost = Boolean.TRUE.equals(servletRequest.getAttribute(SET_HOST_CURRENT_HOST));
        boolean redirects = shouldFollowRedirects(servletRequest.getAttribute(REDIRECTS));
        boolean parseForm = Boolean.TRUE.equals(servletRequest.getAttribute(PARSE_FORM));
        Set<?> requiredRoles = setAttribute(servletRequest, REQUIRE_ROLE);
        Set<?> methodRoles = setAttribute(servletRequest, METHOD_ROLE);

        String redirect = servletRequest.getRequestURI();
        redirect = StringUtils.substringAfter(redirect, "/proxy/");
        if (redirect.startsWith("http")) {
            /* We don't allow // so http:// will be http:/ and same with https. So we fixup here */
            redirect = redirect.replaceFirst("^http:/([^/])", "http://$1");
            redirect = redirect.replaceFirst("^https:/([^/])", "https://$1");
        }

        if (!Strings.CS.startsWith(redirect, "http")) {
            redirect = "https://" + redirect;
        }

        URI uri;
        try {
            uri = new URI(redirect);
        } catch (URISyntaxException e) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidRedirect", "The redirect is invalid/empty", null);
        }
        String queryInfo = servletRequest.getQueryString();
        if (queryInfo != null) {
            try {
                uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                        URLDecoder.decode(queryInfo, StandardCharsets.UTF_8), uri.getFragment());
            } catch (URISyntaxException e) {
                throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidRedirect", "The redirect query is invalid", null);
            }
        }
        redirect = uri.toString();

        if (!isProxyableScheme(uri.getScheme()) || StringUtils.isBlank(uri.getHost())) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidRedirect",
                    "The redirect is invalid/empty", null);
        }

        String host = uri.getPort() > 0 ? String.format("%s:%s", uri.getHost(), uri.getPort()) : uri.getHost();

        if (!isAllowed(servletRequest, host)) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN);
        }

        boolean matchesAllowedPath = false;

        if(isNoAuthProxy()) {
            if (uri.getPath() != null && allowedPaths != null) {
                for(String path : allowedPaths) {
                    if(uri.getPath().startsWith(path)) {
                        matchesAllowedPath = true;
                    }
                }

            }
            if(!matchesAllowedPath){
                return;
            }
        }

        JdkProxyRequest temp;
        String method = servletRequest.getMethod();
        if (servletRequest instanceof ProxyPreFilter.Request) {
            method = ((ProxyPreFilter.Request)servletRequest).getRealMethod();
        }

        switch (method) {
        case "POST":
        case "GET":
        case "PUT":
        case "DELETE":
        case "HEAD":
            temp = new JdkProxyRequest(method, redirect);
            break;
        default:
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "Invalid method", "The method " + method + " is not supported", null);
        }
        temp.setConnectTimeoutMillis(settings.connectTimeoutMillis());
        temp.setRequestTimeoutMillis(settings.requestTimeoutMillis());

        // This isn't always available. As is the case for proxy protocol
        String xForwardedProto = servletRequest.getHeader(FORWARD_PROTO);
        if (xForwardedProto == null && request.getRequestUrl() != null && request.getRequestUrl().startsWith("https")) {
            temp.addHeader(FORWARD_PROTO, "https");
        }

        boolean parseFormContent = false;
        for (String headerName : Collections.list(servletRequest.getHeaderNames())) {
            if (MANAGED_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String headerVal : Collections.list(servletRequest.getHeaders(headerName))) {
                if (CONTENT_TYPE.equalsIgnoreCase(headerName)) {
                    parseFormContent = parseFormContent || shouldParseFormContent(parseForm, headerVal);
                }
                temp.addHeader(headerName, Strings.CS.removeStart(headerVal, "rancher:"));
            }
        }

        String authHeader = servletRequest.getHeader(API_AUTH);
        if (authHeader != null) {
            temp.setHeader("Authorization", authHeader);
        } else {
            if (uri.getPath() != null && uri.getPath().startsWith("/v1-auth/")) {
                //set the auth service access token
                String externalAccessToken = (String) request.getAttribute(AUTH_ACCESS_TOKEN);
                if(!StringUtils.isBlank(externalAccessToken)) {
                    String bearerToken = " Bearer "+ externalAccessToken;
                    temp.setHeader("Authorization", bearerToken);
                }
            }
        }

        if (setCurrentHost) {
            temp.setHeader("Host", request.getResponseUrlBase().replaceFirst("^https?://", ""));
        } else {
            temp.setHeader("Host", host);
        }

        String projectHeader = "";
        Set<String> roles = null;
        String roleString = "";
        Policy policy = ApiUtils.getPolicy();
        if (policy != null) {
            projectHeader = ApiContext.getContext().getIdFormatter()
                    .formatId(AccountConstants.TYPE, Long.toString(policy.getAccountId()))
                    .toString();
            roles = policy.getRoles();
            roleString = StringUtils.join(roles, ",");
        }
        temp.setHeader(ProjectConstants.PROJECT_HEADER, projectHeader);
        temp.setHeader(ProjectConstants.ROLES_HEADER, roleString);

        authorize(method, requiredRoles, roles, methodRoles);

        if ("POST".equals(method) || "PUT".equals(method)) {
            if (parseFormContent) {
                temp.bodyForm(servletRequest.getParameterMap());
            } else {
                temp.body(request.getInputStream(), servletRequest.getContentLengthLong());
            }
        }

        JdkProxyRequest.ProxyResponse response = temp.execute(redirects);
        int statusCode = response.getStatusCode();
        request.setResponseObject(new Object());
        request.setResponseCode(statusCode);
        for (Map.Entry<String, List<String>> header : response.getHeaders().entrySet()) {
            for (String value : header.getValue()) {
                request.getServletContext().getResponse().addHeader(header.getKey(), value);
            }
        }
        request.commit();
        OutputStream writer = request.getServletContext().getResponse().getOutputStream();
        try (InputStream body = response.getBody()) {
            if (body != null) {
                body.transferTo(writer);
            }
        }
    }

    static Set<?> setAttribute(HttpServletRequest servletRequest, String attribute) {
        Object value = servletRequest.getAttribute(attribute);
        return value == null ? null : Set.class.cast(value);
    }

    static boolean shouldParseFormContent(boolean parseForm, String contentType) {
        if (!parseForm || StringUtils.isBlank(contentType)) {
            return false;
        }

        String mimeType = StringUtils.substringBefore(contentType, ";").trim();
        return JdkProxyRequest.APPLICATION_FORM_URLENCODED.equalsIgnoreCase(mimeType);
    }

    static boolean shouldFollowRedirects(Object redirectsAttribute) {
        return Boolean.TRUE.equals(redirectsAttribute);
    }

    static boolean isProxyableScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    static void authorize(String method, Set<?> requiredRoles, Set<String> roles, Set<?> methods) {
        if (methods != null && methods.size() > 0) {
            if (!methods.contains(method)) {
                return;
            }
        }

        if (requiredRoles == null || requiredRoles.isEmpty()) {
            return;
        }

        boolean ok = false;
        if (roles != null) {
            for (String role : roles) {
                if (requiredRoles.contains(role)) {
                    ok = true;
                    break;
                }
            }
        }

        if (!ok) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN);
        }
    }

    boolean isWhitelisted(String host) {
        for (String valid : settings.whitelist()) {
            if (valid.equals(host)) {
                return true;
            }

            if (valid.startsWith("*") && host.endsWith(valid.substring(1))) {
                return true;
            }
        }

        return false;
    }

    public List<String> getAllowedPaths() {
        return allowedPaths;
    }

    public void setAllowedPaths(List<String> allowedPaths) {
        this.allowedPaths = allowedPaths;
    }

    public boolean isNoAuthProxy() {
        return noAuthProxy;
    }

    public void setNoAuthProxy(String noAuthProxy) {
        this.noAuthProxy = Boolean.parseBoolean(noAuthProxy);
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }
}
