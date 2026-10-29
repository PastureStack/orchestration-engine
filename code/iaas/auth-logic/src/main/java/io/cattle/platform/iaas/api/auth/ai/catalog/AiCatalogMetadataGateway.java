package io.cattle.platform.iaas.api.auth.ai.catalog;

import io.cattle.platform.json.JsonMapper;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One fixed same-container metadata destination; never a general API broker. */
public class AiCatalogMetadataGateway {
    static final URI ENDPOINT = URI.create("http://127.0.0.1:8088/v1-catalog/ai/templates");
    static final int MAX_BYTES = 1048576;
    @Inject JsonMapper json;
    final Transport transport;
    public AiCatalogMetadataGateway() { this(new HttpTransport()); }
    AiCatalogMetadataGateway(Transport transport) { this.transport = transport; }
    record Reply(int status, byte[] body) { }
    interface Transport { Reply exchange(HttpRequest request) throws IOException, InterruptedException; }

    public Map<String, Object> read(String projectId, AiCatalogMetadataQuery input) {
        if (projectId == null || !projectId.matches("1a[1-9][0-9]{0,18}")) throw AiCatalogMetadataQuery.error(403, "OwnerPermissionDenied");
        List<String> pairs = new ArrayList<>();
        if (input.query() != null) pairs.add("query=" + encoded(input.query()));
        if (input.marker() != null) pairs.add("marker=" + encoded(input.marker()));
        if (input.templateId() != null) pairs.add("templateId=" + encoded(input.templateId()));
        if (input.versions()) pairs.add("versions=true");
        if (input.versionId() != null) pairs.add("versionId=" + encoded(input.versionId()));
        URI uri = URI.create(ENDPOINT.toString() + (pairs.isEmpty() ? "" : "?" + String.join("&", pairs)));
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json").header("X-API-Project-Id", projectId).build();
        Reply reply;
        try { reply = transport.exchange(request); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (IOException | RuntimeException failure) { throw unavailable(); }
        if (reply.status() == 404) throw AiCatalogMetadataQuery.error(404, "CatalogMetadataNotFound");
        if (reply.status() == 409) throw AiCatalogMetadataQuery.error(409, "CatalogMetadataCursorStale");
        if (reply.status() == 422) throw AiCatalogMetadataQuery.error(422, "CatalogMetadataInputInvalid");
        if (reply.status() != 200 || reply.body() == null || reply.body().length > MAX_BYTES) throw unavailable();
        try { return json.readValue(reply.body()); }
        catch (IOException | RuntimeException invalid) { throw unavailable(); }
    }
    private static String encoded(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static io.github.ibuildthecloud.gdapi.exception.ClientVisibleException unavailable() {
        return AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
    }

    static final class HttpTransport implements Transport {
        final HttpClient client;
        final long deadlineMillis;
        final int maxBytes;
        HttpTransport() { this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                }).build(), 5000); }
        HttpTransport(HttpClient client, long deadlineMillis) { this(client, deadlineMillis, MAX_BYTES); }
        HttpTransport(HttpClient client, long deadlineMillis, int maxBytes) {
            this.client = client; this.deadlineMillis = deadlineMillis; this.maxBytes = maxBytes;
        }
        @Override public Reply exchange(HttpRequest request) throws IOException, InterruptedException {
            CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, ignored -> new BoundedBody(maxBytes));
            try {
                HttpResponse<byte[]> response = pending.get(deadlineMillis, TimeUnit.MILLISECONDS);
                return new Reply(response.statusCode(), response.body());
            } catch (TimeoutException timeout) {
                pending.cancel(true); throw new IOException("Catalog metadata deadline exceeded");
            } catch (ExecutionException failure) { throw new IOException("Catalog metadata unavailable"); }
            catch (InterruptedException interrupted) { pending.cancel(true); throw interrupted; }
        }
    }

    /** Cancel while streaming, before allocating/copying an oversized body. */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final int limit;
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value; value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Catalog metadata body exceeds limit")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(new IOException("Catalog metadata body unavailable")); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
