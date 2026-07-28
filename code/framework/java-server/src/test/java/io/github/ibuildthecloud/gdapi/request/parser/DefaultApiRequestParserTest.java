package io.github.ibuildthecloud.gdapi.request.parser;

import static org.junit.Assert.*;

import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class DefaultApiRequestParserTest {

    private static final String DEFAULT_REQUEST_URL = "http://defaulturl/v1";
    static DefaultApiRequestParser parser;

    HttpServletRequest request;
    Map<String, String> headers;
    Map<String, String[]> parameterMap;
    String requestUri;
    String requestUrl;

    @BeforeClass
    public static void setupClass() {
        parser = new DefaultApiRequestParser();
        parser.setAllowClientOverrideHeaders(true);
    }

    @Before
    public void setup() {
        headers = new HashMap<>();
        parameterMap = new HashMap<>();
        requestUri = "/v1";
        requestUrl = DEFAULT_REQUEST_URL;
        request = newRequest();
        // host header should always be set
        header(DefaultApiRequestParser.HOST_HEADER, "hostfoo");
    }

    @Test
    public void testXApiRequestUrl() {
        // Test X-API-Request-url basic use case
        header(DefaultApiRequestParser.DEFAULT_OVERRIDE_URL_HEADER, "http://foo:8080/v1");
        String url = parser.parseRequestUrl(null, request);
        assertEquals("http://foo:8080/v1", url);

        // Test longer request URI
        header(DefaultApiRequestParser.DEFAULT_OVERRIDE_URL_HEADER, "http://foo:8080/v1/instances");
        url = parser.parseRequestUrl(null, request);
        assertEquals("http://foo:8080/v1/instances", url);
    }

    @Test
    public void testXApiRequestUrlQueryString() {
        // Test X-API-Request-url with query string that needs stripped
        header(DefaultApiRequestParser.DEFAULT_OVERRIDE_URL_HEADER, "http://foo/v1/instances?bar=true");
        String url = parser.parseRequestUrl(null, request);
        assertEquals("http://foo/v1/instances", url);
    }

    @Test
    public void testXForwardedHost() {
        // Test x-forwarded-proto + x-f-host + x-f-port basic use case
        header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "https");
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "foo");
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "1234");
        String url = parser.parseRequestUrl(null, request);
        assertEquals("https://foo:1234/v1", url);

        // Test x-forwarded-proto + x-f-host (no x-f-port)
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, null);
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://foo/v1", url);

        // Test x-forwarded-proto + x-f-host + x-f-port case where x-f-host also has port and should be overridden
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "foo:1111");
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "1234");
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://foo:1234/v1", url);

        // Test x-forwarded-proto == https and x-forwarded-port == 443, dont include port in result
        // This is the typical AWS ELB use case
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "foo");
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "443");
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://foo/v1", url);

        // Test x-forwarded-proto == http and x-forwarded-port == 80
        // This is the typical AWS ELB use case
        header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "http");
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "80");
        url = parser.parseRequestUrl(null, request);
        assertEquals("http://foo/v1", url);

        // Test longer request URI
        requestUri = "/v1/instances/";
        url = parser.parseRequestUrl(null, request);
        assertEquals("http://foo/v1/instances/", url);
    }

    @Test
    public void testHost() {
        // Test x-forwarded-proto + Host + x-f-port basic use case
        header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "https");
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "1234");
        String url = parser.parseRequestUrl(null, request);
        assertEquals("https://hostfoo:1234/v1", url);

        // Test x-forwarded-proto + Host (no x-f-port)
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, null);
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://hostfoo/v1", url);

        // Test x-forwarded-proto + Host + x-f-port case where x-f-host also has port and should be overridden
        header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "https");
        header(DefaultApiRequestParser.HOST_HEADER, "hostfoo:1111");
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "1234");
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://hostfoo:1234/v1", url);

        // Test longer request URI
        requestUri = "/v1/instances/";
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://hostfoo:1234/v1/instances/", url);
    }

    @Test
    public void testNoXForwardedProto() {
        // Test no x-forwarded-proto present but x-f-host/Host is present
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "foo");
        String url = parser.parseRequestUrl(null, request);
        assertEquals(DEFAULT_REQUEST_URL, url);

        // Test no x-forwarded-proto present but x-f-host/Host is present
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, null);
        url = parser.parseRequestUrl(null, request);
        assertEquals(DEFAULT_REQUEST_URL, url);
    }

    @Test
    public void testIPv6() {
        // Test for x-f-host == [::1] (no port in host header)
        header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "https");
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "[::1]");
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "1234");
        String url = parser.parseRequestUrl(null, request);
        assertEquals("https://[::1]:1234/v1", url);

        // Same thing, but with host header
        header(DefaultApiRequestParser.HOST_HEADER, "[::1]");
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, null);
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "1234");
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://[::1]:1234/v1", url);

        // Properly override the port that's in the x-f-host header
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "[::1]:1111");
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "1234");
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://[::1]:1234/v1", url);

        // Same thing, but with host header
        header(DefaultApiRequestParser.HOST_HEADER, "[::1]:1111");
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, null);
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, "1234");
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://[::1]:1234/v1", url);

        // No x-f-port header
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "[::1]");
        header(DefaultApiRequestParser.FORWARDED_PORT_HEADER, null);
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://[::1]/v1", url);

        // Same thing, but with host header
        header(DefaultApiRequestParser.HOST_HEADER, "[::1]");
        header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, null);
        url = parser.parseRequestUrl(null, request);
        assertEquals("https://[::1]/v1", url);
    }

    @Test
    public void testParseParamsPreservesServletParameterArrays() throws Exception {
        String[] values = new String[] { "one", "two" };
        parameterMap.put("name", values);

        Map<String, Object> parsed = parser.parseParams(null, request);

        assertSame(values, parsed.get("name"));
    }

    private void header(String name, String value) {
        if (value == null) {
            headers.remove(name);
        } else {
            headers.put(name, value);
        }
    }

    private HttpServletRequest newRequest() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                switch (method.getName()) {
                case "getHeader":
                    return headers.get(args[0]);
                case "getRequestURI":
                    return requestUri;
                case "getRequestURL":
                    return new StringBuffer(requestUrl);
                case "getParameterMap":
                    return parameterMap;
                case "getContentType":
                    return null;
                case "getMethod":
                    return "GET";
                default:
                    return null;
                }
            }
        };

        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class[] { HttpServletRequest.class },
                handler);
    }
}
