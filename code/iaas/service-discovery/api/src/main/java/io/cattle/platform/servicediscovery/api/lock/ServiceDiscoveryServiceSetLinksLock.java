package io.cattle.platform.servicediscovery.api.lock;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.lock.Lock;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;
import io.cattle.platform.lock.exception.FailedToAcquireLockException;
import io.cattle.platform.lock.provider.LockProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ServiceDiscoveryServiceSetLinksLock extends AbstractBlockingLockDefintion {
    private record TransactionKey(Object provider, long serviceId) { }
    private static final ThreadLocal<Map<TransactionKey, Integer>> CALLBACKS = new ThreadLocal<>();
    private record Quarantine(Lock lock, Throwable failure, boolean releaseAttempted, Quarantine previous) { }
    private static final ThreadLocal<Map<TransactionKey, Quarantine>> QUARANTINES = new ThreadLocal<>();

    public ServiceDiscoveryServiceSetLinksLock(Service service) { this(service.getId()); }
    public ServiceDiscoveryServiceSetLinksLock(long serviceId) { super("SERVICE.SETLINKS." + serviceId); }

    /** Same native mutex: retain through commit; never block acquiring below a held source ID. */
    public static <T> T withLock(LockManager manager, long serviceId, Supplier<T> action) {
        var definition = new ServiceDiscoveryServiceSetLinksLock(serviceId);
        var key = new TransactionKey(manager.getLockProvider(), serviceId);
        rejectQuarantine(key, definition);
        if (!descending(key)) return manager.lock(definition, () -> inside(manager, definition, key, action));
        boolean[] entered = {false};
        T result = manager.tryLock(definition, () -> { entered[0] = true; return inside(manager, definition, key, action); });
        if (!entered[0]) throw new FailedToAcquireLockException(definition);
        return result;
    }

    /** Process-state owners already hold a process lock: contention must retry, never form a reverse wait. */
    public static <T> T tryWithLock(LockManager manager, long serviceId, Supplier<T> action) {
        var definition = new ServiceDiscoveryServiceSetLinksLock(serviceId);
        var key = new TransactionKey(manager.getLockProvider(), serviceId);
        rejectQuarantine(key, definition);
        return manager.tryLock(definition, () -> inside(manager, definition, key, action));
    }

    /** Iterative handler scopes borrow only their own acquisitions; transaction owners retain independently. */
    public static <T> T withLocks(LockManager manager, Collection<Long> serviceIds, Supplier<T> action) {
        var ids = new TreeSet<>(serviceIds);
        List<HandlerLock> scopes = new ArrayList<>(ids.size());
        Throwable failure = null;
        try {
            for (long id : ids) {
                var scope = new HandlerLock(manager, id);
                scopes.add(scope);
                scope.acquire();
            }
            return action.get();
        } catch (RuntimeException | Error error) {
            failure = error; throw error;
        } finally {
            Throwable cleanup = null;
            for (int i = scopes.size() - 1; i >= 0; i--) {
                try { scopes.get(i).release(); }
                catch (RuntimeException | Error error) { cleanup = remember(cleanup, error); }
            }
            if (failure != null) { if (cleanup != null) remember(failure, cleanup); }
            else rethrow(cleanup);
        }
    }

    private static boolean descending(TransactionKey key) {
        boolean result = TransactionSynchronizationManager.getResourceMap().keySet().stream().anyMatch(value -> higher(value, key));
        Map<TransactionKey, Integer> held = CALLBACKS.get();
        return result || held != null && held.keySet().stream().anyMatch(value -> higher(value, key));
    }

    private static Throwable remember(Throwable failure, Throwable cleanup) {
        if (failure == null) return cleanup;
        if (failure != cleanup) failure.addSuppressed(cleanup);
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    /** One provider lease and at most one handler acquisition; never release an unattempted/reentrant owner's hold. */
    private static final class HandlerLock {
        private final LockManager manager;
        private final LockProvider provider;
        private final ServiceDiscoveryServiceSetLinksLock definition;
        private final TransactionKey key;
        private Lock lock;
        private boolean acquired, entered;

        private HandlerLock(LockManager manager, long serviceId) {
            this.manager = manager; this.provider = manager.getLockProvider();
            this.definition = new ServiceDiscoveryServiceSetLinksLock(serviceId);
            this.key = new TransactionKey(provider, serviceId);
        }

        private void acquire() {
            rejectQuarantine(key, definition);
            boolean retry = descending(key);
            lock = provider.getLock(definition);
            if (retry) {
                if (!lock.tryLock()) throw new FailedToAcquireLockException(definition);
            } else lock.lock();
            acquired = true;
            enter(manager, definition, key); entered = true;
        }

        private void release() {
            Throwable failure = null;
            if (entered) {
                try { exit(key); } catch (RuntimeException | Error error) { failure = error; }
            }
            boolean unlocked = !acquired;
            if (acquired) {
                try { lock.unlockChecked(); unlocked = true; }
                catch (RuntimeException | Error error) {
                    quarantine(key, lock, error, false); failure = remember(failure, error);
                }
            }
            if (lock != null && unlocked) {
                try { provider.releaseLock(lock); }
                catch (RuntimeException | Error error) {
                    quarantine(key, lock, error, true); failure = remember(failure, error);
                }
            }
            rethrow(failure);
        }
    }

    /** Unknown releases retain exact leases/diagnostics across transaction cleanup; never retry an ambiguous provider return. */
    private static void quarantine(TransactionKey key, Lock lock, Throwable failure, boolean releaseAttempted) {
        Map<TransactionKey, Quarantine> unknown = QUARANTINES.get();
        if (unknown == null) { unknown = new HashMap<>(); QUARANTINES.set(unknown); }
        unknown.put(key, new Quarantine(lock, failure, releaseAttempted, unknown.get(key)));
    }

    private static void rejectQuarantine(TransactionKey key, ServiceDiscoveryServiceSetLinksLock definition) {
        Map<TransactionKey, Quarantine> unknown = QUARANTINES.get();
        if (unknown != null && unknown.containsKey(key)) {
            var failure = new FailedToAcquireLockException(definition);
            failure.initCause(unknown.get(key).failure()); throw failure;
        }
    }

    private static boolean higher(Object value, TransactionKey key) {
        return value instanceof TransactionKey held && held.provider() == key.provider() && held.serviceId() > key.serviceId();
    }

    private static <T> T inside(LockManager manager, ServiceDiscoveryServiceSetLinksLock definition, TransactionKey key, Supplier<T> action) {
        enter(manager, definition, key);
        try { return action.get(); } finally { exit(key); }
    }

    private static void enter(LockManager manager, ServiceDiscoveryServiceSetLinksLock definition, TransactionKey key) {
        rejectQuarantine(key, definition);
        Map<TransactionKey, Integer> held = CALLBACKS.get();
        if (held == null) { held = new HashMap<>(); CALLBACKS.set(held); }
        held.merge(key, 1, Integer::sum);
        try {
            var provider = manager.getLockProvider();
            Object bound = TransactionSynchronizationManager.getResource(key);
            if (bound != null) {
                if (!(bound instanceof RetainedLock owner) || owner.provider != provider || !owner.key.equals(key)
                        || owner.thread != Thread.currentThread())
                    throw new FailedToAcquireLockException(definition);
                if (owner.closed.get()) {
                    // All writers must recover only this exact owner's confirmed release, even without a transaction.
                    if (!owner.releaseSucceeded || TransactionSynchronizationManager.getResource(key) != owner
                            || TransactionSynchronizationManager.unbindResource(key) != owner)
                        throw new FailedToAcquireLockException(definition);
                    bound = null;
                }
            }
            if (bound == null && TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()
                    && !TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                new RetainedLock(provider, definition, key).retain();
        } catch (RuntimeException | Error failure) {
            exit(key); throw failure;
        }
    }

    private static void exit(TransactionKey key) {
        Map<TransactionKey, Integer> held = CALLBACKS.get();
        int depth = held.get(key) - 1;
        if (depth == 0) held.remove(key); else held.put(key, depth);
        if (held.isEmpty()) CALLBACKS.remove();
    }

    /** One owner transfers its acquired lock to transaction completion; failure and callback share one release claim. */
    private static final class RetainedLock implements TransactionSynchronization {
        private final LockProvider provider;
        private final Lock lock;
        private final TransactionKey key;
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean acquired;
        private final Thread thread = Thread.currentThread();
        private volatile boolean releaseSucceeded;

        private RetainedLock(LockProvider provider, ServiceDiscoveryServiceSetLinksLock definition, TransactionKey key) {
            this.provider = provider; this.lock = provider.getLock(definition); this.key = key;
        }

        private void retain() {
            try {
                lock.lock(); acquired = true;
                TransactionSynchronizationManager.bindResource(key, this);
                TransactionSynchronizationManager.registerSynchronization(this);
            } catch (RuntimeException | Error failure) {
                try { release(); } catch (RuntimeException | Error cleanup) {
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override public void afterCompletion(int status) { release(); }

        private void release() {
            if (thread != Thread.currentThread()) throw new IllegalStateException("Retained native lock requires its owning thread");
            if (!closed.compareAndSet(false, true)) return;
            boolean unlocked = false, released = false;
            try {
                if (acquired) { lock.unlockChecked(); unlocked = true; }
                provider.releaseLock(lock); released = true;
            } catch (RuntimeException | Error failure) {
                quarantine(key, lock, failure, !acquired || unlocked); throw failure;
            } finally {
                releaseSucceeded = acquired && unlocked && released;
                if (releaseSucceeded && TransactionSynchronizationManager.getResource(key) == this)
                    TransactionSynchronizationManager.unbindResource(key);
            }
        }
    }

}
