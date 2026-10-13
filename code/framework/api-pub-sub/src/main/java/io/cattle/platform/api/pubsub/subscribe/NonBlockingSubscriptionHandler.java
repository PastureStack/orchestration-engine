package io.cattle.platform.api.pubsub.subscribe;

import io.cattle.platform.api.pubsub.manager.SubscribeManager;
import io.cattle.platform.async.retry.CancelRetryException;
import io.cattle.platform.async.retry.Retry;
import io.cattle.platform.async.retry.RetryTimeoutService;
import io.cattle.platform.eventing.EventListener;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.framework.event.FrameworkEvents;
import io.cattle.platform.framework.event.Ping;
import io.cattle.platform.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.MoreExecutors;

public abstract class NonBlockingSubscriptionHandler implements SubscriptionHandler {

    private interface LifecycleListener extends EventListener {
        void terminate(SubscriptionCloseReason reason);
    }

    private static final Logger log = LoggerFactory.getLogger(NonBlockingSubscriptionHandler.class);

    private static final SubscriptionSettings DEFAULT_SETTINGS = ArchaiusSubscriptionSettings.create();
    private static final Map<String, Object> LOGOUT_MESSAGE = new HashMap<>();

    /** A bounded retry retains only the provider's tokenless lifecycle trace, not a stream or request. */
    private static final class TerminalDelivery implements Runnable {
        private static final int RETRIES = 3;
        private static final long RETRY_MILLIS = 1000L;
        private final SubscriptionAuthorization.Lifecycle lifecycle;
        private final SubscriptionCloseReason reason;
        private final RetryTimeoutService service;
        private final SettableFuture<Object> completion = SettableFuture.create();
        private final Retry retry = new Retry(RETRIES + 1, RETRY_MILLIS, completion, this);
        private int attempts;

        private TerminalDelivery(SubscriptionAuthorization.Lifecycle lifecycle, SubscriptionCloseReason reason, RetryTimeoutService service) {
            this.lifecycle = lifecycle; this.reason = reason; this.service = service;
            completion.addListener(this::finished, MoreExecutors.directExecutor());
        }

        static void deliver(SubscriptionAuthorization.Lifecycle lifecycle, SubscriptionCloseReason reason, RetryTimeoutService service) {
            try { lifecycle.closed(reason); return; }
            catch (RuntimeException unavailable) { /* Preserve the first cause; do not log exception values. */ }
            if (service == null) {
                log.warn("Subscription terminal audit retries exhausted or unavailable");
                return;
            }
            TerminalDelivery delivery = new TerminalDelivery(lifecycle, reason, service);
            try { service.submit(delivery.retry); }
            catch (RuntimeException unavailable) {
                delivery.completion.setException(new IllegalStateException("Subscription terminal audit retry is unavailable"));
            }
        }

        @Override public synchronized void run() {
            if (completion.isDone()) {
                // RetryTimeoutService queues before calling the runnable. A
                // previously dispatched worker can therefore arrive after completion.
                service.completed(retry);
                return;
            }
            attempts++;
            try {
                lifecycle.closed(reason);
                completion.set(null);
            } catch (RuntimeException unavailable) {
                if (attempts >= RETRIES)
                    completion.setException(new IllegalStateException("Subscription terminal audit retry is unavailable"));
            }
        }

        private void finished() {
            service.completed(retry);
            try { completion.get(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.warn("Subscription terminal audit retries exhausted or unavailable");
            } catch (ExecutionException | java.util.concurrent.CancellationException unavailable) {
                log.warn("Subscription terminal audit retries exhausted or unavailable");
            }
        }
    }

    private final SubscriptionSettings settings;
    
    static {
        LOGOUT_MESSAGE.put("name", "logout");
    }

    protected NonBlockingSubscriptionHandler() {
        this(DEFAULT_SETTINGS);
    }

