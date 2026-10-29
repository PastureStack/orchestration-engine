package io.cattle.platform.iaas.api.request.handler;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.iaas.api.servlet.filter.ProxyFilter;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

/** Same production ProxyFilter/GenericWhitelistedProxy/JdkProxyRequest chain.
 * A supplied local fixture executable switches only the upstream to the actual Catalog router/handlers.
 */
public class CatalogPrivateProxyBoundaryTest {
    static final String QUERY = "templateId=ctm-" + "a".repeat(64) + "&versionId=cvm-" + "b".repeat(64);
    @After public void cleanup() { ApiContext.remove(); }
    @BeforeClass public static void initializeShippingProxyTransport() throws Exception {
        // The shipping launcher configures Host before any JDK HttpClient starts.
        Class.forName(JdkProxyRequest.class.getName());
    }

    @Test public void publicCatalogFilterAndBothGenericEntrancesCannotReachPrivateOwner() throws Exception {
        try (Owner owner = new Owner()) {
            for (String path : List.of("/v1-catalog/ai/launch-material", "/v1-catalog/%61i/launch-material",
                    "/v1-catalog%2fai/launch-material", "/v1-catalog//ai//launch-material", "/v1-catalog/foo/../ai/launch-material",
                    "/v1-catalog/ai/launch-material/", "/v1-catalog/%2561i/launch-material")) {
                for (String entrance : List.of("filter", "v1", "v2-beta")) {
                    owner.reset(); Exchange exchange = new Exchange(owner, path, QUERY);
                    try {
                        exchange.execute(entrance);
                        fail(path + ":" + entrance + " reached HTTP " + exchange.status + "; private source calls=" + owner.launchCalls());
                    } catch (ClientVisibleException denied) {
                        assertEquals(403, denied.getStatus()); assertEquals("CatalogPrivateRouteDenied", denied.getCode());
                    }
                    assertEquals(path + ":" + entrance, 0, owner.launchCalls());
                }
            }
        }
    }

    @Test public void wholePrivateNamespaceIsClosedBeforePostBodyOrAnyOwnerSource() throws Exception {
        try (Owner owner = new Owner()) {
            for (String path : List.of("/v1-catalog/ai/templates", "/v1-catalog/ai/launch-options", "/v1-catalog/ai/launch-validate")) {
                for (String entrance : List.of("filter", "v1", "v2-beta")) {
                    owner.reset(); Exchange exchange = new Exchange(owner, path, path.endsWith("validate") ? null : QUERY);
                    if (path.endsWith("validate")) { exchange.method = "POST"; exchange.body = "{}"; }
                    assertEquals(403, assertThrows(ClientVisibleException.class, () -> exchange.execute(entrance)).getStatus());
                    assertEquals(0, exchange.bodyReads); assertEquals(0, owner.launchCalls()); assertEquals(0, owner.metadataCalls());
                }
            }
        }
    }

    @Test public void sameOriginRedirectCannotCrossIntoPrivateNamespace() throws Exception {
        try (Owner owner = new Owner()) {
            owner.reset(); Exchange exchange = new Exchange(owner, "/_fixture/redirect", null);
            ClientVisibleException denied = assertThrows(ClientVisibleException.class, () -> exchange.execute("v2-beta"));
            assertEquals(403, denied.getStatus()); assertEquals("CatalogPrivateRouteDenied", denied.getCode());
            assertEquals(0, owner.launchCalls());
        }
    }

