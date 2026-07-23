package io.cattle.platform.docker.machine.launch;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class LocalReloadRequest {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private LocalReloadRequest() {
    }

    static void post(String url, String... headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
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
        HttpRequest request = HttpRequest.newBuilder(uri)
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
}