    NonBlockingSubscriptionHandler(SubscriptionSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Subscription settings are required");
        }
        this.settings = settings;
    }
    @Inject
    JsonMapper jsonMapper;
    @Inject
    EventService eventService;
    @Inject
    RetryTimeoutService retryTimeout;
    ExecutorService executorService;
    List<ApiPubSubEventPostProcessor> eventProcessors;
    @Inject List<SubscriptionAuthorization> subscriptionAuthorizations;

    @Override
    public boolean subscribe(Collection<String> eventNames, final ApiRequest apiRequest, final boolean strip) throws IOException {
        ApiContext apiContext = ApiContext.getContext();

        final java.util.ArrayList<SubscriptionAuthorization.Session> authorizations = new java.util.ArrayList<>();
        final java.util.ArrayList<SubscriptionAuthorization.Lifecycle> lifecycles = new java.util.ArrayList<>();
        if (subscriptionAuthorizations != null) {
            for (SubscriptionAuthorization authorization : subscriptionAuthorizations) {
                SubscriptionAuthorization.Lifecycle lifecycle = authorization.captureLifecycle(apiRequest);
                if (lifecycle != null) lifecycles.add(lifecycle);
                SubscriptionAuthorization.Session session = authorization.capture(apiRequest);
                if (session != null) authorizations.add(session);
            }
        }

        final Object writeLock = new Object();
        final MessageWriter writer = getMessageWriter(apiRequest);
        final AtomicBoolean disconnect = new AtomicBoolean(false);

        final IdFormatter idFormatter = apiContext.getIdFormatter();
        final Object policy = apiContext.getPolicy();

        if (writer == null) {
            return false;
        }

        LifecycleListener listener = new LifecycleListener() {
            @Override
            public void terminate(SubscriptionCloseReason reason) {
                if (!disconnect.compareAndSet(false, true)) return;
                try {
                    writer.close(reason);
                } catch (RuntimeException unavailable) {
                    log.warn("Subscription writer close failed");
                } finally {
                    try {
                        eventService.unsubscribe(this);
                    } finally {
                        SubscriptionCloseReason selected = writer.getCloseReason();
                        SubscriptionCloseReason actualReason = selected == null ? reason : selected;
                        for (SubscriptionAuthorization.Lifecycle lifecycle : lifecycles) {
                            TerminalDelivery.deliver(lifecycle, actualReason, retryTimeout);
                        }
                    }
                }
            }

            @Override
            public void onEvent(Event event) {
                if (disconnect.get()) return;
                try {
                    EventVO<Object> modified = new EventVO<Object>(event);

                    ApiRequest request = new ApiRequest(apiRequest);
                    Object currentPolicy = policy;
                    try {
                        for (SubscriptionAuthorization.Session session : authorizations) {
                            currentPolicy = session.currentPolicy(modified);
                            if (currentPolicy == null) return;
                        }
                    } catch (RuntimeException failed) {
                        terminate(SubscriptionCloseReason.AUTHORIZATION_FAILED);
                        return;
                    }
                    if (!postProcess(modified, idFormatter, request, currentPolicy)) {
                        return;
                    }
                    obfuscateIds(modified, idFormatter);

                    write(modified, writer, writeLock, strip, this, disconnect);
                } catch (IOException e) {
                    log.trace("IOException on subscription write, disconnecting");
                    terminate(SubscriptionCloseReason.IO_ERROR);
                } catch (RuntimeException failed) {
                    terminate(SubscriptionCloseReason.INTERNAL_ERROR);
                }
            }
        };
        writer.setCloseListener(listener::terminate);
        if (disconnect.get()) return false;
        return subscribe(eventNames, listener, writer, disconnect, writeLock, strip) != null;
    }

    protected boolean postProcess(EventVO<Object> event, IdFormatter idFormatter, ApiRequest request, Object policy) {
        try {
            ApiContext context = ApiContext.newContext();
            context.setApiRequest(request);
            context.setIdFormatter(idFormatter);
            context.setPolicy(policy);

            for (ApiPubSubEventPostProcessor processor : eventProcessors) {
                if (!processor.processEvent(event)) {
                    return false;
                }
            }
        } finally {
            ApiContext.remove();
        }

        return true;
    }

