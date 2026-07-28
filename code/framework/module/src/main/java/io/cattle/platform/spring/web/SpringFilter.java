package io.cattle.platform.spring.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;

import org.springframework.web.context.support.WebApplicationContextUtils;

public abstract class SpringFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        WebApplicationContextUtils
            .getRequiredWebApplicationContext(filterConfig.getServletContext())
            .getAutowireCapableBeanFactory()
            .autowireBean(this);
    }

    @Override
    public void destroy() {
    }

}
