package io.cattle.platform.api.pubsub.subscribe.jetty;

import org.eclipse.jetty.websocket.api.Callback;

public class WebSocketWriteCallback implements Callback {

    WebSocketMessageWriter writer;

    public WebSocketWriteCallback(WebSocketMessageWriter writer) {
        this.writer = writer;
    }

    @Override
    public void fail(Throwable e) {
        writer.close();
    }

    @Override
    public void succeed() {
        writer.getQueuedMessageCount().decrementAndGet();
    }

}
