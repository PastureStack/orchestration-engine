package io.cattle.platform.api.pubsub.subscribe.jetty;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

final class ArchaiusWebSocketWriterSettings implements WebSocketWriterSettings {

    private final ConfigProperty<Integer> maxQueuedMessages = ArchaiusUtil.getIntProperty("subscribe.max.queued.messages");

    static WebSocketWriterSettings create() {
        return new ArchaiusWebSocketWriterSettings();
    }

    @Override
    public int maxQueuedMessages() {
        return maxQueuedMessages.get();
    }
}
