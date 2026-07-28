package io.github.ibuildthecloud.gdapi.servlet;

import java.io.IOException;

import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

public class ApiRequestFilter implements Filter {

    ApiRequestFilterDelegate apiRequestFilterDelegate;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        apiRequestFilterDelegate.doFilter(request, response, chain);
    }

    @Override
    public void destroy() {
    }

    public ApiRequestFilterDelegate getApiRequestFilterDelegate() {
        return apiRequestFilterDelegate;
    }

    @Inject
    public void setApiRequestFilterDelegate(ApiRequestFilterDelegate apiRequestFilterDelegate) {
        this.apiRequestFilterDelegate = apiRequestFilterDelegate;
    }

}
