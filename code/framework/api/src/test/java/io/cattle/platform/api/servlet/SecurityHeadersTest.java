package io.cattle.platform.api.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class SecurityHeadersTest {

    @After
    public void clearProperties() {
        clear("api.security.headers.enabled");
        clear("api.security.headers.frame.options");
        clear("api.security.headers.content.security.policy");
        clear("api.security.headers.content.type.options");
        clear("api.security.headers.referrer.policy");
        clear("api.security.headers.hsts.enabled");
        clear("api.security.headers.hsts");
    }

    @Test
    public void appliesDefaultBrowserSafeHeadersWithoutHstsOnHttp() {
        HeaderCapture response = new HeaderCapture();

        new SecurityHeaders().apply(request(false), response.response());

        assertEquals("SAMEORIGIN", response.headers.get("X-Frame-Options"));
        assertEquals("frame-ancestors 'self'", response.headers.get("Content-Security-Policy"));
        assertEquals("nosniff", response.headers.get("X-Content-Type-Options"));
        assertEquals("no-referrer", response.headers.get("Referrer-Policy"));
        assertFalse(response.headers.containsKey("Strict-Transport-Security"));
    }

    @Test
    public void appliesHstsBehindTlsTerminatingProxy() {
        HeaderCapture response = new HeaderCapture();
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Forwarded-Proto", "http, https");

        new SecurityHeaders().apply(request(false, headers), response.response());

        assertEquals("max-age=31536000; includeSubDomains", response.headers.get("Strict-Transport-Security"));
    }

    @Test
    public void canDisableSecurityHeadersForLegacyCompatibility() {
        ConfigurationManager.getConfigInstance().setProperty("api.security.headers.enabled", false);
        HeaderCapture response = new HeaderCapture();

        new SecurityHeaders().apply(request(true), response.response());

        assertTrue(response.headers.isEmpty());
    }

    @Test
    public void appliesInjectedSettingsWithoutChangingHeaderNames() {
        HeaderCapture response = new HeaderCapture();
        SecurityHeaderSettings settings = fixedSettings(true, "DENY", "frame-ancestors 'none'", "nosniff",
                "same-origin", true, "max-age=60");

        new SecurityHeaders(settings).apply(request(true), response.response());

        assertEquals("DENY", response.headers.get("X-Frame-Options"));
        assertEquals("frame-ancestors 'none'", response.headers.get("Content-Security-Policy"));
        assertEquals("nosniff", response.headers.get("X-Content-Type-Options"));
        assertEquals("same-origin", response.headers.get("Referrer-Policy"));
        assertEquals("max-age=60", response.headers.get("Strict-Transport-Security"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSettings() {
        new SecurityHeaders(null);
    }

    private static HttpServletRequest request(boolean secure) {
        return request(secure, new LinkedHashMap<String, String>());
    }

    private static HttpServletRequest request(boolean secure, Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(SecurityHeadersTest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> {
                    if ("isSecure".equals(method.getName())) {
                        return secure;
                    }
                    if ("getHeader".equals(method.getName())) {
                        return headers.get(args[0]);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (Boolean.TYPE.equals(type)) {
            return false;
        }
        if (Character.TYPE.equals(type)) {
            return Character.valueOf('\0');
        }
        if (Byte.TYPE.equals(type)) {
            return Byte.valueOf((byte) 0);
        }
        if (Short.TYPE.equals(type)) {
            return Short.valueOf((short) 0);
        }
        if (Integer.TYPE.equals(type)) {
            return Integer.valueOf(0);
        }
        if (Long.TYPE.equals(type)) {
            return Long.valueOf(0L);
        }
        if (Float.TYPE.equals(type)) {
            return Float.valueOf(0F);
        }
        if (Double.TYPE.equals(type)) {
            return Double.valueOf(0D);
        }
        return null;
    }

    private static SecurityHeaderSettings fixedSettings(boolean enabled, String frameOptions,
            String contentSecurityPolicy, String contentTypeOptions, String referrerPolicy, boolean hstsEnabled,
            String hsts) {
        return new SecurityHeaderSettings() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public String frameOptions() {
                return frameOptions;
            }

            @Override
            public String contentSecurityPolicy() {
                return contentSecurityPolicy;
            }

            @Override
            public String contentTypeOptions() {
                return contentTypeOptions;
            }

            @Override
            public String referrerPolicy() {
                return referrerPolicy;
            }

            @Override
            public boolean hstsEnabled() {
                return hstsEnabled;
            }

            @Override
            public String hsts() {
                return hsts;
            }
        };
    }

    private static class HeaderCapture {
        private final Map<String, String> headers = new LinkedHashMap<String, String>();

        HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(SecurityHeadersTest.class.getClassLoader(),
                    new Class<?>[] { HttpServletResponse.class }, (proxy, method, args) -> {
                        if ("setHeader".equals(method.getName()) || "addHeader".equals(method.getName())) {
                            headers.put(String.valueOf(args[0]), String.valueOf(args[1]));
                            return null;
                        }
                        if ("containsHeader".equals(method.getName())) {
                            String name = String.valueOf(args[0]).toLowerCase(Locale.ROOT);
                            for (String header : headers.keySet()) {
                                if (header.toLowerCase(Locale.ROOT).equals(name)) {
                                    return true;
                                }
                            }
                            return false;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }
}
