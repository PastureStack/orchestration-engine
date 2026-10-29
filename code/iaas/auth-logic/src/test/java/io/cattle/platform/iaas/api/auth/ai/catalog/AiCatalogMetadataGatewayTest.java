package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.json.JacksonJsonMapper;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AiCatalogMetadataGatewayTest {
    @Test public void fixedDestinationGetAndProjectHeaderCannotBeChangedByQueryText() {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var gateway = new AiCatalogMetadataGateway(request -> {
            sent.set(request); return new AiCatalogMetadataGateway.Reply(200, "{\"resourceType\":\"catalogTemplate\",\"data\":[]}".getBytes(StandardCharsets.UTF_8));
        }); gateway.json = new JacksonJsonMapper();
        gateway.read("1a77", new AiCatalogMetadataQuery("?url=http://evil&Cookie=secret", null, null));
        HttpRequest request = sent.get(); assertEquals("GET", request.method()); assertEquals("127.0.0.1", request.uri().getHost());
        assertEquals(8088, request.uri().getPort()); assertEquals("/v1-catalog/ai/templates", request.uri().getPath());
        assertEquals("1a77", request.headers().firstValue("X-API-Project-Id").orElseThrow());
        assertEquals(2, request.headers().map().size()); assertTrue(request.headers().firstValue("Authorization").isEmpty());
        assertTrue(request.headers().firstValue("Cookie").isEmpty()); assertTrue(request.uri().getRawQuery().startsWith("query=%3Furl%3D"));
    }

    @Test public void nonSuccessAndOversizeDoNotRelayResponseSecretsOrRedirect() {
        for (int status : List.of(302, 401, 500, 200)) {
            byte[] bytes = status == 200 ? new byte[AiCatalogMetadataGateway.MAX_BYTES + 1] : "private-password".getBytes(StandardCharsets.UTF_8);
            var gateway = new AiCatalogMetadataGateway(request -> new AiCatalogMetadataGateway.Reply(status, bytes)); gateway.json = new JacksonJsonMapper();
            assertEquals("CatalogMetadataUnavailable", AiCatalogMetadataServiceTest.rejected(() -> gateway.read("1a77", new AiCatalogMetadataQuery(null, null, null))));
        }
        var transport = new AiCatalogMetadataGateway.HttpTransport();
        assertEquals(HttpClient.Redirect.NEVER, transport.client.followRedirects());
        assertEquals(List.of(Proxy.NO_PROXY), transport.client.proxy().orElseThrow().select(URI.create("http://127.0.0.1:8088")));
    }

    @Test public void boundedSubscriberCancelsBeforeCopyAndAcceptsFragmentedWithinLimit() throws Exception {
        var body = new AiCatalogMetadataGateway.BoundedBody(5); Flow.Subscription subscription = mock(Flow.Subscription.class);
        body.onSubscribe(subscription); body.onNext(List.of(ByteBuffer.wrap(new byte[4]), ByteBuffer.wrap(new byte[2])));
        verify(subscription).cancel(); assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally()); assertEquals(4, body.bytes.size());
        var good = new AiCatalogMetadataGateway.BoundedBody(5); good.onSubscribe(mock(Flow.Subscription.class));
        good.onNext(List.of(ByteBuffer.wrap(new byte[2]), ByteBuffer.wrap(new byte[3]))); good.onComplete();
        assertEquals(5, good.getBody().toCompletableFuture().get().length);
    }

    @Test public void entireResponseDeadlineCancelsPendingBodyFuture() throws Exception {
        HttpClient client = mock(HttpClient.class); CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        var transport = new AiCatalogMetadataGateway.HttpTransport(client, 1);
        try { transport.exchange(HttpRequest.newBuilder(AiCatalogMetadataGateway.ENDPOINT).GET().build()); fail("must timeout"); }
        catch (IOException expected) { assertTrue(pending.isCancelled()); assertFalse(expected.getMessage().contains("private")); }
    }

    @Test public void versionQueryStillUsesOnlyTheFixedEndpointAndClosedParameters() {
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        var gateway = new AiCatalogMetadataGateway(request -> { sent.set(request); return new AiCatalogMetadataGateway.Reply(404, new byte[0]); });
        assertEquals("CatalogMetadataNotFound", AiCatalogMetadataServiceTest.rejected(() -> gateway.read("1a77",
                new AiCatalogMetadataQuery(null, null, AiCatalogMetadataServiceTest.ID, true, "cvm-" + "b".repeat(64)))));
        String query = sent.get().uri().getRawQuery(); assertTrue(query.contains("versions=true")); assertTrue(query.contains("versionId=cvm-"));
        assertEquals("127.0.0.1", sent.get().uri().getHost());
    }
}
