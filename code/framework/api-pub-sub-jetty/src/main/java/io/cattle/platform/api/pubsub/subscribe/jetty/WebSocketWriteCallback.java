package io.cattle.platform.api.pubsub.subscribe.jetty;

import org.eclipse.jetty.websocket.api.Callback;
import io.cattle.platform.api.pubsub.subscribe.SubscriptionCloseReason;

public class WebSocketWriteCallback implements Callback {

    WebSocketMessageWriter writer;

    public WebSocketWriteCallback(WebSocketMessageWriter writer) {
        this.writer = writer;
    }

    @Override
    public void fail(Throwable e) {
        writer.close(SubscriptionCloseReason.IO_ERROR);
    }

    @Override
    public void succeed() {
        writer.getQueuedMessageCount().decrementAndGet();
    }

}
