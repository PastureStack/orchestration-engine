package io.cattle.platform.iaas.api.request.handler;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Test;

public class JdkProxyRequestTest {

    private HttpServer server;
    private ExecutorService executor;

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    public void forwardsManagedProxyHeadersAndUsesHttp11() throws Exception {
        AtomicReference<Headers> headers = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();

        startServer(exchange -> {
            headers.set(exchange.getRequestHeaders());
            protocol.set(exchange.getProtocol());
            write(exchange, 204, new byte[0]);
        });

        JdkProxyRequest request = new JdkProxyRequest("POST", url("/headers"));
        request.setHeader("Host", "proxy.example:9443");
        request.addHeader("Authorization", "Bearer abc");
        request.addHeader("Cookie", "session=one");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-For", "192.0.2.10");
        request.body(new ByteArrayInputStream(bytes("body")), 4);

        JdkProxyRequest.ProxyResponse response = request.execute(false);
        closeQuietly(response.getBody());

        assertEquals(204, response.getStatusCode());
        assertEquals("HTTP/1.1", protocol.get());
        assertEquals("proxy.example:9443", headers.get().getFirst("Host"));
        assertEquals("Bearer abc", headers.get().getFirst("Authorization"));
        assertEquals("session=one", headers.get().getFirst("Cookie"));
        assertEquals("https", headers.get().getFirst("X-forwarded-proto"));
        assertEquals("192.0.2.10", headers.get().getFirst("X-forwarded-for"));
    }

    @Test
    public void streamsRawBodyWithoutReEncoding() throws Exception {
        byte[] raw = new byte[8192];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i % 251);
        }
        byte[] marker = bytes("a=b&space=hello world\0end");
        System.arraycopy(marker, 0, raw, 100, marker.length);
        AtomicReference<byte[]> captured = new AtomicReference<>();
        AtomicReference<String> contentLength = new AtomicReference<>();

        startServer(exchange -> {
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-length"));
            captured.set(readAll(exchange.getRequestBody()));
            write(exchange, 200, bytes("ok"));
        });

        JdkProxyRequest request = new JdkProxyRequest("PUT", url("/raw"));
        request.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        request.body(new ByteArrayInputStream(raw), raw.length);

        JdkProxyRequest.ProxyResponse response = request.execute(false);
        assertEquals("ok", new String(readAll(response.getBody()), StandardCharsets.UTF_8));

        assertEquals(String.valueOf(raw.length), contentLength.get());
        assertArrayEquals(raw, captured.get());
    }

    @Test
    public void encodesLegacyFormBodyOnlyWhenRequested() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();

        startServer(exchange -> {
            captured.set(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
            write(exchange, 200, bytes("ok"));
        });

        Map<String, String[]> form = new LinkedHashMap<>();
        form.put("name", new String[] { "hello world" });
        form.put("multi", new String[] { "1", "2" });
        form.put("empty", new String[] { null });

        JdkProxyRequest request = new JdkProxyRequest("POST", url("/form"));
        request.bodyForm(form);

        JdkProxyRequest.ProxyResponse response = request.execute(false);
        closeQuietly(response.getBody());

        assertEquals(200, response.getStatusCode());
        assertEquals("name=hello+world&multi=1&multi=2&empty=", captured.get());
    }

    @Test
    public void doesNotFollowRedirectsWhenDisabled() throws Exception {
        AtomicBoolean targetHit = new AtomicBoolean(false);

        startServer(exchange -> {
            if ("/redirect".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().set("Location", url("/target"));
                write(exchange, 302, bytes("redirect"));
                return;
            }
            targetHit.set(true);
            write(exchange, 200, bytes("target"));
        });

        JdkProxyRequest request = new JdkProxyRequest("GET", url("/redirect"));
        JdkProxyRequest.ProxyResponse response = request.execute(false);
        String body = new String(readAll(response.getBody()), StandardCharsets.UTF_8);

        assertEquals(302, response.getStatusCode());
        assertEquals("redirect", body);
        assertFalse(targetHit.get());
    }

    @Test
    public void followsRedirectsWhenEnabled() throws Exception {
        AtomicBoolean targetHit = new AtomicBoolean(false);

        startServer(exchange -> {
            if ("/redirect".equals(exchange.getRequestURI().getPath())) {
                exchange.getResponseHeaders().set("Location", url("/target"));
                write(exchange, 302, bytes("redirect"));
                return;
            }
            targetHit.set(true);
            write(exchange, 200, bytes("target"));
        });

        JdkProxyRequest request = new JdkProxyRequest("GET", url("/redirect"));
        JdkProxyRequest.ProxyResponse response = request.execute(true);
        String body = new String(readAll(response.getBody()), StandardCharsets.UTF_8);

        assertEquals(200, response.getStatusCode());
        assertEquals("target", body);
        assertTrue(targetHit.get());
    }

    @Test
    public void preservesResponseHeadersAndStreamsLargeBody() throws Exception {
        byte[] large = new byte[1024 * 1024];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 127);
        }

        startServer(exchange -> {
            exchange.getResponseHeaders().add("X-Rc16-Test", "one");
            exchange.getResponseHeaders().add("X-Rc16-Test", "two");
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            write(exchange, 206, large);
        });

        JdkProxyRequest request = new JdkProxyRequest("GET", url("/large"));
        JdkProxyRequest.ProxyResponse response = request.execute(false);

        assertEquals(206, response.getStatusCode());
        assertTrue(headerValues(response, "X-Rc16-Test").containsAll(Arrays.asList("one", "two")));
        assertEquals("application/octet-stream", headerValues(response, "Content-Type").get(0));
        assertArrayEquals(large, readAll(response.getBody()));
    }

    @Test
    public void honorsRequestTimeout() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(1000);
                write(exchange, 200, bytes("late"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        JdkProxyRequest request = new JdkProxyRequest("GET", url("/slow"));
        request.setRequestTimeoutMillis(50);

        try {
            request.execute(false);
            fail("Expected request timeout");
        } catch (IOException e) {
            assertTrue(e.getMessage().toLowerCase(Locale.ROOT).contains("timed out")
                    || e.getClass().getName().contains("Timeout"));
        }
    }

    private void startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", handler);
        server.start();
    }

    private String url(String path) {
        InetSocketAddress address = server.getAddress();
        return URI.create("http://" + address.getHostString() + ":" + address.getPort() + path).toString();
    }

    private static List<String> headerValues(JdkProxyRequest.ProxyResponse response, String name) {
        for (Map.Entry<String, List<String>> entry : response.getHeaders().entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    private static void write(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException e) {
        }
    }
}
