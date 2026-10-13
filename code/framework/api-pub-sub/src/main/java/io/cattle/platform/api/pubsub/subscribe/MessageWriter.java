package io.cattle.platform.api.pubsub.subscribe;

import java.io.IOException;
import java.util.function.Consumer;

public interface MessageWriter {

    void write(String message, Object writeLock) throws IOException;

    void close();

    default void close(SubscriptionCloseReason reason) { close(); }

    /** An immutable first cause, or null when an older writer does not expose one. */
    default SubscriptionCloseReason getCloseReason() { return null; }

    /** A writer must replay an already observed close to a newly registered listener. */
    default void setCloseListener(Consumer<SubscriptionCloseReason> listener) { }

}
