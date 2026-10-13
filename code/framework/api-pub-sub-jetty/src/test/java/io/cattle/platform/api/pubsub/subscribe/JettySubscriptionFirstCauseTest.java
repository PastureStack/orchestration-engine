package io.cattle.platform.api.pubsub.subscribe;

import static org.junit.Assert.*;

import io.cattle.platform.api.pubsub.subscribe.jetty.WebSocketMessageWriter;
import io.cattle.platform.eventing.EventListener;
import io.cattle.platform.eventing.EventService;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.Test;

public class JettySubscriptionFirstCauseTest {
    @Test public void handlerServerCloseCannotReplaceSelectedIoCauseWhileSessionCloseBlocks() throws Exception {
        ApiContext.newContext().setIdFormatter(new IdentityFormatter()); ApiContext.getContext().setPolicy("original-policy");
        WebSocketMessageWriter writer = new WebSocketMessageWriter(null);
        CountDownLatch inSessionClose = new CountDownLatch(1), releaseSessionClose = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<SubscriptionCloseReason> terminals = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler(writer, terminals);
        writer.onWebSocketConnect((Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[]{Session.class}, (p, method, args) -> {
            if (method.getName().equals("isOpen")) return true;
            if (method.getName().equals("close")) {
                inSessionClose.countDown(); releaseSessionClose.await(); writer.onWebSocketClose(1000, "private-close-text");
            }
            return null;
        }));
        Thread io = new Thread(() -> {
            try { writer.onWebSocketError(new IOException("private-io-fault")); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            assertTrue(handler.subscribe(List.of("resource.change"), new ApiRequest(null, null), true));
            io.start(); assertTrue(inSessionClose.await(5, TimeUnit.SECONDS));
            assertEquals(SubscriptionCloseReason.IO_ERROR, writer.getCloseReason());
            handler.stop(); // Wins the handler CAS while the writer's I/O close has not returned.
            assertEquals(List.of(SubscriptionCloseReason.IO_ERROR), terminals); assertEquals(1, handler.unsubscribes);
        } finally { releaseSessionClose.countDown(); io.join(5000); ApiContext.remove(); }
        assertFalse(io.isAlive()); assertNull(failure.get());
        assertEquals(List.of(SubscriptionCloseReason.IO_ERROR), terminals); assertEquals(1, handler.unsubscribes);
    }

    private static class Handler extends NonBlockingSubscriptionHandler {
        final WebSocketMessageWriter writer; AtomicBoolean disconnect; EventListener listener; int unsubscribes;
        Handler(WebSocketMessageWriter writer, List<SubscriptionCloseReason> terminals) {
            this.writer = writer;
            eventService = (EventService) Proxy.newProxyInstance(EventService.class.getClassLoader(), new Class<?>[]{EventService.class}, (p, method, args) -> {
                if (method.getName().equals("unsubscribe")) unsubscribes++; return null;
            });
            subscriptionAuthorizations = List.of(new SubscriptionAuthorization() {
                @Override public SubscriptionAuthorization.Session capture(ApiRequest request) { return null; }
                @Override public Lifecycle captureLifecycle(ApiRequest request) { return terminals::add; }
            });
        }
        void stop() { unsubscribe(disconnect, writer, listener); }
        @Override protected MessageWriter getMessageWriter(ApiRequest request) { return writer; }
        @Override protected Future<?> subscribe(Collection<String> names, EventListener listener, MessageWriter writer, AtomicBoolean disconnect, Object lock, boolean strip) {
            this.listener = listener; this.disconnect = disconnect; return CompletableFuture.completedFuture(null);
        }
    }
}
