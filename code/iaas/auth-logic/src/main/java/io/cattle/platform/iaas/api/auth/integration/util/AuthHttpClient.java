package io.cattle.platform.iaas.api.auth.integration.util;

import io.cattle.platform.util.net.HttpResponseUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class AuthHttpClient {

    public static final String APPLICATION_JSON = "application/json";
    public static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";

    private static final String MAX_RESPONSE_BYTES_PROPERTY = "cattle.auth.http.maxResponseBytes";
    private static final HttpClient DEFAULT_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private AuthHttpClient() {
    }

    public static Response get(String url, String... headers) throws IOException {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), 0, headers);
    }

    public static Response postJson(String url, String body, String... headers) throws IOException {
        return postJson(url, body, 0, headers);
    }

    public static Response postJson(String url, String body, int timeoutMillis, String... headers) throws IOException {
        return post(url, body, APPLICATION_JSON, timeoutMillis, headers);
    }

    public static Response postForm(String url, String body, String... headers) throws IOException {
        return post(url, body, APPLICATION_FORM_URLENCODED, 0, headers);
    }

    public static boolean isConnectionFailure(IOException e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof ConnectException || current instanceof HttpConnectTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Response post(String url, String body, String contentType, int timeoutMillis, String... headers)
            throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return send(builder, timeoutMillis, headers);
    }

    private static Response send(HttpRequest.Builder builder, int timeoutMillis, String... headers) throws IOException {
        applyHeaders(builder, headers);
        HttpClient client = DEFAULT_CLIENT;
        if (timeoutMillis > 0) {
            Duration timeout = Duration.ofMillis(timeoutMillis);
            builder.timeout(timeout);
            client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }

        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                return new Response(response.statusCode(),
                        HttpResponseUtils.readUtf8String(responseBody, MAX_RESPONSE_BYTES_PROPERTY));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling authentication provider", e);
        }
    }

    private static void applyHeaders(HttpRequest.Builder builder, String... headers) {
        if (headers.length % 2 != 0) {
            throw new IllegalArgumentException("Headers must be provided as name/value pairs");
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
    }

    public static final class Response {
        private final int statusCode;
        private final String body;

        private Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }
}
