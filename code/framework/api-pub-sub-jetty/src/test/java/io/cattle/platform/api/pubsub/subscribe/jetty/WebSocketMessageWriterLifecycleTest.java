package io.cattle.platform.api.pubsub.subscribe.jetty;

import static org.junit.Assert.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.cattle.platform.api.pubsub.subscribe.SubscriptionCloseReason;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class WebSocketMessageWriterLifecycleTest {
    @Test public void alreadyClosedWriterReplaysFirstCauseOnce() throws Exception {
        WebSocketMessageWriter writer = new WebSocketMessageWriter(null, () -> 5);
        writer.onWebSocketClose(1000, "private-close-value");
        List<SubscriptionCloseReason> closed = new ArrayList<>(); writer.setCloseListener(closed::add);
        writer.close(); writer.onWebSocketError(new IOException("private-io-value"));
        assertEquals(List.of(SubscriptionCloseReason.CLIENT_CLOSED), closed);
        assertThrows(IOException.class, () -> writer.write("private-body", new Object()));
    }

    @Test public void serverReasonSurvivesReentrantJettyCloseCallback() {
        WebSocketMessageWriter writer = new WebSocketMessageWriter(null, () -> 5);
        writer.onWebSocketConnect((Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[]{Session.class}, (p, method, args) -> {
            if (method.getName().equals("isOpen")) return true;
            if (method.getName().equals("close")) writer.onWebSocketClose(1000, "private-reentrant-close-value");
            return null;
        }));
        List<SubscriptionCloseReason> closed = new ArrayList<>(); writer.setCloseListener(closed::add);
        writer.close(SubscriptionCloseReason.AUTHORIZATION_FAILED); writer.close();
        assertEquals(List.of(SubscriptionCloseReason.AUTHORIZATION_FAILED), closed);
    }

    @Test public void asynchronousWriteFailureIsIoNotAuthorization() {
        WebSocketMessageWriter writer = new WebSocketMessageWriter(null, () -> 5);
        List<SubscriptionCloseReason> closed = new ArrayList<>(); writer.setCloseListener(closed::add);
        new WebSocketWriteCallback(writer).fail(new IOException("private-callback-value"));
        writer.onWebSocketClose(1001, "private-close-value");
        assertEquals(List.of(SubscriptionCloseReason.IO_ERROR), closed);
    }

    @Test public void concurrentCloseCallbacksNotifyOnceOutsideWriterMonitor() throws Exception {
        WebSocketMessageWriter writer = new WebSocketMessageWriter(null, () -> 5);
        List<SubscriptionCloseReason> closed = Collections.synchronizedList(new ArrayList<>());
        writer.setCloseListener(reason -> { assertFalse(Thread.holdsLock(writer)); closed.add(reason); });
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Thread client = new Thread(() -> {
            ready.countDown(); await(start); writer.onWebSocketClose(1000, "private-close-value");
        });
        Thread io = new Thread(() -> {
            ready.countDown(); await(start); writer.onWebSocketError(new IOException("private-error-value"));
        });
        client.start(); io.start(); ready.await(); start.countDown(); client.join(); io.join();
        assertEquals(1, closed.size());
        assertTrue(List.of(SubscriptionCloseReason.CLIENT_CLOSED, SubscriptionCloseReason.IO_ERROR).contains(closed.get(0)));
    }

    @Test public void rawCloseTextAndThrowableDoNotReachLogMessageArgumentsOrCause() {
        Logger logger = (Logger) LoggerFactory.getLogger(WebSocketMessageWriter.class);
        Level before = logger.getLevel(); ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start();
        logger.addAppender(logs); logger.setLevel(Level.TRACE);
        String sentinel = "known-secret-close-and-error-sentinel";
        try {
            for (String identifier : new String[]{null, sentinel}) {
                WebSocketMessageWriter writer = new WebSocketMessageWriter(identifier, () -> 5);
                writer.onWebSocketClose(1000, sentinel);
                writer.onWebSocketError(new IOException(sentinel));
            }
            assertFalse(logs.list.isEmpty());
            for (ILoggingEvent event : logs.list) {
                assertFalse(event.getFormattedMessage().contains(sentinel)); assertNull(event.getThrowableProxy());
                if (event.getArgumentArray() != null) {
                    for (Object argument : event.getArgumentArray()) assertFalse(String.valueOf(argument).contains(sentinel));
                }
            }
        } finally { logger.detachAppender(logs); logger.setLevel(before); logs.stop(); }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
