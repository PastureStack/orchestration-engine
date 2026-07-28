package io.cattle.platform.iaas.api.servlet.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;

import org.junit.Test;

public class ProxyFilterTest {

    @Test
    public void initParsesRolesMethodsWithoutRoles() throws Exception {
        ProxyFilter filter = new ProxyFilter();

        filter.init(config(params(
                "proxy", "auth-service",
                "rolesMethods", "POST, PUT")));

        assertNull(filter.roles);
        assertEquals(new HashSet<>(Arrays.asList("POST", "PUT")), filter.methods);
    }

    @Test
    public void initDoesNotRequireRolesMethodsWhenRolesAreConfigured() throws Exception {
        ProxyFilter filter = new ProxyFilter();

        filter.init(config(params(
                "proxy", "auth-service",
                "roles", "admin, owner")));

        assertEquals(new HashSet<>(Arrays.asList("admin", "owner")), filter.roles);
        assertNull(filter.methods);
    }

    @Test
    public void authTokenLoginProxyDoesNotRequireExistingAdminRole() {
        assertFalse(ProxyFilter.shouldRequireRoles("/v1-auth/token"));
    }

    @Test
    public void authConfigProxyStillRequiresConfiguredAdminRole() {
        assertTrue(ProxyFilter.shouldRequireRoles("/v1-auth/config"));
        assertTrue(ProxyFilter.shouldRequireRoles("/v1-auth/configs"));
        assertTrue(ProxyFilter.shouldRequireRoles("/v1-auth/reload"));
    }

    private static Map<String, String> params(String... values) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return result;
    }

    private static FilterConfig config(Map<String, String> params) {
        return new FilterConfig() {
            @Override
            public String getFilterName() {
                return "proxy-filter-test";
            }

            @Override
            public ServletContext getServletContext() {
                return null;
            }

            @Override
            public String getInitParameter(String name) {
                return params.get(name);
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.enumeration(params.keySet());
            }
        };
    }
}
