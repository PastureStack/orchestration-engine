package io.cattle.platform.api.pubsub.subscribe;

import static org.junit.Assert.*;

import io.cattle.platform.eventing.EventListener;
import io.cattle.platform.async.retry.Retry;
import io.cattle.platform.async.retry.RetryTimeoutService;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NonBlockingSubscriptionLifecycleTest {
    @Before public void setup() { ApiContext.newContext().setIdFormatter(new IdentityFormatter()); ApiContext.getContext().setPolicy("original"); }
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void clientCloseIsExactlyOnceAndPreventsFurtherEvents() throws Exception {
        Handler handler = handler(null); handler.start();
        handler.writer.listener.accept(SubscriptionCloseReason.CLIENT_CLOSED);
        handler.writer.listener.accept(SubscriptionCloseReason.IO_ERROR);
        handler.listener.onEvent(new EventVO<>());
        assertEquals(List.of(SubscriptionCloseReason.CLIENT_CLOSED), handler.terminals);
        assertEquals(1, handler.unsubscribes); assertEquals(0, handler.writes);
    }

    @Test public void alreadyClosedWriterIsObservedBeforeEventRegistration() throws Exception {
        Handler handler = handler(null); handler.writer.alreadyClosed = true;
        assertFalse(handler.start()); assertNull(handler.listener);
        assertEquals(List.of(SubscriptionCloseReason.CLIENT_CLOSED), handler.terminals); assertEquals(1, handler.unsubscribes);
    }

    @Test public void ordinaryServerUnsubscribeRetainsServerCause() throws Exception {
        Handler handler = handler(null); handler.start(); handler.stop(); handler.stop();
        assertEquals(List.of(SubscriptionCloseReason.SERVER_CLOSED), handler.terminals);
        assertEquals(1, handler.unsubscribes); assertTrue(handler.writer.closed);
    }

    @Test public void authorizationProcessorAndIoFailuresHaveDifferentCauses() throws Exception {
        Handler auth = handler(event -> { throw new IllegalStateException("private-auth-fault"); }); auth.start();
        auth.listener.onEvent(new EventVO<>());
        assertEquals(List.of(SubscriptionCloseReason.AUTHORIZATION_FAILED), auth.terminals);
        Handler processor = handler(null); processor.processorFault = true; processor.start(); processor.listener.onEvent(new EventVO<>());
        assertEquals(List.of(SubscriptionCloseReason.INTERNAL_ERROR), processor.terminals);
        Handler io = handler(null); io.ioFault = true; io.start(); io.listener.onEvent(new EventVO<>());
        assertEquals(List.of(SubscriptionCloseReason.IO_ERROR), io.terminals);
    }

    @Test public void rowFilterDoesNotTerminateAndLegacyKeepsOriginalPolicy() throws Exception {
        Handler filtered = handler(event -> null); filtered.start(); filtered.listener.onEvent(new EventVO<>());
        assertTrue(filtered.terminals.isEmpty()); assertEquals(0, filtered.writes);
        Handler legacy = handler(null); legacy.start(); legacy.listener.onEvent(new EventVO<>());
        assertEquals("original", legacy.usedPolicy); assertEquals(1, legacy.writes); assertTrue(legacy.terminals.isEmpty());
    }

    @Test public void observerStorageFailureCannotSkipPhysicalCleanup() throws Exception {
        Handler handler = handler(null); handler.auditFault = true; handler.start();
        handler.writer.listener.accept(SubscriptionCloseReason.CLIENT_CLOSED);
        assertTrue(handler.writer.closed); assertEquals(1, handler.unsubscribes); assertEquals(1, handler.terminals.size());
    }

    @Test public void subscriptionSetupFailureHasOneTerminalAndNoBody() throws Exception {
        Handler handler = handler(null); handler.realSubscribe = true;
        assertFalse(handler.start()); assertEquals(List.of(SubscriptionCloseReason.SUBSCRIBE_FAILED), handler.terminals);
        assertEquals(1, handler.unsubscribes); assertEquals(0, handler.writes);
        assertTrue(handler.registered.isEmpty());
    }

    @Test public void lateRegistrationAfterClientCloseIsRemovedWithoutSecondTerminal() throws Exception {
        Handler handler = handler(null); handler.realSubscribe = true; handler.closeDuringRegistration = true;
        assertFalse(handler.start());
        assertEquals(List.of(SubscriptionCloseReason.CLIENT_CLOSED), handler.terminals);
        assertEquals(2, handler.unsubscribes); // Immediate fence plus cleanup of the late registration.
        assertEquals(0, handler.writes);
        assertTrue(handler.registered.isEmpty());
    }

    @Test public void lateRegistrationTimeoutThenSuccessCannotRestoreClosedListener() throws Exception {
        Handler handler = handler(null); handler.realSubscribe = true; handler.closeDuringRegistration = true; handler.registrationTimeout = true;
        assertFalse(handler.start()); assertEquals(2, handler.unsubscribes);
        assertTrue(handler.registered.isEmpty()); assertFalse(handler.pendingRegistration.isDone());
        handler.pendingRegistration.set(null); // Backend acknowledgement arrives only after timeout cleanup.
        assertTrue(handler.pendingRegistration.isDone()); assertTrue(handler.registered.isEmpty());
        assertEquals(List.of(SubscriptionCloseReason.CLIENT_CLOSED), handler.terminals); assertEquals(0, handler.writes);
    }

    @Test public void terminalAuditRetriesAfterPhysicalCleanupAndCancelsOnSuccess() throws Exception {
        Handler handler = handler(null); handler.auditFailuresRemaining = 2; handler.start();
        handler.writer.listener.accept(SubscriptionCloseReason.CLIENT_CLOSED); handler.stop();
        assertTrue(handler.writer.closed); assertEquals(1, handler.unsubscribes);
        assertEquals(1, handler.retries.submissions); Retry scheduled = handler.retries.pending;
        assertNotNull(scheduled); assertEquals(4, scheduled.getRetries()); assertEquals(Long.valueOf(1000), scheduled.getTimeoutMillis());
        handler.retries.fire(); assertFalse(scheduled.getFuture().isDone());
        handler.retries.fire(); assertTrue(scheduled.getFuture().isDone()); assertNull(handler.retries.pending);
        assertNull(scheduled.getFuture().get()); assertEquals(1, handler.unsubscribes);
        assertEquals(List.of(SubscriptionCloseReason.CLIENT_CLOSED, SubscriptionCloseReason.CLIENT_CLOSED, SubscriptionCloseReason.CLIENT_CLOSED), handler.terminals);
        scheduled.getRunnable().run(); // A worker dispatched before success must not retry the trace.
        assertEquals(3, handler.terminals.size()); assertNull(handler.retries.pending);
    }

    @Test public void terminalAuditRetriesAreBoundedAndNeverChangeFirstReason() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(NonBlockingSubscriptionHandler.class);
        Level before = logger.getLevel(); ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start();
        logger.addAppender(logs); logger.setLevel(Level.WARN);
        try {
            Handler handler = handler(null); handler.auditFault = true; handler.start();
            handler.writer.listener.accept(SubscriptionCloseReason.IO_ERROR); handler.stop();
            Retry scheduled = handler.retries.pending; assertNotNull(scheduled);
            for (int attempt = 0; attempt < 3; attempt++) handler.retries.fire();
            assertTrue(scheduled.getFuture().isDone()); assertNull(handler.retries.pending);
            assertThrows(ExecutionException.class, () -> scheduled.getFuture().get());
            scheduled.getRunnable().run(); scheduled.getRunnable().run();
            assertEquals(4, handler.terminals.size()); assertTrue(handler.terminals.stream().allMatch(reason -> reason == SubscriptionCloseReason.IO_ERROR));
            assertEquals(1, handler.unsubscribes); assertEquals(1, handler.retries.submissions);
            assertEquals(1L, logs.list.stream().filter(event -> "Subscription terminal audit retries exhausted or unavailable".equals(event.getFormattedMessage())).count());
            for (ILoggingEvent event : logs.list) {
                assertFalse(event.getFormattedMessage().contains("private-storage-fault")); assertNull(event.getThrowableProxy());
            }
        } finally { logger.detachAppender(logs); logger.setLevel(before); logs.stop(); }
    }

    private static Handler handler(SubscriptionAuthorization.Session session) {
        Handler handler = new Handler();
        handler.subscriptionAuthorizations = List.of(new SubscriptionAuthorization() {
            @Override public Session capture(ApiRequest request) { return session; }
            @Override public Lifecycle captureLifecycle(ApiRequest request) { return reason -> {
                handler.terminals.add(reason);
                if (handler.auditFault || handler.auditFailuresRemaining-- > 0) throw new IllegalStateException("private-storage-fault");
            }; }
        });
        return handler;
    }

    private static class Handler extends NonBlockingSubscriptionHandler {
        int writes, unsubscribes, auditFailuresRemaining; boolean processorFault, ioFault, auditFault, realSubscribe, closeDuringRegistration, registrationTimeout; Object usedPolicy;
        EventListener listener; AtomicBoolean disconnected; final Writer writer = new Writer(); final List<SubscriptionCloseReason> terminals = new ArrayList<>();
        final Set<EventListener> registered = new HashSet<>(); final SettableFuture<Object> pendingRegistration = SettableFuture.create();
        final ManualRetries retries = new ManualRetries();
        Handler() {
            super(new SubscriptionSettings() { public long pingIntervalMillis() { return 5; } public int maxPings() { return 2; } });
            retryTimeout = retries;
            eventService = (EventService) Proxy.newProxyInstance(EventService.class.getClassLoader(), new Class<?>[]{EventService.class}, (p, method, args) -> {
                if (method.getName().equals("unsubscribe")) { unsubscribes++; registered.remove(args[0]); }
                if (method.getName().equals("subscribe")) {
                    if (closeDuringRegistration) {
                        writer.listener.accept(SubscriptionCloseReason.CLIENT_CLOSED);
                        registered.add((EventListener) args[1]); // Models register after the handler's earlier close fence.
                        if (registrationTimeout) return new ForwardingListenableFuture.SimpleForwardingListenableFuture<Object>(pendingRegistration) {
                            @Override public Object get(long timeout, TimeUnit unit) throws TimeoutException {
                                throw new TimeoutException("private-registration-timeout");
                            }
                        };
                        return Futures.immediateFuture(null);
                    }
                    registered.add((EventListener) args[1]);
                    return Futures.immediateFailedFuture(new IOException("private-setup-fault"));
                }
                return null;
            });
        }
        boolean start() throws IOException { return subscribe(List.of("resource.change"), new ApiRequest(null, null), true); }
        void stop() { unsubscribe(disconnected, writer, listener); }
        @Override protected MessageWriter getMessageWriter(ApiRequest request) { return writer; }
        @Override protected Future<?> subscribe(Collection<String> names, EventListener listener, MessageWriter writer, AtomicBoolean disconnected, Object lock, boolean strip) {
            this.listener = listener;
            this.disconnected = disconnected;
            return realSubscribe ? super.subscribe(names, listener, writer, disconnected, lock, strip) : CompletableFuture.completedFuture(null);
        }
        @Override protected boolean postProcess(EventVO<Object> event, IdFormatter formatter, ApiRequest request, Object policy) {
            if (processorFault) throw new IllegalStateException("private-processor-fault"); usedPolicy = policy; return true;
        }
        @Override protected void write(Event event, MessageWriter writer, Object lock, boolean strip, EventListener listener, AtomicBoolean disconnected) throws IOException {
            if (ioFault) throw new IOException("private-write-fault"); writes++;
        }
    }

    private static class ManualRetries implements RetryTimeoutService {
        Retry pending; int submissions;
        @Override public Object submit(Retry retry) { submissions++; pending = retry; return retry; }
        @Override public void completed(Object retry) { if (retry == pending) pending = null; }
        void fire() { assertNotNull(pending); pending.getRunnable().run(); }
    }

    private static class Writer implements MessageWriter {
        boolean closed, alreadyClosed; Consumer<SubscriptionCloseReason> listener;
        @Override public void write(String message, Object lock) { }
        @Override public void close() { closed = true; }
        @Override public void close(SubscriptionCloseReason reason) { closed = true; if (listener != null) listener.accept(reason); }
        @Override public void setCloseListener(Consumer<SubscriptionCloseReason> listener) {
            this.listener = listener; if (alreadyClosed) listener.accept(SubscriptionCloseReason.CLIENT_CLOSED);
        }
    }
}