    protected void obfuscateIds(EventVO<?> event, IdFormatter idFormatter) {
        if (event.getResourceType() == null) {
            event.setResourceId(null);
        } else {
            Object id = idFormatter.formatId(event.getResourceType(), event.getResourceId());
            event.setResourceId(id == null ? null : id.toString());
        }
    }

    protected abstract MessageWriter getMessageWriter(ApiRequest apiRequest) throws IOException;

    protected void write(Event event, MessageWriter writer, Object writeLock, boolean strip, EventListener listener, final AtomicBoolean disconnect)
            throws IOException {
        EventVO<Object> newEvent = new EventVO<Object>(event);
        if (strip) {
            String name = newEvent.getName();
            if (name != null) {
                newEvent.setName(StringUtils.substringBefore(name, FrameworkEvents.EVENT_SEP));
            }
        }

        if (SubscribeManager.EVENT_DISCONNECT.equals(newEvent.getName())) {
            write(writer, jsonMapper.writeValueAsString(LOGOUT_MESSAGE), listener);
            unsubscribe(disconnect, writer, listener);
            return;
        }

        String content = jsonMapper.writeValueAsString(newEvent);
        write(writer, content, writeLock);
    }

    protected void write(MessageWriter writer, String content, Object writeLock) throws IOException {
        writer.write(content, writeLock);
    }

    protected Future<?> subscribe(Collection<String> eventNames, EventListener listener, MessageWriter writer, AtomicBoolean disconnect, Object writeLock,
            boolean strip) {
        boolean unsubscribe = false;
        try {
            for (String eventName : eventNames) {
                if (disconnect.get()) return null;
                try {
                    // AbstractEventService registers synchronously before returning
                    // its future. A close can precede that registration after the
                    // check above; cleanup must also run if future.get times out.
                    eventService.subscribe(eventName, listener).get(settings.pingIntervalMillis(), TimeUnit.MILLISECONDS);
                } finally {
                    if (disconnect.get()) eventService.unsubscribe(listener);
                }
                if (disconnect.get()) return null;
            }
            write(new Ping(), writer, writeLock, strip, listener, disconnect);
            return schedulePing(listener, writer, disconnect);
        } catch (Throwable e) {
            unsubscribe = true;
        } finally {
            if (unsubscribe) {
                unsubscribe(disconnect, writer, listener, SubscriptionCloseReason.SUBSCRIBE_FAILED);
            }
        }

        return null;
    }

    protected Future<?> schedulePing(final EventListener listener, final MessageWriter writer, final AtomicBoolean disconnect) {
        final SettableFuture<?> future = SettableFuture.create();
        retryTimeout.submit(new Retry(settings.maxPings(), settings.pingIntervalMillis(), future, new Runnable() {
            @Override
            public void run() {
                if (disconnect.get()) {
                    unsubscribe(disconnect, writer, listener);
                    future.setException(new CancelRetryException());
                    throw new CancelRetryException();
                }
                listener.onEvent(new Ping());
            }
        }));

        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    unsubscribe(disconnect, writer, listener);
                } catch (ExecutionException e) {
                    unsubscribe(disconnect, writer, listener);
                }
            }
        }, executorService);

        return future;
    }

    protected void unsubscribe(AtomicBoolean disconnect, MessageWriter writer, EventListener listener) {
        unsubscribe(disconnect, writer, listener, SubscriptionCloseReason.SERVER_CLOSED);
    }

    protected void unsubscribe(AtomicBoolean disconnect, MessageWriter writer, EventListener listener, SubscriptionCloseReason reason) {
        if (listener instanceof LifecycleListener lifecycle) {
            lifecycle.terminate(reason);
            return;
        }
        disconnect.set(true);
        writer.close(reason);
        eventService.unsubscribe(listener);
    }

    public List<ApiPubSubEventPostProcessor> getEventProcessors() {
        return eventProcessors;
    }

    @Inject
    public void setEventProcessors(List<ApiPubSubEventPostProcessor> eventProcessors) {
        this.eventProcessors = eventProcessors;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

}
