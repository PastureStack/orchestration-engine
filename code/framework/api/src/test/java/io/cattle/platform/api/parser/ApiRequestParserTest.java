package io.cattle.platform.api.parser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import io.github.ibuildthecloud.gdapi.request.parser.DefaultApiRequestParser;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class ApiRequestParserTest {

    @Test
    public void requestParserSettingsReadDynamicValuesThroughWrapper() {
        final String allowOverrideKey = "api.allow.client.override";
        final String httpsPortsKey = "proxy.protocol.https.ports";
        final String trustForwardedHostKey = "api.trust.forwarded.host";
        final String allowedForwardedHostsKey = "api.forwarded.host.allowlist";
        final String apiHostKey = "api.host";

        try {
            ConfigurationManager.getConfigInstance().setProperty(allowOverrideKey, "true");
            ConfigurationManager.getConfigInstance().setProperty(httpsPortsKey, "7443,8443");
            ConfigurationManager.getConfigInstance().setProperty(trustForwardedHostKey, "true");
            ConfigurationManager.getConfigInstance().setProperty(allowedForwardedHostsKey, "api.example,*.trusted.example");
            ConfigurationManager.getConfigInstance().setProperty(apiHostKey, "https://public.example");

            ApiRequestParserSettings settings = ArchaiusApiRequestParserSettings.create();

            assertTrue(settings.allowClientOverrideHeaders());
            assertTrue(settings.httpsPorts().contains("7443"));
            assertTrue(settings.httpsPorts().contains("8443"));
            assertTrue(settings.trustForwardedHost());
            assertTrue(settings.allowedForwardedHosts().contains("api.example"));
            assertEquals("https://public.example", settings.apiHost());
        } finally {
            clearProperty(allowOverrideKey);
            clearProperty(httpsPortsKey);
            clearProperty(trustForwardedHostKey);
            clearProperty(allowedForwardedHostsKey);
            clearProperty(apiHostKey);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingApiRequestParserSettings() {
        new ApiRequestParser(null);
    }

    @Test
    public void usesInjectedAllowOverrideSetting() {
        assertTrue(new ApiRequestParser(settings(true, "443")).isAllowClientOverrideHeaders());
        assertFalse(new ApiRequestParser(settings(false, "443")).isAllowClientOverrideHeaders());
    }

    @Test
    public void usesInjectedHttpsPortsForExplicitPort() {
        ApiRequestParser parser = new ApiRequestParser(settings(false, "443", "8443"));

        assertTrue(parser.isHttpsPort("api.example", "443"));
        assertTrue(parser.isHttpsPort("api.example", "8443"));
        assertFalse(parser.isHttpsPort("api.example", "8080"));
    }

    @Test
    public void extractsPortFromHostWhenPortArgumentIsMissing() {
        ApiRequestParser parser = new ApiRequestParser(settings(false, "443", "9443"));

        assertTrue(parser.isHttpsPort("api.example:9443", null));
        assertFalse(parser.isHttpsPort("api.example:8080", null));
        assertFalse(parser.isHttpsPort("api.example", null));
    }

    @Test
    public void ignoresUntrustedForwardedHostByDefault() {
        TestableApiRequestParser parser = new TestableApiRequestParser(settings(false, "", null, false, "443"));
        HttpServletRequest request = request("/v1", "http://internal/v1",
                header(DefaultApiRequestParser.HOST_HEADER, "public.example"),
                header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "attacker.invalid"),
                header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "https"));

        assertEquals("https://public.example/v1", parser.parseUrl(request));
    }

    @Test
    public void allowsForwardedHostWhenItMatchesHostHeader() {
        TestableApiRequestParser parser = new TestableApiRequestParser(settings(false, "", null, false, "443"));
        HttpServletRequest request = request("/v1", "http://internal/v1",
                header(DefaultApiRequestParser.HOST_HEADER, "public.example"),
                header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "public.example"),
                header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "https"));

        assertEquals("https://public.example/v1", parser.parseUrl(request));
    }

    @Test
    public void allowsConfiguredForwardedHost() {
        TestableApiRequestParser parser = new TestableApiRequestParser(settings(false, "api.example:9443,*.trusted.example",
                "https://public.example", false, "443"));
        HttpServletRequest explicitPort = request("/v1", "http://internal/v1",
                header(DefaultApiRequestParser.HOST_HEADER, "internal.example"),
                header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "api.example:9443"),
                header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "https"));
        HttpServletRequest wildcard = request("/v1", "http://internal/v1",
                header(DefaultApiRequestParser.HOST_HEADER, "internal.example"),
                header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "team.trusted.example"),
                header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "https"));

        assertEquals("https://api.example:9443/v1", parser.parseUrl(explicitPort));
        assertEquals("https://team.trusted.example/v1", parser.parseUrl(wildcard));
    }

    @Test
    public void preservesLegacyForwardedHostTrustWhenExplicitlyEnabled() {
        TestableApiRequestParser parser = new TestableApiRequestParser(settings(false, "", null, true, "443"));
        HttpServletRequest request = request("/v1", "http://internal/v1",
                header(DefaultApiRequestParser.HOST_HEADER, "public.example"),
                header(DefaultApiRequestParser.FORWARDED_HOST_HEADER, "legacy-proxy.example"),
                header(DefaultApiRequestParser.FORWARDED_PROTO_HEADER, "https"));

        assertEquals("https://legacy-proxy.example/v1", parser.parseUrl(request));
    }

    private static void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static ApiRequestParserSettings settings(boolean allowOverride, String... httpsPorts) {
        return settings(allowOverride, "", null, false, httpsPorts);
    }

    private static ApiRequestParserSettings settings(boolean allowOverride, String allowedForwardedHosts, String apiHost,
            boolean trustForwardedHost, String... httpsPorts) {
        return new TestApiRequestParserSettings(allowOverride, Arrays.asList(httpsPorts), trustForwardedHost,
                Arrays.asList(allowedForwardedHosts.split(",")), apiHost);
    }

    private static Header header(String name, String value) {
        return new Header(name, value);
    }

    private static HttpServletRequest request(final String requestUri, final String requestUrl, Header... headers) {
        final Map<String, String> headerMap = new HashMap<>();
        for (Header header : headers) {
            headerMap.put(header.name, header.value);
        }

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                switch (method.getName()) {
                case "getHeader":
                    return headerMap.get(args[0]);
                case "getRequestURI":
                    return requestUri;
                case "getRequestURL":
                    return new StringBuffer(requestUrl);
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

    private static final class Header {
        private final String name;
        private final String value;

        Header(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    private static final class TestableApiRequestParser extends ApiRequestParser {
        TestableApiRequestParser(ApiRequestParserSettings settings) {
            super(settings);
        }

        String parseUrl(HttpServletRequest request) {
            return parseRequestUrl(null, request);
        }
    }

    private static final class TestApiRequestParserSettings implements ApiRequestParserSettings {
        private final boolean allowOverride;
        private final List<String> httpsPorts;
        private final boolean trustForwardedHost;
        private final List<String> allowedForwardedHosts;
        private final String apiHost;

        TestApiRequestParserSettings(boolean allowOverride, List<String> httpsPorts, boolean trustForwardedHost,
                List<String> allowedForwardedHosts, String apiHost) {
            this.allowOverride = allowOverride;
            this.httpsPorts = httpsPorts;
            this.trustForwardedHost = trustForwardedHost;
            this.allowedForwardedHosts = allowedForwardedHosts;
            this.apiHost = apiHost;
        }

        @Override
        public boolean allowClientOverrideHeaders() {
            return allowOverride;
        }

        @Override
        public List<String> httpsPorts() {
            return httpsPorts;
        }

        @Override
        public boolean trustForwardedHost() {
            return trustForwardedHost;
        }

        @Override
        public List<String> allowedForwardedHosts() {
            return allowedForwardedHosts;
        }

        @Override
        public String apiHost() {
            return apiHost;
        }
    }
}
