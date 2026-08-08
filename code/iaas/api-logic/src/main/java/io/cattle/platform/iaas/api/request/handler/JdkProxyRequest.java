package io.cattle.platform.iaas.api.request.handler;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.function.Predicate;

final class JdkProxyRequest {

    static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";
    static final String CONTENT_TYPE = "Content-Type";
    static final String HOST = "Host";
    private static final int MAX_REDIRECTS = 5;

    private static final Set<String> JDK_RESTRICTED_HEADERS = new HashSet<>(Arrays.asList(
            "connection",
            "content-length",
            "expect",
            "host",
            "upgrade"));

    static {
        allowRestrictedHeader("host");
    }

    private static final HttpClient NO_REDIRECT_CLIENT = newClient(HttpClient.Redirect.NEVER);

    private final String authorizedHost;
    private final int authorizedPort;
    private final String authorizedScheme;
    private final Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
    private final String method;
    private final Predicate<URI> targetPolicy;
    private final URI target;
    private HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
    private long connectTimeoutMillis;
    private long requestTimeoutMillis;

    JdkProxyRequest(String method, URI target, Predicate<URI> targetPolicy) {
        this.method = method;
        this.targetPolicy = targetPolicy;
        URI initialTarget = requireProxyTarget(target);
        this.authorizedScheme = initialTarget.getScheme();
        this.authorizedHost = initialTarget.getHost();
        this.authorizedPort = effectivePort(initialTarget);
        this.target = requireAllowedTarget(initialTarget);
    }

