package io.cattle.platform.docker.machine.launch;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class LocalReloadRequest {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private LocalReloadRequest() {
    }

    static void postLoopback(int port, String path, String... headers) throws IOException {
        sendPost(loopbackUri(false, port, path, null), headers);
    }

    static void postLoopbackWithQuery(int port, String path, String query, String... headers) throws IOException {
        sendPost(loopbackUri(false, port, path, query), headers);
    }

    static void postLoopbackBase(String baseUrl, String suffix, String... headers) throws IOException {
        URI base;
        try {
            base = new URI(baseUrl);
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid loopback service URL", e);
        }
        URI normalized = normalizeLoopback(base, suffix);
        sendPost(normalized, headers);
    }

    private static void sendPost(URI uri, String... headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(DEFAULT_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody());

        if (headers.length % 2 != 0) {
            throw new IllegalArgumentException("headers must contain name/value pairs");
        }

        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }

        try {
            HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending local reload request", e);
        }
    }

    static boolean isGetSuccessful(URI uri) {
        URI normalized;
        try {
            normalized = normalizeLoopback(uri, "");
        } catch (IllegalArgumentException e) {
            return false;
        }
        HttpRequest request = HttpRequest.newBuilder(normalized)
                .timeout(DEFAULT_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static URI loopbackUri(boolean tls, int port, String path, String query) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Loopback port must be between 1 and 65535");
        }
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Loopback path must be an absolute HTTP path");
        }
        try {
            return new URI(tls ? "https" : "http", null, "127.0.0.1", port, path, query, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid loopback request", e);
        }
    }

    static URI normalizeLoopback(URI input, String suffix) {
        if (input == null || input.getHost() == null || input.getUserInfo() != null || input.getFragment() != null) {
            throw new IllegalArgumentException("Loopback service URL is invalid");
        }
        boolean tls;
        if ("http".equalsIgnoreCase(input.getScheme())) {
            tls = false;
        } else if ("https".equalsIgnoreCase(input.getScheme())) {
            tls = true;
        } else {
            throw new IllegalArgumentException("Loopback service URL must use HTTP or HTTPS");
        }
        String host = input.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (!("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host))) {
            throw new IllegalArgumentException("Loopback service URL must target the local host");
        }
        int port = input.getPort();
        if (port < 0) {
            port = tls ? 443 : 80;
        }
        String basePath = input.getPath();
        if (basePath == null || basePath.isEmpty()) {
            basePath = "/";
        }
        String addition = suffix == null ? "" : suffix;
        if (!addition.isEmpty() && !addition.startsWith("/")) {
            throw new IllegalArgumentException("Loopback path suffix must start with a slash");
        }
        String path = basePath.endsWith("/") && addition.startsWith("/")
                ? basePath.substring(0, basePath.length() - 1) + addition
                : basePath + addition;
        return loopbackUri(tls, port, path, input.getQuery());
    }
}
