package io.cattle.platform.api.pubsub.subscribe.jetty;

import io.cattle.platform.api.pubsub.subscribe.MessageWriter;
import io.cattle.platform.api.pubsub.subscribe.SubscriptionCloseReason;

import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.exceptions.WebSocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebSocket(autoDemand = true)
public class WebSocketMessageWriter implements MessageWriter {

    private static final Logger log = LoggerFactory.getLogger(WebSocketMessageWriter.class);

    private static final WebSocketWriterSettings DEFAULT_SETTINGS = ArchaiusWebSocketWriterSettings.create();

    private Session session;
    private volatile boolean connectionClosed = false;
    private SubscriptionCloseReason closeReason;
    private Consumer<SubscriptionCloseReason> closeListener;
    private boolean closeNotified;
    private AtomicInteger queuedMessageCount = new AtomicInteger();

    private final WebSocketWriterSettings settings;

    public WebSocketMessageWriter(String identifier) {
        this(identifier, DEFAULT_SETTINGS);
    }

    WebSocketMessageWriter(String identifier, WebSocketWriterSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("WebSocket writer settings are required");
        }
        this.settings = settings;
        if (identifier != null) {
            log.info("Creating websocket message writer");
        }
    }

    @OnWebSocketOpen
    public void onWebSocketConnect(Session session) {
        this.session = session;
    }

    @OnWebSocketClose
    public void onWebSocketClose(int closeCode, String message) {
        observedClose(SubscriptionCloseReason.CLIENT_CLOSED);
        log.debug("Websocket connection closed. Code: [{}].", closeCode);
    }

    @OnWebSocketError
    public void onWebSocketError(Throwable cause) {
        close(SubscriptionCloseReason.IO_ERROR);
        log.warn("Unexpected websocket error");
    }

    @Override
    public void write(String message, Object writeLock) throws IOException {
        // The explicit connnectionClosed check is because the session is null until the connection is initially established
        if (connectionClosed) {
            throw new EOFException("WebSocket is closed.");
        }

        int maxQueuedMessages = settings.maxQueuedMessages();
        if (queuedMessageCount.get() > maxQueuedMessages) {
            throw new IOException("Reached max queued messages [" + maxQueuedMessages + "].");
        }

        if (session != null && session.isOpen()) {
            try {
                session.sendText(message, new WebSocketWriteCallback(this));
                queuedMessageCount.incrementAndGet();
            } catch (WebSocketException e) {
                // Thrown if getRemote() determines the connection was closed by the client. No need to log.
                close(SubscriptionCloseReason.IO_ERROR);
            }
        }
    }

    @Override
    public void close() {
        close(SubscriptionCloseReason.SERVER_CLOSED);
    }

    @Override
    public void close(SubscriptionCloseReason reason) {
        // Select the cause before session.close() can reenter the close callback.
        boolean first;
        synchronized (this) {
            first = closeReason == null;
            if (first) closeReason = Objects.requireNonNull(reason);
            connectionClosed = true;
        }
        try {
            if (first && session != null && session.isOpen()) session.close();
        } finally {
            notifyClose();
        }
    }

    private void observedClose(SubscriptionCloseReason reason) {
        synchronized (this) {
            if (closeReason == null) closeReason = reason;
            connectionClosed = true;
        }
        notifyClose();
    }

    @Override
    public synchronized SubscriptionCloseReason getCloseReason() {
        return closeReason;
    }

    @Override
    public void setCloseListener(Consumer<SubscriptionCloseReason> listener) {
        synchronized (this) {
            if (closeListener != null) throw new IllegalStateException("Close listener already registered");
            closeListener = Objects.requireNonNull(listener);
        }
        notifyClose();
    }

    private void notifyClose() {
        Consumer<SubscriptionCloseReason> listener;
        SubscriptionCloseReason reason;
        synchronized (this) {
            if (closeReason == null || closeListener == null || closeNotified) return;
            closeNotified = true;
            listener = closeListener;
            reason = closeReason;
        }
        // No callback executes while holding the writer's monitor.
        listener.accept(reason);
    }

    public AtomicInteger getQueuedMessageCount() {
        return queuedMessageCount;
    }

    public void setQueuedMessageCount(AtomicInteger queuedMessageCount) {
        this.queuedMessageCount = queuedMessageCount;
    }
}
