package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.*;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.engine.process.ExitReason;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.impl.ProcessExecutionExitException;
import io.cattle.platform.engine.process.impl.ResourceStatesDefinition;
import io.cattle.platform.lock.exception.FailedToAcquireLockException;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.process.common.generic.GenericResourceProcessState;
import io.cattle.platform.servicediscovery.api.lock.ServiceDiscoveryServiceSetLinksLock;
import java.lang.reflect.Proxy;
import io.cattle.platform.lock.Lock;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.provider.impl.StandardLock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.Test;

public class ServiceLinksStateMutationGuardTest {
    @Test public void everyMapRemovalStateUsesTheSharedMutexAndRetriesWithoutTerminalDenial() throws Exception {
        var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider());
        var owner = new ServiceDiscoveryApiServiceImpl(); owner.lockManager = locks;
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var holder = worker.submit(() -> ServiceDiscoveryServiceSetLinksLock.withLock(locks, 7, () -> { held.countDown(); await(release); return true; }));
            await(held);
            for (String before : List.of("active", "removing")) {
                var map = new ServiceConsumeMapRecord(); map.setId(70L); map.setServiceId(7L); map.setState(before);
                var writes = new AtomicInteger(); var state = state(map, writes, owner, before.equals("active") ? "removing" : "removed");
                var failure = assertThrows(ProcessExecutionExitException.class, state::setDone);
                assertEquals(ExitReason.STATE_CHANGED, failure.getExitReason());
                assertFalse(failure.getExitReason().isTerminating()); assertFalse(failure.getExitReason().isError());
                assertEquals(before, map.getState()); assertEquals(0, writes.get());
            }
            release.countDown(); assertTrue(holder.get(4, TimeUnit.SECONDS));
            var map = new ServiceConsumeMapRecord(); map.setId(70L); map.setServiceId(7L); map.setState("removing");
            var writes = new AtomicInteger(); assertEquals("removed", state(map, writes, owner, "removed").setDone());
            assertEquals("removed", map.getState()); assertEquals(1, writes.get());
            assertTrue(owner.transition(new Object(), "state", "active", () -> true));
            assertTrue(owner.transition(map, "other", "active", () -> true));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test public void descendingNestedSourcesRetryInsteadOfDeadlockingAnAscendingWriter() throws Exception {
        var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider());
        var high = new CountDownLatch(1); var low = new CountDownLatch(1); var descend = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        Object previous = com.netflix.config.ConfigurationManager.getConfigInstance().getProperty("default.lock.wait.millis");
        com.netflix.config.ConfigurationManager.getConfigInstance().setProperty("default.lock.wait.millis", 10000L);
        try {
            var upper = workers.submit(() -> ServiceDiscoveryServiceSetLinksLock.withLock(locks, 9, () -> {
                high.countDown(); await(low); await(descend);
                assertThrows(FailedToAcquireLockException.class, () -> ServiceDiscoveryServiceSetLinksLock.withLock(locks, 2, () -> true));
                return true;
            })); await(high);
            var lower = workers.submit(() -> ServiceDiscoveryServiceSetLinksLock.withLock(locks, 2, () -> {
                low.countDown(); descend.countDown(); return ServiceDiscoveryServiceSetLinksLock.withLock(locks, 9, () -> true);
            }));
            assertTrue(upper.get(4, TimeUnit.SECONDS)); assertTrue(lower.get(4, TimeUnit.SECONDS));
            assertTrue(workers.submit(() -> ServiceDiscoveryServiceSetLinksLock.withLocks(locks, List.of(9L, 2L), () -> true)).get(4, TimeUnit.SECONDS));
        } finally {
            descend.countDown(); workers.shutdownNow();
            if (previous == null) com.netflix.config.ConfigurationManager.getConfigInstance().clearProperty("default.lock.wait.millis");
            else com.netflix.config.ConfigurationManager.getConfigInstance().setProperty("default.lock.wait.millis", previous);
        }
        bulkThousandSourcesHaveBoundedCallbackDepth(); bulkPartialContentionPreservesPriorOwners();
        bulkAcquisitionFailureReturnsUnacquiredLease(); bulkPartialFailureRetainsTransactionOwners();
        bulkCleanupContinuesAndPreservesPrimary(); bulkReentryRestoresCallbackDepth();
        checkedNativeReleasePreservesLegacyAndReentrantSemantics(); checkedNativeReleaseDoesNotOverwriteTheNextOwner();
        nativeDelegateFailureQuarantinesRetainedAndHandlerLeases();
    }

    private static void bulkThousandSourcesHaveBoundedCallbackDepth() {
        int shallow = ServiceDiscoveryServiceSetLinksLock.withLocks(new LedgerFixture().locks, List.of(1L),
                () -> Thread.currentThread().getStackTrace().length);
        for (int mode = 0; mode < 3; mode++) {
            var f = new LedgerFixture(); var ids = new ArrayList<Long>();
            for (long id = 1000; id > 0; id--) ids.add(id);
            ids.add(1L); var actions = new AtomicInteger();
            boolean transaction = mode == 2, failAction = mode == 1;
            var primary = new IllegalStateException("injected bulk action failure");
            java.util.function.Supplier<Boolean> run = () -> ServiceDiscoveryServiceSetLinksLock.withLocks(f.locks, ids, () -> {
                actions.incrementAndGet();
                assertTrue("Callback depth must not grow with consumer count", Thread.currentThread().getStackTrace().length <= shallow + 8);
                assertEquals(1000, f.physical.size());
                for (var lock : f.physical.values()) assertEquals(transaction ? 2 : 1, lock.getHoldCount());
                if (failAction) throw primary;
                return true;
            });
            if (transaction) transaction().executeWithoutResult(status -> {
                assertTrue(run.get());
                for (var lock : f.physical.values()) assertEquals("Handler return keeps every transaction owner", 1, lock.getHoldCount());
                assertEquals(1000, TransactionSynchronizationManager.getSynchronizations().size());
            });
            else if (failAction) assertSame(primary, assertThrows(IllegalStateException.class, run::get));
            else assertTrue(run.get());
            assertEquals(1, actions.get());
            assertEquals(java.util.stream.LongStream.rangeClosed(1, 1000).boxed()
                    .flatMap(id -> transaction ? java.util.stream.Stream.of(id, id) : java.util.stream.Stream.of(id)).toList(), f.order);
            f.assertBalanced();
        }
    }

    private static void bulkPartialContentionPreservesPriorOwners() throws Exception {
        var f = new LedgerFixture(); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var holder = worker.submit(() -> ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 2, () -> {
                held.countDown(); await(release); return true;
            })); await(held);
            ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 1, () ->
                ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 9, () -> {
                    int highGets = f.gets.get(9L).get(), contendedUnlocks = f.unlocks.get(2L).get();
                    assertThrows(FailedToAcquireLockException.class,
                            () -> ServiceDiscoveryServiceSetLinksLock.withLocks(f.locks, List.of(9L, 2L, 1L), () -> failAction()));
                    assertEquals("Reentrant low owner keeps its original hold", 1, f.physical.get(1L).getHoldCount());
                    assertEquals("Unattempted high owner is never unlocked", 1, f.physical.get(9L).getHoldCount());
                    assertEquals(highGets, f.gets.get(9L).get());
                    assertEquals("Failed try owns no unlock", contendedUnlocks, f.unlocks.get(2L).get());
                    assertEquals(1, f.releases.get(2L).get());
                    return true;
                }));
            release.countDown(); assertTrue(holder.get(4, TimeUnit.SECONDS)); f.assertBalanced();
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    private static void bulkAcquisitionFailureReturnsUnacquiredLease() {
        var f = new LedgerFixture(); f.acquisitionFailure = 2;
        assertSame(f.acquireError, assertThrows(IllegalStateException.class,
                () -> ServiceDiscoveryServiceSetLinksLock.withLocks(f.locks, List.of(3L, 1L, 2L), () -> failAction())));
        assertEquals(List.of(1L, 2L), f.order);
        assertEquals(1, f.unlocks.get(1L).get()); assertEquals(0, f.unlocks.get(2L).get());
        assertEquals(1, f.releases.get(2L).get()); f.assertBalanced();
    }

    private static void bulkPartialFailureRetainsTransactionOwners() {
        for (boolean rollback : List.of(false, true)) {
            var f = new LedgerFixture(); f.tryFailure = 2;
            transaction().executeWithoutResult(status -> {
                ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 9, () -> {
                    assertThrows(FailedToAcquireLockException.class,
                            () -> ServiceDiscoveryServiceSetLinksLock.withLocks(f.locks, List.of(9L, 2L, 1L), () -> failAction()));
                    assertEquals("Partial bulk failure must retain an already registered owner", 1, f.physical.get(1L).getHoldCount());
                    assertEquals(2, f.physical.get(9L).getHoldCount()); return true;
                });
                assertEquals(1, f.physical.get(9L).getHoldCount());
                assertEquals(2, TransactionSynchronizationManager.getSynchronizations().size());
                if (rollback) status.setRollbackOnly();
            });
            f.assertBalanced();
        }
    }

    private static void bulkCleanupContinuesAndPreservesPrimary() {
        for (boolean failAction : List.of(false, true)) {
            var f = new LedgerFixture(); f.unlockFailure = 2; f.releaseFailure = 3;
            var primary = new IllegalArgumentException("injected reviewed handler failure");
            Throwable actual = assertThrows(RuntimeException.class,
                    () -> ServiceDiscoveryServiceSetLinksLock.withLocks(f.locks, List.of(1L, 2L, 3L), () -> {
                        if (failAction) throw primary; return true;
                    }));
            if (failAction) { assertSame(primary, actual); actual = actual.getSuppressed()[0]; }
            assertSame("Reverse scope cleanup keeps its first failure", f.releaseError, actual);
            assertArrayEquals(new Throwable[] {f.unlockError}, actual.getSuppressed());
            assertEquals(1, f.unlocks.get(1L).get()); assertEquals(1, f.unlocks.get(2L).get()); assertEquals(1, f.unlocks.get(3L).get());
            assertEquals("Unknown handler unlock retains its exact lease", f.gets.get(2L).get() - 1, f.releases.get(2L).get());
            assertEquals(f.gets.get(1L).get(), f.releases.get(1L).get());
            assertEquals(f.gets.get(3L).get(), f.releases.get(3L).get());
            f.physical.values().forEach(lock -> assertFalse(lock.isLocked()));
            f.assertScopesClosed(); clearFixtureQuarantines(f);
        }
    }

    private static void bulkReentryRestoresCallbackDepth() {
        var f = new LedgerFixture();
        assertTrue(ServiceDiscoveryServiceSetLinksLock.withLocks(f.locks, List.of(2L, 1L), () -> {
            assertTrue(ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 1, () -> {
                assertTrue(ServiceDiscoveryServiceSetLinksLock.withLocks(f.locks, List.of(1L, 2L), () -> {
                    assertEquals(3, f.physical.get(1L).getHoldCount()); assertEquals(2, f.physical.get(2L).getHoldCount()); return true;
                }));
                assertEquals(2, f.physical.get(1L).getHoldCount()); return true;
            }));
            assertEquals(1, f.physical.get(1L).getHoldCount()); assertEquals(1, f.physical.get(2L).getHoldCount()); return true;
        })); f.assertBalanced();
    }

    private static void checkedNativeReleasePreservesLegacyAndReentrantSemantics() {
        var definition = new ServiceDiscoveryServiceSetLinksLock(7);
        var physical = new ReentrantLock(); var lock = new StandardLock(definition, physical);
        try {
            lock.unlock(); assertTrue(lock.tryLock()); assertTrue(lock.tryLock());
            lock.unlockChecked(); assertEquals(1, physical.getHoldCount());
            lock.unlock(); assertFalse(physical.isLocked());
            assertThrows(IllegalMonitorStateException.class, lock::unlockChecked);
        } finally {
            // Each successful acquisition must also settle if an assertion fails.
            lock.unlock(); lock.unlock();
        }
        var legacyFailure = new IllegalStateException("native delegate fails before release");
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var delegate = new ReentrantLock() {
            @Override public void unlock() { if (fail.get()) throw legacyFailure; super.unlock(); }
        };
        var legacy = new StandardLock(definition, delegate);
        try {
            assertTrue(legacy.tryLock());
            legacy.unlock(); assertTrue("Legacy best-effort release still swallows a delegate failure", delegate.isHeldByCurrentThread());
        } finally {
            fail.set(false); legacy.unlock();
            // The legacy failure intentionally loses wrapper ownership before releasing its delegate.
            if (delegate.isHeldByCurrentThread()) delegate.unlock();
        }
        var returns = new AtomicInteger();
        Lock custom = new Lock() {
            @Override public boolean tryLock() { return true; }
            @Override public void lock() { }
            @Override public void unlock() { returns.incrementAndGet(); }
            @Override public LockDefinition getLockDefinition() { return definition; }
        };
        custom.unlockChecked(); assertEquals("Existing custom provider keeps its declared legacy contract", 1, returns.get());
    }

    private static void checkedNativeReleaseDoesNotOverwriteTheNextOwner() throws Exception {
        var nextPhysical = new CountDownLatch(1); var attempt = new CountDownLatch(1);
        var nextThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var delegate = new ReentrantLock() {
            @Override public boolean tryLock() {
                if (Thread.currentThread() == nextThread.get()) attempt.countDown();
                boolean result = super.tryLock();
                if (result && Thread.currentThread() == nextThread.get()) nextPhysical.countDown(); return result;
            }
            @Override public void unlock() {
                super.unlock();
                if (Thread.currentThread() != nextThread.get()) await(nextPhysical);
            }
        };
        var lock = new StandardLock(new ServiceDiscoveryServiceSetLinksLock(7), delegate);
        assertTrue(lock.tryLock()); var worker = Executors.newSingleThreadExecutor();
        try {
            var next = worker.submit(() -> {
                nextThread.set(Thread.currentThread());
                while (!lock.tryLock()) Thread.onSpinWait();
                assertTrue(delegate.isHeldByCurrentThread()); lock.unlockChecked(); return true;
            });
            await(attempt); lock.unlockChecked(); assertTrue(next.get(4, TimeUnit.SECONDS));
            assertFalse(delegate.isLocked());
        } finally { worker.shutdownNow(); }
    }

    private static void nativeDelegateFailureQuarantinesRetainedAndHandlerLeases() throws Exception {
        for (boolean transactionOwner : List.of(false, true)) {
            var f = new LedgerFixture(); Object resourceKey = null; Object marker = null;
            try {
                if (transactionOwner) {
                    TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
                    ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 2, () -> true);
                    resourceKey = TransactionSynchronizationManager.getResourceMap().keySet().iterator().next();
                    marker = TransactionSynchronizationManager.getResource(resourceKey);
                    f.delegateFailure = 2;
                    var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
                    assertSame(f.nativeError, assertThrows(IllegalStateException.class,
                            () -> callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED)));
                    callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
                    assertSame(marker, TransactionSynchronizationManager.getResource(resourceKey));
                    TransactionSynchronizationManager.clearSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(false);
                } else {
                    f.delegateFailure = 2;
                    assertSame(f.nativeError, assertThrows(IllegalStateException.class,
                            () -> ServiceDiscoveryServiceSetLinksLock.withLocks(f.locks, List.of(1L, 2L, 3L), () -> true)));
                    assertFalse(f.physical.get(1L).isLocked()); assertFalse(f.physical.get(3L).isLocked());
                }
                var retained = f.standard.get(2L);
                assertEquals("Delegate threw before unlock: physical ownership must remain", 1, f.physical.get(2L).getHoldCount());
                assertEquals("Checked failure restores native owner bookkeeping", 1L, nativeOwnerCount(retained));
                assertEquals("Quarantine retains exactly the unreturned lease", 1L, retained.getReference());
                int gets = f.gets.get(2L).get();
                for (java.util.function.Supplier<Boolean> action : List.<java.util.function.Supplier<Boolean>>of(
                        () -> ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 2, () -> failAction()),
                        () -> ServiceDiscoveryServiceSetLinksLock.tryWithLock(f.locks, 2, () -> failAction()),
                        () -> ServiceDiscoveryServiceSetLinksLock.withLocks(f.locks, List.of(2L), () -> failAction()))) {
                    var denied = assertThrows(FailedToAcquireLockException.class, action::get);
                    assertSame("Fail-closed diagnostics retain the native cause", f.nativeError, denied.getCause());
                }
                assertEquals("Same-thread rejected writers acquire no extra lease", gets, f.gets.get(2L).get());
                var worker = Executors.newSingleThreadExecutor();
                try {
                    assertTrue(worker.submit(() -> {
                        assertThrows(FailedToAcquireLockException.class,
                                () -> ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 2, () -> failAction())); return true;
                    }).get(4, TimeUnit.SECONDS));
                } finally { worker.shutdownNow(); }
                var borrowed = f.getLock(new ServiceDiscoveryServiceSetLinksLock(2));
                assertSame("Provider must retain the exact mutex, never create a replacement", retained, borrowed); f.releaseLock(borrowed);
                assertEquals(1L, retained.getReference()); assertEquals(1, f.physical.get(2L).getHoldCount());
                assertEquals(1L, nativeOwnerCount(retained)); f.assertScopesClosed();
            } finally {
                f.delegateFailure = 0; clearFixtureQuarantines(f);
                if (resourceKey != null) TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);
                if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
                // Disposable fixture only: production deliberately exposes no unsafe force-release recovery.
                f.physical.values().forEach(lock -> { while (lock.isHeldByCurrentThread()) lock.unlock(); });
            }
        }
    }

    private static long nativeOwnerCount(StandardLock lock) {
        try { var field = StandardLock.class.getDeclaredField("ownerCount"); field.setAccessible(true); return field.getLong(lock); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void clearFixtureQuarantines(Object provider) {
        try {
            var field = ServiceDiscoveryServiceSetLinksLock.class.getDeclaredField("QUARANTINES"); field.setAccessible(true);
            var local = (ThreadLocal<?>) field.get(null); var markers = (Map<?, ?>) local.get();
            if (markers == null) return;
            markers.keySet().removeIf(key -> {
                try { var accessor = key.getClass().getDeclaredMethod("provider"); accessor.setAccessible(true); return accessor.invoke(key) == provider; }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            if (markers.isEmpty()) local.remove();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static boolean failAction() { throw new AssertionError("A partial acquisition must not enter the native handler"); }

    private static TransactionTemplate transaction() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        });
    }

    private static final class LedgerFixture extends InMemoryLockProvider {
        private final LockManagerImpl locks = new LockManagerImpl();
        private final Map<Long, ReentrantLock> physical = new ConcurrentHashMap<>();
        private final Map<Long, StandardLock> standard = new ConcurrentHashMap<>();
        private final Map<Long, AtomicInteger> gets = new ConcurrentHashMap<>(), unlocks = new ConcurrentHashMap<>(), releases = new ConcurrentHashMap<>();
        private final List<Long> order = java.util.Collections.synchronizedList(new ArrayList<>());
        private long acquisitionFailure, tryFailure, unlockFailure, releaseFailure, delegateFailure;
        private final IllegalStateException nativeError = new IllegalStateException("native delegate fails before physical unlock");
        private final IllegalStateException acquireError = new IllegalStateException("injected acquisition failure");
        private final IllegalStateException unlockError = new IllegalStateException("injected handler unlock failure after release");
        private final IllegalStateException releaseError = new IllegalStateException("injected provider release failure after return");
        private LedgerFixture() { locks.setLockProvider(this); }
        private static long id(LockDefinition definition) { return Long.parseLong(definition.getLockId().substring("SERVICE.SETLINKS.".length())); }
        @Override public Lock getLock(LockDefinition definition) {
            long id = id(definition); gets.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            releases.computeIfAbsent(id, ignored -> new AtomicInteger()); order.add(id); return super.getLock(definition);
        }
        @Override protected StandardLock createLock(LockDefinition definition) {
            long id = id(definition); var delegate = new ReentrantLock() {
                @Override public void unlock() { if (id == delegateFailure) throw nativeError; super.unlock(); }
            }; physical.put(id, delegate);
            unlocks.computeIfAbsent(id, ignored -> new AtomicInteger());
            var result = new StandardLock(definition, delegate) {
                @Override public void lock() { if (id == acquisitionFailure) throw acquireError; super.lock(); }
                @Override public boolean tryLock() { return id != tryFailure && super.tryLock(); }
                @Override public void unlock() { unlocks.get(id).incrementAndGet(); super.unlock(); }
                @Override public void unlockChecked() {
                    unlocks.get(id).incrementAndGet(); super.unlockChecked(); if (id == unlockFailure) throw unlockError;
                }
            }; standard.put(id, result); return result;
        }
        @Override public void releaseLock(Lock lock) {
            long id = id(lock.getLockDefinition()); releases.get(id).incrementAndGet(); super.releaseLock(lock);
            if (id == releaseFailure) throw releaseError;
        }
        private void assertBalanced() {
            gets.forEach((id, count) -> assertEquals("Every obtained provider lease is returned: " + id, count.get(), releases.get(id).get()));
            physical.values().forEach(lock -> assertFalse("No handler/transaction mutex remains held", lock.isLocked()));
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty()); assertScopesClosed();
        }
        private void assertScopesClosed() {
            try {
                var field = ServiceDiscoveryServiceSetLinksLock.class.getDeclaredField("CALLBACKS"); field.setAccessible(true);
                assertNull("Every handler callback depth must be removed", ((ThreadLocal<?>) field.get(null)).get());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }

    private static GenericResourceProcessState state(ServiceConsumeMapRecord map, AtomicInteger writes, ServiceDiscoveryApiServiceImpl owner, String after) {
        var definition = new ResourceStatesDefinition(); definition.setDoneStatesMap(Map.of(map.getState(), after));
        var objects = (ObjectManager) Proxy.newProxyInstance(ServiceLinksStateMutationGuardTest.class.getClassLoader(), new Class<?>[]{ObjectManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("loadResource") || method.getName().equals("reload")) return map;
            if (method.getName().equals("setFields")) { map.setState(((Object[]) args[2])[0].toString()); writes.incrementAndGet(); return map; }
            throw new UnsupportedOperationException(method.getName());
        });
        return new GenericResourceProcessState(null, definition, new LaunchConfiguration("serviceconsumemap.remove", "serviceConsumeMap", "70", 5L, null, Map.of()), objects, List.of(owner));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("map state/source ordering deterministic barrier", latch.await(4, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