    @Test public void ordinaryCatalogAndDirectInternalOwnerCallsRemainReachable() throws Exception {
        try (Owner owner = new Owner()) {
            for (String entrance : List.of("filter", "v1", "v2-beta")) {
                Exchange exchange = new Exchange(owner, "/v1-catalog/schemas", null); exchange.execute(entrance);
                assertEquals(200, exchange.status);
            }
            owner.reset();
            assertEquals(200, owner.client.send(HttpRequest.newBuilder(URI.create(owner.base + "/v1-catalog/ai/launch-material?" + QUERY))
                    .header("X-API-Project-Id", "1a5").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(1, owner.launchCalls()); // The private owner really works; a universally-denying fixture cannot pass.
        }
    }

    static final class Exchange {
        final Owner owner; final String path, query; final Map<String, Object> attributes = new LinkedHashMap<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream(); int status;
        String method = "GET", body = ""; int bodyReads;
        final GenericWhitelistedProxy proxy;
        Exchange(Owner owner, String path, String query) {
            this.owner = owner; this.path = path; this.query = query;
            proxy = new GenericWhitelistedProxy("test", new ProxySettings() {
                public boolean allowProxy() { return true; }
                public List<String> whitelist() { return List.of(URI.create(owner.base).getAuthority()); }
                public long connectTimeoutMillis() { return 2000; }
                public long requestTimeoutMillis() { return 5000; }
            });
        }
        void execute(String entrance) throws Exception {
            ApiContext.newContext();
            Policy policy = (Policy) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Policy.class }, (p, method, args) -> {
                if (method.getName().equals("getAccountId")) return 5L;
                if (method.getName().equals("getAuthenticatedAsAccountId")) return 42L;
                if (method.getName().equals("getRoles")) return Set.of("admin");
                return defaultValue(method.getReturnType());
            });
            ApiContext.getContext().setPolicy(policy);
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setTypeMappings(Map.of("account", "a"));
            formatter.setSchemaFactory((SchemaFactory) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { SchemaFactory.class },
                    (p, method, args) -> defaultValue(method.getReturnType())));
            ApiContext.getContext().setIdFormatter(formatter);
            HttpServletResponse response = response(); HttpServletRequest request = request(entrance.equals("filter") ? path : "/" + entrance +
                    "/proxy/" + owner.base + path);
            attributes.put(GenericWhitelistedProxy.REDIRECTS, true);
            if ("filter".equals(entrance)) {
                ProxyFilter filter = new ProxyFilter();
                filter.init((FilterConfig) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { FilterConfig.class }, (p, method, args) -> {
                    if (method.getName().equals("getInitParameter")) return "proxy".equals(args[0]) ? owner.base : null;
                    return defaultValue(method.getReturnType());
                }));
                filter.doFilter(request, response, (r, s) -> fail("Catalog filter must forward to the real generic proxy"));
            } else forward(request, response);
        }
        HttpServletRequest request(String uri) {
            return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { HttpServletRequest.class }, (p, method, args) -> {
                return switch (method.getName()) {
                case "getRequestURI" -> uri;
                case "getMethod" -> this.method;
                case "getQueryString" -> query;
                case "getAttribute" -> attributes.get(args[0]);
                case "setAttribute" -> { attributes.put((String) args[0], args[1]); yield null; }
                case "getHeaderNames", "getHeaders" -> Collections.emptyEnumeration();
                case "getContentLengthLong" -> (long) body.getBytes(StandardCharsets.UTF_8).length;
                case "getInputStream" -> {
                    bodyReads++; var input = new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
                    yield new jakarta.servlet.ServletInputStream() {
                        public int read() { return input.read(); }
                        public boolean isFinished() { return input.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(jakarta.servlet.ReadListener listener) { }
                    };
                }
                case "getRequestDispatcher" -> new RequestDispatcher() {
                    public void forward(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) throws java.io.IOException {
                        try { Exchange.this.forward(new HttpServletRequestWrapper((HttpServletRequest) r) {
                            @Override public String getRequestURI() { return (String) args[0]; }
                        }, (HttpServletResponse) s); }
                        catch (java.io.IOException failure) { throw failure; }
                        catch (RuntimeException failure) { throw failure; }
                        catch (Exception failure) { throw new java.io.IOException("Test forwarding unavailable", failure); }
                    }
                    public void include(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) { fail("Unexpected include"); }
                };
                default -> defaultValue(method.getReturnType());
                };
            });
        }
        void forward(HttpServletRequest http, HttpServletResponse response) throws Exception {
            ApiServletContext servlet = new ApiServletContext(http, response, null);
            ApiRequest request = new ApiRequest(servlet, null); request.setType("proxy"); request.setMethod(method);
            request.setRequestUrl("http://public.example" + http.getRequestURI()); request.setResponseUrlBase("http://public.example");
            ApiContext.getContext().setApiRequest(request); proxy.generate(request);
        }
        HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { HttpServletResponse.class }, (p,
                    method, args) -> {
                if (method.getName().equals("setStatus")) { status = (Integer) args[0]; return null; }
                if (method.getName().equals("getOutputStream")) return new ServletOutputStream() {
                    public void write(int value) { output.write(value); }
                    public boolean isReady() { return true; }
                    public void setWriteListener(WriteListener listener) { }
                };
                return defaultValue(method.getReturnType());
            });
        }
    }

    static final class Owner implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        final String base; final HttpServer server; final Process process; final AtomicInteger sourceCalls = new AtomicInteger();
        Owner() throws Exception {
            String executable = System.getProperty("catalog.private.fixture");
            if (executable != null) {
                process = new ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.DISCARD).start(); server = null;
                String line = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine();
                Map<?, ?> info = new com.fasterxml.jackson.databind.ObjectMapper().readValue(line, Map.class); base = (String) info.get("baseUrl");
            } else {
                process = null; server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/", exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.equals("/_fixture/redirect")) { exchange.getResponseHeaders().set("Location", "/v1-catalog/ai/launch-material?" +
                            QUERY); exchange.sendResponseHeaders(301, -1); }
                    else {
                        if (path.contains("/ai/")) sourceCalls.incrementAndGet();
                        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
                                exchange.getResponseBody().write(bytes);
                    }
                    exchange.close();
                });
                server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
            }
        }
        void reset() throws Exception {
            if (process == null) sourceCalls.set(0);
            else assertEquals(204, client.send(HttpRequest.newBuilder(URI.create(base + "/fixture/reset")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        int launchCalls() throws Exception {
            if (process == null) return sourceCalls.get();
            String body = client.send(HttpRequest.newBuilder(URI.create(base + "/fixture/counts")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            return ((Number) new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class).get("launchSourceCalls")).intValue();
        }
        int metadataCalls() throws Exception {
            if (process == null) return sourceCalls.get();
            String body = client.send(HttpRequest.newBuilder(URI.create(base + "/fixture/counts")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            return ((Number) new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class).get("metadataSourceCalls")).intValue();
        }
        @Override public void close() throws Exception {
            if (server != null) server.stop(0);
            if (process != null) { process.getOutputStream().write('\n'); process.getOutputStream().flush();
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
        }
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return '\0';
    }
}
