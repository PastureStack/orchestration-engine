package io.github.ibuildthecloud.gdapi.request.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Test;

public class CSRFCookieHandlerTest {

    @Test
    public void createsStrongBrowserCsrfCookieWithSameSiteLax() throws Exception {
        ResponseCapture response = new ResponseCapture();
        CSRFCookieHandler handler = new CSRFCookieHandler();

        handler.handle(apiRequest("GET", request(false, null, Map.of(), Map.of()), response.response()));

        Cookie csrf = response.onlyCookie();
        assertEquals(CSRFCookieHandler.CSRF, csrf.getName());
        assertEquals(CSRFCookieHandler.TOKEN_BYTES * 2, csrf.getValue().length());
        assertTrue(csrf.getValue().matches("[0-9A-F]+"));
        assertEquals("/", csrf.getPath());
        assertEquals(CSRFCookieHandler.SAME_SITE, csrf.getAttribute("SameSite"));
        assertFalse(csrf.getSecure());
        assertFalse(csrf.isHttpOnly());
    }

    @Test
    public void marksCsrfCookieSecureWhenRequestIsSecure() throws Exception {
        ResponseCapture response = new ResponseCapture();

        new CSRFCookieHandler().handle(apiRequest("GET",
                request(true, null, Map.of(), Map.of()), response.response()));

        assertTrue(response.onlyCookie().getSecure());
    }

    @Test
    public void marksCsrfCookieSecureBehindTlsProxy() throws Exception {
        ResponseCapture response = new ResponseCapture();
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Forwarded-Proto", "http, https");

        new CSRFCookieHandler().handle(apiRequest("GET",
                request(false, null, headers, Map.of()), response.response()));

        assertTrue(response.onlyCookie().getSecure());
    }

    @Test
    public void preservesExistingValidTokenAndAddsCookieAttributes() throws Exception {
        String token = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
        Cookie existing = new Cookie(CSRFCookieHandler.CSRF, token);
        ResponseCapture response = new ResponseCapture();
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(CSRFCookieHandler.HEADER, token);

        new CSRFCookieHandler().handle(apiRequest("POST",
                request(false, new Cookie[] { existing }, headers, Map.of()), response.response()));

        Cookie csrf = response.onlyCookie();
        assertSame(existing, csrf);
        assertEquals(token, csrf.getValue());
        assertEquals("/", csrf.getPath());
        assertEquals(CSRFCookieHandler.SAME_SITE, csrf.getAttribute("SameSite"));
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsWriteRequestWhenCsrfDoesNotMatch() throws Exception {
        Cookie existing = new Cookie(CSRFCookieHandler.CSRF, "expected");
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(CSRFCookieHandler.HEADER, "wrong");

        new CSRFCookieHandler().handle(apiRequest("POST",
                request(false, new Cookie[] { existing }, headers, Map.of()), new ResponseCapture().response()));
    }

    @Test
    public void ignoresNonBrowserRequests() throws Exception {
        ResponseCapture response = new ResponseCapture();
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("User-Agent", "curl/8");

        new CSRFCookieHandler().handle(apiRequest("GET",
                request(false, null, headers, Map.of()), response.response()));

        assertNull(response.cookie());
    }

    private static ApiRequest apiRequest(String method, HttpServletRequest request, HttpServletResponse response) {
        ApiRequest apiRequest = new ApiRequest(new ApiServletContext(request, response, null), null);
        apiRequest.setMethod(method);
        return apiRequest;
    }

    private static HttpServletRequest request(boolean secure, Cookie[] cookies, Map<String, String> headers,
            Map<String, String> parameters) {
        Map<String, String> allHeaders = new LinkedHashMap<String, String>();
        allHeaders.put("User-Agent", "Mozilla/5.0");
        allHeaders.putAll(headers);

        return (HttpServletRequest) Proxy.newProxyInstance(CSRFCookieHandlerTest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "isSecure":
                        return secure;
                    case "getCookies":
                        return cookies;
                    case "getHeader":
                        return getIgnoreCase(allHeaders, String.valueOf(args[0]));
                    case "getParameter":
                        return getIgnoreCase(parameters, String.valueOf(args[0]));
                    default:
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    private static String getIgnoreCase(Map<String, String> values, String name) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
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
        private final List<Cookie> cookies = new ArrayList<Cookie>();

        HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(CSRFCookieHandlerTest.class.getClassLoader(),
                    new Class<?>[] { HttpServletResponse.class }, (proxy, method, args) -> {
                        if ("addCookie".equals(method.getName())) {
                            cookies.add((Cookie) args[0]);
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        Cookie cookie() {
            return cookies.isEmpty() ? null : cookies.get(0);
        }

        Cookie onlyCookie() {
            assertEquals(1, cookies.size());
            return cookies.get(0);
        }
    }
}
