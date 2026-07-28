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

final class JdkProxyRequest {

    static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";
    static final String CONTENT_TYPE = "Content-Type";
    static final String HOST = "Host";

    private static final Set<String> JDK_RESTRICTED_HEADERS = new HashSet<>(Arrays.asList(
            "connection",
            "content-length",
            "expect",
            "host",
            "upgrade"));

    static {
        allowRestrictedHeader("host");
    }

    private static final HttpClient REDIRECT_CLIENT = newClient(HttpClient.Redirect.NORMAL);
    private static final HttpClient NO_REDIRECT_CLIENT = newClient(HttpClient.Redirect.NEVER);

    private final HttpRequest.Builder builder;
    private final String method;
    private HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
    private long connectTimeoutMillis;
    private long requestTimeoutMillis;

    JdkProxyRequest(String method, String url) {
        this.method = method;
        this.builder = HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1);
    }

    void setConnectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = Math.max(0, connectTimeoutMillis);
    }

    void setRequestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = Math.max(0, requestTimeoutMillis);
        if (this.requestTimeoutMillis > 0) {
            builder.timeout(Duration.ofMillis(this.requestTimeoutMillis));
        }
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
        try {
            HttpRequest request = builder.method(method, body).build();
            HttpClient client = client(redirects);
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new ProxyResponse(response.statusCode(), response.headers(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while proxying request", e);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage());
        }
    }

    private void setOrAddHeader(boolean replace, String name, String value) {
        if (isJdkRestrictedHeader(name) && !HOST.equalsIgnoreCase(name)) {
            return;
        }

        if (replace) {
            builder.setHeader(name, value);
        } else {
            builder.header(name, value);
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

    private HttpClient client(boolean redirects) {
        if (connectTimeoutMillis <= 0) {
            return redirects ? REDIRECT_CLIENT : NO_REDIRECT_CLIENT;
        }
        return newClient(redirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER, connectTimeoutMillis);
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
