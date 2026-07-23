package io.cattle.platform.framework.secret;

import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.util.net.HttpResponseUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class SecretHttpClient {

    private static final String APPLICATION_JSON = "application/json";
    private static final String MAX_RESPONSE_BYTES_PROPERTY = "cattle.secrets.http.maxResponseBytes";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private SecretHttpClient() {
    }

    static String postJsonForString(String url, String body, String failureMessage) throws IOException {
        HttpResponse<InputStream> response = postJson(url, body);
        try (InputStream responseBody = response.body()) {
            requireSuccess(response, failureMessage);
            return HttpResponseUtils.readUtf8String(responseBody, MAX_RESPONSE_BYTES_PROPERTY);
        }
    }

    static Map<String, Object> postJsonForMap(String url, String body, JsonMapper jsonMapper, String failureMessage)
            throws IOException {
        HttpResponse<InputStream> response = postJson(url, body);
        try (InputStream responseBody = response.body()) {
            requireSuccess(response, failureMessage);
            return jsonMapper.readValue(HttpResponseUtils.readUtf8String(responseBody, MAX_RESPONSE_BYTES_PROPERTY));
        }
    }

    static void postJsonAllowingNotFound(String url, String body, String failureMessage) throws IOException {
        HttpResponse<InputStream> response = postJson(url, body);
        try (InputStream responseBody = response.body()) {
            int statusCode = response.statusCode();
            if (statusCode >= 300 && statusCode != 404) {
                throw new IOException(failureMessage + statusCode);
            }
        }
    }

    private static HttpResponse<InputStream> postJson(String url, String body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling secrets service", e);
        }
    }

    private static void requireSuccess(HttpResponse<?> response, String failureMessage) throws IOException {
        int statusCode = response.statusCode();
        if (statusCode >= 300) {
            throw new IOException(failureMessage + statusCode);
        }
    }
}
