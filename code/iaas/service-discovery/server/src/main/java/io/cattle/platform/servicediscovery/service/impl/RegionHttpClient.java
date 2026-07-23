package io.cattle.platform.servicediscovery.service.impl;

import io.cattle.platform.core.model.Region;
import io.cattle.platform.util.net.HttpResponseUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpHeaders;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import org.apache.commons.codec.binary.Base64;

final class RegionHttpClient {

    private static final String APPLICATION_JSON = "application/json";
    private static final String MAX_RESPONSE_BYTES_PROPERTY = "cattle.region.http.maxResponseBytes";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private RegionHttpClient() {
    }

    static HttpResponse<String> get(String url, Region region) throws IOException {
        return send(request(url, region).GET().build());
    }

    static HttpResponse<String> postJson(String url, Region region, String body) throws IOException {
        return send(request(url, region)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    static HttpResponse<String> delete(String url, Region region) throws IOException {
        return send(request(url, region).DELETE().build());
    }

    private static HttpRequest.Builder request(String url, Region region) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authorization(region))
                .header("X-ENFORCE-AUTHENTICATION", "true")
                .header("Content-Type", APPLICATION_JSON)
                .header("Accept", APPLICATION_JSON);
    }

    private static String authorization(Region region) {
        String encodedKeys = Base64.encodeBase64String(
                String.format("%s:%s", region.getPublicValue(), region.getSecretValue())
                        .getBytes(StandardCharsets.UTF_8));
        return String.format("Basic %s", encodedKeys);
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                return new StringHttpResponse(response,
                        HttpResponseUtils.readUtf8String(responseBody, MAX_RESPONSE_BYTES_PROPERTY));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling external region", e);
        }
    }

    private static final class StringHttpResponse implements HttpResponse<String> {
        private final HttpResponse<?> delegate;
        private final String body;

        private StringHttpResponse(HttpResponse<?> delegate, String body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public HttpRequest request() {
            return delegate.request();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return delegate.headers();
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return delegate.sslSession();
        }

        @Override
        public URI uri() {
            return delegate.uri();
        }

        @Override
        public HttpClient.Version version() {
            return delegate.version();
        }
    }
}
