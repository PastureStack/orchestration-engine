package io.cattle.platform.api.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class ApiRequestFilterTest {

    @After
    public void clearProperties() {
        clear("api.ignore.paths");
        clear("ui.pl");
        clear("localization");
        clear("rancher.server.version");
    }

    @Test
    public void archaiusSettingsReadDynamicValues() {
        ConfigurationManager.getConfigInstance().setProperty("api.ignore.paths", "/static,/ping");
        ConfigurationManager.getConfigInstance().setProperty("ui.pl", "rancher");
        ConfigurationManager.getConfigInstance().setProperty("localization", "zh-TW");
        ConfigurationManager.getConfigInstance().setProperty("rancher.server.version", "v1.6.196");

        ApiRequestFilterSettings settings = ArchaiusApiRequestFilterSettings.create();

        assertEquals(Arrays.asList("/static", "/ping"), settings.ignorePaths());
        assertEquals("rancher", settings.projectLabel());
        assertEquals("zh-TW", settings.localization());
        assertEquals("v1.6.196", settings.serverVersion());

        ConfigurationManager.getConfigInstance().setProperty("rancher.server.version", "v1.6.197");

        assertEquals("v1.6.197", settings.serverVersion());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSettings() {
        new ApiRequestFilter(null, new IndexFile(indexSettings("local")), new SecurityHeaders());
    }

    @Test
    public void injectedIgnorePathsUsePrefixMatching() {
        ApiRequestFilter filter = filter(settings(Arrays.asList("/static", "/ping"), "rancher", "", "v1"));

        assertTrue(filter.isIgnoredPath("/static/js/app.js"));
        assertTrue(filter.isIgnoredPath("/ping"));
    }

    @Test
    public void addsVersionHeaderFromInjectedSettings() {
        ApiRequestFilter filter = filter(settings(Collections.<String>emptyList(), "rancher", "", "v1.6.197"));
        ResponseCapture response = new ResponseCapture();

        filter.addVersionHeader(request(null), response.response());

        assertEquals("v1.6.197", response.headers.get("X-PastureStack-Version"));
        assertEquals("v1.6.197", response.headers.get("X-Rancher-Version"));
    }

    @Test
    public void addsEncodedProjectLabelCookieWhenMissing() {
        ApiRequestFilter filter = filter(settings(Collections.<String>emptyList(), "team label", "", "v1"));
        ResponseCapture response = new ResponseCapture();

        filter.addPLCookie(request(null), response.response());

        assertEquals(1, response.cookies.size());
        assertEquals("PL", response.cookies.get(0).getName());
        assertEquals("team+label", response.cookies.get(0).getValue());
        assertEquals("/", response.cookies.get(0).getPath());
    }

    @Test
    public void preservesExistingProjectLabelCookie() {
        ApiRequestFilter filter = filter(settings(Collections.<String>emptyList(), "rancher", "", "v1"));
        ResponseCapture response = new ResponseCapture();

        filter.addPLCookie(request(new Cookie[] { new Cookie("PL", "rancher") }), response.response());

        assertTrue(response.cookies.isEmpty());
    }

    @Test
    public void addsDefaultLanguageCookieWhenLocalizationConfigured() {
        ApiRequestFilter filter = filter(settings(Collections.<String>emptyList(), "rancher", "zh-TW", "v1"));
        ResponseCapture response = new ResponseCapture();

        filter.addDefaultLanguageCookie(request(null), response.response());

        assertEquals(1, response.cookies.size());
        assertEquals("LANG", response.cookies.get(0).getName());
        assertEquals("zh-TW", response.cookies.get(0).getValue());
        assertEquals("/", response.cookies.get(0).getPath());
    }

    @Test
    public void doesNotAddLanguageCookieWhenLocalizationBlank() {
        ApiRequestFilter filter = filter(settings(Collections.<String>emptyList(), "rancher", "", "v1"));
        ResponseCapture response = new ResponseCapture();

        filter.addDefaultLanguageCookie(request(null), response.response());

        assertTrue(response.cookies.isEmpty());
    }

    private static ApiRequestFilter filter(ApiRequestFilterSettings settings) {
        return new ApiRequestFilter(settings, new IndexFile(indexSettings("local")), new SecurityHeaders());
    }

    private static ApiRequestFilterSettings settings(final List<String> ignorePaths, final String projectLabel,
            final String localization, final String serverVersion) {
        return new ApiRequestFilterSettings() {
            @Override
            public List<String> ignorePaths() {
                return ignorePaths;
            }

            @Override
            public String projectLabel() {
                return projectLabel;
            }

            @Override
            public String localization() {
                return localization;
            }

            @Override
            public String serverVersion() {
                return serverVersion;
            }
        };
    }

    private static IndexFileSettings indexSettings(final String indexUrl) {
        return new IndexFileSettings() {
            @Override
            public String indexUrl() {
                return indexUrl;
            }

            @Override
            public void addIndexUrlCallback(Runnable callback) {
            }
        };
    }

    private static HttpServletRequest request(final Cookie[] cookies) {
        return (HttpServletRequest) Proxy.newProxyInstance(ApiRequestFilterTest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> {
                    if ("getCookies".equals(method.getName())) {
                        return cookies;
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

    private static class ResponseCapture {
        private final Map<String, String> headers = new LinkedHashMap<String, String>();
        private final List<Cookie> cookies = new ArrayList<Cookie>();

        HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(ApiRequestFilterTest.class.getClassLoader(),
                    new Class<?>[] { HttpServletResponse.class }, (proxy, method, args) -> {
                        if ("setHeader".equals(method.getName()) || "addHeader".equals(method.getName())) {
                            headers.put(String.valueOf(args[0]), String.valueOf(args[1]));
                            return null;
                        }
                        if ("addCookie".equals(method.getName())) {
                            cookies.add((Cookie) args[0]);
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }
}
