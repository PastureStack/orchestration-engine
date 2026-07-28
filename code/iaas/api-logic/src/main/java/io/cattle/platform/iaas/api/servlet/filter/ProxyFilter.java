package io.cattle.platform.iaas.api.servlet.filter;

import io.cattle.platform.iaas.api.request.handler.GenericWhitelistedProxy;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

public class ProxyFilter implements Filter {

    static final String AUTH_TOKEN_PATH = "/v1-auth/token";

    String proxy;
    boolean redirects = true;
    boolean parseform = false;
    Set<String> roles;
    Set<String> methods;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        proxy = filterConfig.getInitParameter("proxy");
        String value = filterConfig.getInitParameter("redirects");
        if (StringUtils.isNotBlank(value)) {
            redirects = Boolean.parseBoolean(value);
        }
        String parseFormValue = filterConfig.getInitParameter("parseform");
        if (StringUtils.isNotBlank(parseFormValue)) {
            parseform = Boolean.parseBoolean(parseFormValue);
        }
        String roles = filterConfig.getInitParameter("roles");
        if (StringUtils.isNotBlank(roles)) {
            this.roles = new HashSet<>(Arrays.asList(roles.trim().split("\\s*,\\s*")));
        }
        String rolesMethods = filterConfig.getInitParameter("rolesMethods");
        if (StringUtils.isNotBlank(rolesMethods)) {
            this.methods = new HashSet<>(Arrays.asList(rolesMethods.trim().split("\\s*,\\s*")));
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        RequestDispatcher rd = request.getRequestDispatcher("/v1/proxy/" + proxy + httpRequest.getRequestURI());
        request.setAttribute(GenericWhitelistedProxy.ALLOWED_HOST, true);
        request.setAttribute(GenericWhitelistedProxy.SET_HOST_CURRENT_HOST, true);
        request.setAttribute(GenericWhitelistedProxy.REDIRECTS, redirects);
        request.setAttribute(GenericWhitelistedProxy.PARSE_FORM, parseform);

        if (roles != null && shouldRequireRoles(httpRequest.getRequestURI())) {
            request.setAttribute(GenericWhitelistedProxy.REQUIRE_ROLE, roles);
        }
        if (methods != null && shouldRequireRoles(httpRequest.getRequestURI())) {
            request.setAttribute(GenericWhitelistedProxy.METHOD_ROLE, methods);
        }

        rd.forward(request, response);
        return;
    }

    @Override
    public void destroy() {
    }

    static boolean shouldRequireRoles(String requestUri) {
        return !AUTH_TOKEN_PATH.equals(requestUri);
    }

}
