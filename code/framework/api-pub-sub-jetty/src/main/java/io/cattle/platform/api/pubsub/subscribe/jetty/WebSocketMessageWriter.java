package io.cattle.platform.api.pubsub.subscribe.jetty;

import io.cattle.platform.api.pubsub.subscribe.MessageWriter;

import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

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
    private boolean connectionClosed = false;
    private AtomicInteger queuedMessageCount = new AtomicInteger();

    private String identifier;
    private final WebSocketWriterSettings settings;

    public WebSocketMessageWriter(String identifier) {
        this(identifier, DEFAULT_SETTINGS);
    }

    WebSocketMessageWriter(String identifier, WebSocketWriterSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("WebSocket writer settings are required");
        }
        this.identifier = identifier;
        this.settings = settings;
        if (identifier != null) {
            log.info("Creating websocket message writer for {}", identifier);
        }
    }

    @OnWebSocketOpen
    public void onWebSocketConnect(Session session) {
        this.session = session;
    }

    @OnWebSocketClose
    public void onWebSocketClose(int closeCode, String message) {
        connectionClosed = true;
        if (identifier != null) {
            log.info("Websocket connection closed for {}. Code: [{}], message: [{}].", identifier, closeCode, message);
        } else {
            log.debug("Websocket connection closed. Code: [{}], message: [{}].", closeCode, message);
        }
    }

    @OnWebSocketError
    public void onWebSocketError(Throwable cause) {
        if (identifier != null) {
            log.warn("Unexpected websocket error for {}", identifier);
        }
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
                close();
            }
        }
    }

    @Override
    public void close() {
        if (!connectionClosed) {
            if (session != null && session.isOpen()) {
                session.close();
            }
            connectionClosed = true;
        }
    }

    public AtomicInteger getQueuedMessageCount() {
        return queuedMessageCount;
    }

    public void setQueuedMessageCount(AtomicInteger queuedMessageCount) {
        this.queuedMessageCount = queuedMessageCount;
    }
}
