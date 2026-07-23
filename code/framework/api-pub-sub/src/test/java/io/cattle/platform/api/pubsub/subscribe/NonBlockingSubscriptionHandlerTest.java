package io.cattle.platform.api.pubsub.subscribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.cattle.platform.async.retry.Retry;
import io.cattle.platform.async.retry.RetryTimeoutService;
import io.cattle.platform.eventing.EventListener;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class NonBlockingSubscriptionHandlerTest {

    @Test
    public void subscriptionPingSettingsReadDynamicValuesThroughWrapper() {
        final String intervalKey = "api.sub.ping.interval.millis";
        final String maxPingsKey = "api.sub.max.pings";

        try {
            ConfigurationManager.getConfigInstance().setProperty(intervalKey, "1234");
            ConfigurationManager.getConfigInstance().setProperty(maxPingsKey, "7");

            SubscriptionSettings settings = ArchaiusSubscriptionSettings.create();

            assertEquals(1234L, settings.pingIntervalMillis());
            assertEquals(7, settings.maxPings());
        } finally {
            clearProperty(intervalKey);
            clearProperty(maxPingsKey);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingSubscriptionSettings() {
        new TestSubscriptionHandler(null);
    }

    @Test
    public void schedulePingUsesInjectedSubscriptionSettings() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CapturingRetryTimeoutService retryTimeout = new CapturingRetryTimeoutService();
            TestSubscriptionHandler handler = new TestSubscriptionHandler(settings(250L, 3));
            handler.retryTimeout = retryTimeout;
            handler.executorService = executor;

            Future<?> future = handler.schedulePing(event -> {
            }, new NoopMessageWriter(), new AtomicBoolean(false));

            assertNotNull(future);
            assertNotNull(retryTimeout.retry);
            assertEquals(3, retryTimeout.retry.getRetries());
            assertEquals(Long.valueOf(250L), retryTimeout.retry.getTimeoutMillis());
        } finally {
            executor.shutdownNow();
        }
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static SubscriptionSettings settings(long pingIntervalMillis, int maxPings) {
        return new TestSubscriptionSettings(pingIntervalMillis, maxPings);
    }

    private static final class TestSubscriptionSettings implements SubscriptionSettings {
        private final long pingIntervalMillis;
        private final int maxPings;

        TestSubscriptionSettings(long pingIntervalMillis, int maxPings) {
            this.pingIntervalMillis = pingIntervalMillis;
            this.maxPings = maxPings;
        }

        @Override
        public long pingIntervalMillis() {
            return pingIntervalMillis;
        }

        @Override
        public int maxPings() {
            return maxPings;
        }
    }

    private static final class TestSubscriptionHandler extends NonBlockingSubscriptionHandler {
        TestSubscriptionHandler(SubscriptionSettings settings) {
            super(settings);
        }

        @Override
        protected MessageWriter getMessageWriter(ApiRequest apiRequest) throws IOException {
            return new NoopMessageWriter();
        }
    }

    private static final class CapturingRetryTimeoutService implements RetryTimeoutService {
        private Retry retry;

        @Override
        public Object submit(Retry retry) {
            this.retry = retry;
            return retry;
        }

        @Override
        public void completed(Object obj) {
        }
    }

    private static final class NoopMessageWriter implements MessageWriter {
        @Override
        public void write(String message, Object writeLock) throws IOException {
        }

        @Override
        public void close() {
        }
    }
}
