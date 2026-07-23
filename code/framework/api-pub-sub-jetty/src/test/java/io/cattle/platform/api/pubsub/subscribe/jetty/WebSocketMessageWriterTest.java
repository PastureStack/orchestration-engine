package io.cattle.platform.api.pubsub.subscribe.jetty;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class WebSocketMessageWriterTest {

    @Test
    public void maxQueuedMessagesReadsDynamicConfigThroughWrapper() throws Exception {
        final String key = "subscribe.max.queued.messages";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "1");

            WebSocketMessageWriter writer = new WebSocketMessageWriter("test-subscription");
            writer.setQueuedMessageCount(new AtomicInteger(2));

            try {
                writer.write("{}", new Object());
                fail("Expected queue limit to reject the message");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("Reached max queued messages [1]"));
            }
        } finally {
            if (ConfigurationManager.getConfigInstance().containsKey(key)) {
                ConfigurationManager.getConfigInstance().clearProperty(key);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingWebSocketWriterSettings() {
        new WebSocketMessageWriter("test-subscription", null);
    }

    @Test
    public void maxQueuedMessagesUsesInjectedSettings() throws Exception {
        WebSocketMessageWriter writer = new WebSocketMessageWriter("test-subscription", () -> 1);
        writer.setQueuedMessageCount(new AtomicInteger(2));

        try {
            writer.write("{}", new Object());
            fail("Expected queue limit to reject the message");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Reached max queued messages [1]"));
        }
    }
}