    void setConnectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = Math.max(0, connectTimeoutMillis);
    }

    void setRequestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = Math.max(0, requestTimeoutMillis);
    }

    void addHeader(String name, String value) {
        setOrAddHeader(false, name, value);
    }

    void setHeader(String name, String value) {
        setOrAddHeader(true, name, value);
    }

    void bodyForm(Map<String, String[]> form) {
        setHeader(CONTENT_TYPE, APPLICATION_FORM_URLENCODED);
        body = HttpRequest.BodyPublishers.ofByteArray(formBody(form).getBytes(StandardCharsets.UTF_8));
    }

    void body(InputStream inputStream, long contentLength) throws IOException {
        if (contentLength == 0) {
            body = HttpRequest.BodyPublishers.noBody();
        } else {
            body = streamingBody(inputStream, contentLength);
        }
    }

    ProxyResponse execute(boolean redirects) throws IOException {
        return execute(target, redirects, 0, method, body);
    }

    private ProxyResponse execute(URI currentTarget, boolean redirects, int redirectCount,
            String currentMethod, HttpRequest.BodyPublisher currentBody) throws IOException {
        try {
            URI allowedTarget = requireAllowedTarget(currentTarget);
            HttpRequest.Builder builder = HttpRequest.newBuilder(allowedTarget)
                    .version(HttpClient.Version.HTTP_1_1);
            if (requestTimeoutMillis > 0) {
                builder.timeout(Duration.ofMillis(requestTimeoutMillis));
            }
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                for (String value : header.getValue()) {
                    builder.header(header.getKey(), redirectedHeaderValue(header.getKey(), value, allowedTarget));
                }
            }
            HttpRequest request = builder.method(currentMethod, currentBody).build();
            HttpClient client = client();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (redirects && isRedirect(response.statusCode())) {
                if (redirectCount >= MAX_REDIRECTS) {
                    closeQuietly(response.body());
                    throw new ProtocolException("Too many proxy redirects");
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location != null) {
                    URI nextTarget = requireAllowedTarget(allowedTarget.resolve(location));
                    if (!canReplayRedirect(response.statusCode(), currentMethod)) {
                        return new ProxyResponse(response.statusCode(), response.headers(), response.body());
                    }
                    closeQuietly(response.body());
                    String nextMethod = redirectedMethod(response.statusCode(), currentMethod);
                    HttpRequest.BodyPublisher nextBody = nextMethod.equals(currentMethod)
                            ? currentBody : HttpRequest.BodyPublishers.noBody();
                    return execute(nextTarget, true, redirectCount + 1, nextMethod, nextBody);
                }
            }
            return new ProxyResponse(response.statusCode(), response.headers(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while proxying request", e);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage());
        }
    }

    private URI requireAllowedTarget(URI candidate) {
        URI normalized = requireProxyTarget(candidate);
        if (!authorizedHost.equals(normalized.getHost())
                || !authorizedScheme.equalsIgnoreCase(normalized.getScheme())
                || authorizedPort != effectivePort(normalized)
                || targetPolicy == null || !targetPolicy.test(normalized)) {
            throw new IllegalArgumentException("Proxy target is not allowed");
        }
        return normalized;
    }

    private static URI requireProxyTarget(URI candidate) {
        if (candidate == null || candidate.getHost() == null || candidate.getUserInfo() != null
                || candidate.getFragment() != null
                || !("http".equalsIgnoreCase(candidate.getScheme())
                || "https".equalsIgnoreCase(candidate.getScheme()))) {
            throw new IllegalArgumentException("Proxy target is not allowed");
        }
        URI normalized = candidate.normalize();
        StringBuilder canonical = new StringBuilder()
                .append(normalized.getScheme().toLowerCase(Locale.ROOT))
                .append("://")
                .append(normalized.getRawAuthority().toLowerCase(Locale.ROOT));
        if (normalized.getRawPath() != null) {
            canonical.append(normalized.getRawPath());
        }
        if (normalized.getRawQuery() != null) {
            canonical.append('?').append(normalized.getRawQuery());
        }
        return URI.create(canonical.toString());
    }

    private void setOrAddHeader(boolean replace, String name, String value) {
        if (isJdkRestrictedHeader(name) && !HOST.equalsIgnoreCase(name)) {
            return;
        }

        String existing = null;
        for (String header : headers.keySet()) {
            if (header.equalsIgnoreCase(name)) {
                existing = header;
                break;
            }
        }
        String key = existing == null ? name : existing;
        if (replace || existing == null) {
            headers.put(key, new java.util.ArrayList<>(List.of(value)));
        } else {
            headers.get(key).add(value);
        }
    }

    private static boolean isJdkRestrictedHeader(String name) {
        return JDK_RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static String formBody(Map<String, String[]> form) {
        StringBuilder body = new StringBuilder();
        for (String name : form.keySet()) {
            String[] values = form.get(name);
            if (values == null) {
                appendFormPair(body, name, "");
                continue;
            }
            for (String value : values) {
                appendFormPair(body, name, value == null ? "" : value);
            }
        }
        return body.toString();
    }

    private static void appendFormPair(StringBuilder body, String name, String value) {
        if (body.length() > 0) {
            body.append('&');
        }
        body.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        body.append('=');
        body.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private HttpClient client() {
        if (connectTimeoutMillis <= 0) {
            return NO_REDIRECT_CLIENT;
        }
        return newClient(HttpClient.Redirect.NEVER, connectTimeoutMillis);
    }

    private static HttpClient newClient(HttpClient.Redirect redirect) {
        return newClient(redirect, 0);
    }

    private static HttpClient newClient(HttpClient.Redirect redirect, long connectTimeoutMillis) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(redirect)
                .proxy(ProxySelector.getDefault());
        if (connectTimeoutMillis > 0) {
            builder.connectTimeout(Duration.ofMillis(connectTimeoutMillis));
        }
        return builder.build();
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private static String redirectedMethod(int statusCode, String currentMethod) {
        if (statusCode == 303 || ((statusCode == 301 || statusCode == 302)
                && "POST".equalsIgnoreCase(currentMethod))) {
            return "GET";
        }
        return currentMethod;
    }

    private static boolean canReplayRedirect(int statusCode, String currentMethod) {
        if ("GET".equalsIgnoreCase(currentMethod) || "HEAD".equalsIgnoreCase(currentMethod)) {
            return true;
        }
        return statusCode == 303 || ((statusCode == 301 || statusCode == 302)
                && "POST".equalsIgnoreCase(currentMethod));
    }

    private String redirectedHeaderValue(String name, String value, URI currentTarget) {
        if (!HOST.equalsIgnoreCase(name) || sameAuthority(target, currentTarget)) {
            return value;
        }
        return authority(currentTarget);
    }

    private static boolean sameAuthority(URI first, URI second) {
        return first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String authority(URI uri) {
        String host = uri.getHost().indexOf(':') >= 0 ? "[" + uri.getHost() + "]" : uri.getHost();
        return uri.getPort() >= 0 ? host + ":" + uri.getPort() : host;
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

    private static HttpRequest.BodyPublisher streamingBody(InputStream inputStream, long contentLength) {
        HttpRequest.BodyPublisher delegate = HttpRequest.BodyPublishers.ofInputStream(() -> inputStream);
        if (contentLength > 0) {
            return new FixedLengthBodyPublisher(delegate, contentLength);
        }
        return delegate;
    }

    private static void allowRestrictedHeader(String header) {
        String current = System.getProperty("jdk.httpclient.allowRestrictedHeaders");
        if (current == null || current.isBlank()) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", header);
            return;
        }

        for (String value : current.split(",")) {
            if (header.equalsIgnoreCase(value.trim())) {
                return;
            }
        }
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", current + "," + header);
    }

    static final class ProxyResponse {
        private final int statusCode;
        private final HttpHeaders headers;
        private final InputStream body;

        private ProxyResponse(int statusCode, HttpHeaders headers, InputStream body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        int getStatusCode() {
            return statusCode;
        }

        Map<String, List<String>> getHeaders() {
            return headers.map();
        }

        InputStream getBody() {
            return body;
        }
    }

    private static final class FixedLengthBodyPublisher implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final long contentLength;

        private FixedLengthBodyPublisher(HttpRequest.BodyPublisher delegate, long contentLength) {
            this.delegate = delegate;
            this.contentLength = contentLength;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            delegate.subscribe(subscriber);
        }
    }
}
