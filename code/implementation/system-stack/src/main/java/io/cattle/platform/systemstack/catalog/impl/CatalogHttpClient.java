package io.cattle.platform.systemstack.catalog.impl;

import io.cattle.platform.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class CatalogHttpClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private CatalogHttpClient() {
    }

    static <T> T getJson(String url, JsonMapper jsonMapper, Class<T> type) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            return null;
        }

        try (InputStream body = response.body()) {
            return jsonMapper.readValue(body, type);
        }
    }

    static int postNoBody(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException {
        try {
            return HTTP_CLIENT.send(request, bodyHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling catalog service", e);
        }
    }
}
