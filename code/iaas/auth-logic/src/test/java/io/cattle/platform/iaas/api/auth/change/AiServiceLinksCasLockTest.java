package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.addon.ServiceLink;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.Lock;
import io.cattle.platform.lock.exception.FailedToAcquireLockException;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.cattle.platform.lock.provider.impl.StandardLock;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.servicediscovery.api.action.SetServiceLinksActionHandler;
import io.cattle.platform.servicediscovery.api.lock.ServiceDiscoveryServiceSetLinksLock;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.cattle.platform.servicediscovery.api.dao.ServiceConsumeMapDao;
import io.cattle.platform.servicediscovery.api.service.ServiceDiscoveryApiService;
import io.cattle.platform.servicediscovery.api.service.impl.ServiceDiscoveryApiServiceImpl;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AiServiceLinksCasLockTest {
    private Object priorWait;
    @Before public void waitForNativeLock() {
        var config = com.netflix.config.ConfigurationManager.getConfigInstance();
        priorWait = config.getProperty("default.lock.wait.millis"); config.setProperty("default.lock.wait.millis", 10000L);
    }
    @After public void cleanup() {
        ApiContext.remove(); var config = com.netflix.config.ConfigurationManager.getConfigInstance();
        if (priorWait == null) config.clearProperty("default.lock.wait.millis"); else config.setProperty("default.lock.wait.millis", priorWait);
    }

    @Test public void nativeClearOrReplaceWinningTheSharedMutexMakesTypedApplyStaleBeforeClaim() throws Exception {
        for (List<Long> desired : List.of(List.<Long>of(), List.of(11L))) {
            Fixture f = new Fixture(); f.pauseNative = true; f.preview();
            var workers = Executors.newFixedThreadPool(2);
            try {
                var nativeWrite = workers.submit(() -> { f.nativeThread.set(Thread.currentThread()); f.nativeSet(desired); return true; });
                await(f.nativeInside);
                f.measureRows.set(true);
                var typed = workers.submit(() -> { f.typedThread.set(Thread.currentThread()); return f.apply(); });
                await(f.typedNativeAttempt); f.releaseNative.countDown();
                assertTrue(nativeWrite.get(4, TimeUnit.SECONDS));
                assertEquals("ResourceChangePlanStale", typed.get(4, TimeUnit.SECONDS));
                assertEquals(new LinkedHashSet<>(desired), f.links.get());
                assertEquals(0, f.base.claims); assertEquals(0, f.base.dispatched);
                assertEquals("Service row must follow the shared native mutex", 0, f.rowsBeforeNativeMutation.get());
            } finally { f.releaseNative.countDown(); workers.shutdownNow(); }
        }
    }

    @Test public void typedCasHoldsTheOriginalNativeMutexAndTheNormalHandlerReentersIt() throws Exception {
        Fixture f = new Fixture(); f.preview();
        CountDownLatch checked = new CountDownLatch(1), release = new CountDownLatch(1);
        when(f.base.domain.prepare(any(), any(), anyString(), anyMap(), anyMap())).thenAnswer(call -> {
            checked.countDown(); await(release); return f.base.prepare(call.getArgument(3));
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var typed = workers.submit(() -> { f.typedThread.set(Thread.currentThread()); return f.apply(); });
            await(checked);
            var nativeWrite = workers.submit(() -> { f.nativeThread.set(Thread.currentThread()); f.nativeSet(List.of(11L)); return true; });
            await(f.nativeAttempt);
            assertTrue("Native writer must observe typed owner before entering its real handler", f.nativeObservedTypedOwner.get());
            assertEquals(Set.of(9L), f.links.get());
            release.countDown();
            assertEquals("accepted", typed.get(4, TimeUnit.SECONDS)); assertTrue(nativeWrite.get(4, TimeUnit.SECONDS));
            assertEquals(Set.of(11L), f.links.get()); assertEquals(1, f.base.claims); assertEquals(1, f.base.dispatched);
            assertEquals("Same native key: outer CAS lease plus ordinary handler reentry", 2, f.typedNativeAttempts.get());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test public void singularNativeAddOrRemoveWinningTheSameMutexAlsoMakesTypedApplyStale() throws Exception {
        for (boolean add : List.of(true, false)) {
            Fixture f = new Fixture(); f.preview(); f.pauseNative = true;
            var owner = f.nativeApiOwner(); var workers = Executors.newFixedThreadPool(2);
            try {
                var writer = workers.submit(() -> { f.nativeThread.set(Thread.currentThread());
                    if (add) owner.addServiceLink(f.base.target, new ServiceLink(11L, null, null));
                    else owner.removeServiceLink(f.base.target, new ServiceLink(9L, null, null)); return true; });
                await(f.nativeInside);
                var typed = workers.submit(() -> { f.typedThread.set(Thread.currentThread()); return f.apply(); });
                await(f.typedNativeAttempt); f.releaseNative.countDown();
                assertTrue(writer.get(4, TimeUnit.SECONDS)); assertEquals("ResourceChangePlanStale", typed.get(4, TimeUnit.SECONDS));
                assertEquals(add ? Set.of(9L, 11L) : Set.of(), f.links.get());
                assertEquals(0, f.base.claims); assertEquals(0, f.base.dispatched);
            } finally { f.releaseNative.countDown(); workers.shutdownNow(); }
        }
    }

    @Test public void singularNativeOwnersCannotMutateBetweenTypedFreshCasAndDispatch() throws Exception {
        for (boolean add : List.of(true, false)) {
            Fixture f = new Fixture(); f.preview(); var owner = f.nativeApiOwner();
            CountDownLatch checked = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.base.domain.prepare(any(), any(), anyString(), anyMap(), anyMap())).thenAnswer(call -> {
                checked.countDown(); await(release); return f.base.prepare(call.getArgument(3));
            });
            var workers = Executors.newFixedThreadPool(2);
            try {
                var typed = workers.submit(() -> { f.typedThread.set(Thread.currentThread()); return f.apply(); }); await(checked);
                var writer = workers.submit(() -> { f.nativeThread.set(Thread.currentThread());
                    if (add) owner.addServiceLink(f.base.target, new ServiceLink(11L, null, null));
                    else owner.removeServiceLink(f.base.target, new ServiceLink(9L, null, null)); return true; });
                await(f.nativeAttempt); assertTrue("Singular owner must wait on the same typed native mutex", f.nativeObservedTypedOwner.get());
                assertEquals(Set.of(9L), f.links.get()); release.countDown();
                assertEquals("accepted", typed.get(4, TimeUnit.SECONDS)); assertTrue(writer.get(4, TimeUnit.SECONDS));
                assertEquals(add ? Set.of(10L, 11L) : Set.of(10L), f.links.get());
            } finally { release.countDown(); workers.shutdownNow(); }
        }
    }

    @Test public void sharedNativeMutexCoversTheRealSpringCommitAndRollbackTailWithoutLeaking() throws Exception {
        for (boolean rollback : List.of(false, true)) {
            Fixture f = new Fixture(); var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:links-commit-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            var sql = new JdbcTemplate(source); sql.execute("create table links_tail (id int primary key, target_id int)");
            sql.update("insert into links_tail values (7, 9)");
            var transactions = new DataSourceTransactionManager(source); var tx = new TransactionTemplate(transactions);
            CountDownLatch returnedFromHandler = new CountDownLatch(1), finish = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> {
                    f.typedThread.set(Thread.currentThread());
                    tx.executeWithoutResult(status -> {
                        ServiceDiscoveryServiceSetLinksLock.withLock(f.base.service.locks, 7, () -> { sql.update("update links_tail set target_id=11 where id=7"); return null; });
                        returnedFromHandler.countDown(); await(finish); if (rollback) status.setRollbackOnly();
                    }); return true;
                });
                await(returnedFromHandler);
                var next = workers.submit(() -> {
                    f.nativeThread.set(Thread.currentThread());
                    return tx.execute(status -> ServiceDiscoveryServiceSetLinksLock.withLock(f.base.service.locks, 7,
                            () -> sql.queryForObject("select target_id from links_tail where id=7", Integer.class)));
                });
                await(f.nativeAttempt);
                assertTrue("The normal handler returned but its write has not committed", f.nativeObservedTypedOwner.get());
                assertEquals(Integer.valueOf(9), sql.queryForObject("select target_id from links_tail where id=7", Integer.class));
                finish.countDown(); assertTrue(first.get(4, TimeUnit.SECONDS));
                assertEquals(Integer.valueOf(rollback ? 9 : 11), next.get(4, TimeUnit.SECONDS));
                assertEquals("Commit and rollback both release the original native key", Integer.valueOf(3),
                        workers.submit(() -> ServiceDiscoveryServiceSetLinksLock.withLock(f.base.service.locks, 7, () -> 3)).get(4, TimeUnit.SECONDS));
            } finally { finish.countDown(); workers.shutdownNow(); }
        }
        retainedCleanupFailure(false, false, TransactionSynchronization.STATUS_COMMITTED);
        retainedCleanupFailure(false, true, TransactionSynchronization.STATUS_ROLLED_BACK);
        retainedCleanupFailure(true, false, TransactionSynchronization.STATUS_ROLLED_BACK);
        retainedAcquisitionFailureDoesNotUnlockAnUnacquiredReference();
        successfulReleaseCanRecoverWithoutTransaction();
        uncertainReleaseRemainsBound(true); uncertainReleaseRemainsBound(false);
    }


    private static void retainedCleanupFailure(boolean registrationFailure, boolean getResourceFailure, int completion) {
        var f = new RetentionFixture(); Object key = null;
        Throwable cleanup = getResourceFailure ? new AssertionError("injected resource lookup failure")
                : new IllegalStateException("injected resource unbind failure");
        var original = new IllegalStateException("injected registration failure after registration");
        var callback = new AtomicReference<TransactionSynchronization>();
        var actions = new AtomicInteger();
        beginRetentionTransaction();
        try {
            if (registrationFailure) {
                try (var ignored = mockStatic(TransactionSynchronizationManager.class, invocation -> {
                    if (invocation.getMethod().getName().equals("registerSynchronization")) {
                        invocation.callRealMethod(); callback.set(invocation.getArgument(0)); throw original;
                    }
                    if (invocation.getMethod().getName().equals("unbindResource")) throw cleanup;
                    return invocation.callRealMethod();
                })) {
                    Throwable actual = assertThrows(IllegalStateException.class,
                            () -> ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, actions::incrementAndGet));
                    assertSame("Keep the registration failure, with cleanup failure suppressed", original, actual);
                    assertArrayEquals(new Throwable[] {cleanup}, actual.getSuppressed());
                    assertEquals(0, actions.get());
                    assertEquals(2, f.unlocks.get()); assertEquals(2, f.releases.get());
                    callback.get().afterCompletion(completion); callback.get().afterCompletion(completion);
                    assertEquals(2, f.unlocks.get()); assertEquals(2, f.releases.get());
                }
            } else {
                assertEquals(Integer.valueOf(1), ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, actions::incrementAndGet));
                assertEquals(Integer.valueOf(2), ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, actions::incrementAndGet));
                assertEquals("Nested calls borrow one still-open exact owner", 1,
                        TransactionSynchronizationManager.getSynchronizations().size());
                callback.set(TransactionSynchronizationManager.getSynchronizations().getFirst());
                assertTrue("Handler return must retain the mutex until completion", f.physical.get().isHeldByCurrentThread());
                int unlocks = f.unlocks.get(), releases = f.releases.get();
                try (var ignored = mockStatic(TransactionSynchronizationManager.class, invocation -> {
                    String method = invocation.getMethod().getName();
                    if (method.equals(getResourceFailure ? "getResource" : "unbindResource")) throw cleanup;
                    return invocation.callRealMethod();
                })) {
                    assertSame(cleanup, assertThrows(Throwable.class, () -> callback.get().afterCompletion(completion)));
                    assertFalse("Resource cleanup failure must not leave the retained mutex locked", f.physical.get().isLocked());
                    assertEquals(unlocks + 1, f.unlocks.get()); assertEquals(releases + 1, f.releases.get());
                    callback.get().afterCompletion(completion); callback.get().afterCompletion(completion);
                    assertEquals("Repeated completion owns no second release", unlocks + 1, f.unlocks.get());
                    assertEquals(releases + 1, f.releases.get());
                }
            }
            assertFalse(f.physical.get().isLocked());
            assertEquals(1, TransactionSynchronizationManager.getResourceMap().size());
            key = TransactionSynchronizationManager.getResourceMap().keySet().iterator().next();
            Object closedMarker = TransactionSynchronizationManager.getResource(key);
            // Simulate the next transaction on the same thread without erasing the failed cleanup's exact marker.
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            Object exactKey = key, unrelatedKey = new Object(), unrelatedValue = new Object();
            TransactionSynchronizationManager.bindResource(unrelatedKey, unrelatedValue);
            try {
                var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:recovered-links-" + java.util.UUID.randomUUID());
                var tx = new TransactionTemplate(new DataSourceTransactionManager(source)); int before = actions.get();
                tx.executeWithoutResult(status -> {
                    assertEquals(Integer.valueOf(before + 1),
                            ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, actions::incrementAndGet));
                    Object freshOwner = TransactionSynchronizationManager.getResource(exactKey);
                    assertNotSame("Confirmed release is removed before a fresh retain", closedMarker, freshOwner);
                    assertTrue("Recovery still retains the mutex after handler return, until real JDBC commit",
                            f.physical.get().isHeldByCurrentThread());
                    int unlocks = f.unlocks.get(), releases = f.releases.get();
                    callback.get().afterCompletion(completion);
                    assertEquals("A late old callback cannot release the fresh owner", unlocks, f.unlocks.get());
                    assertEquals(releases, f.releases.get());
                    assertSame(freshOwner, TransactionSynchronizationManager.getResource(exactKey));
                    assertSame(unrelatedValue, TransactionSynchronizationManager.getResource(unrelatedKey));
                });
                assertFalse(f.physical.get().isLocked()); assertFalse(TransactionSynchronizationManager.hasResource(exactKey));
                assertSame("Only the exact stale marker was removed", unrelatedValue,
                        TransactionSynchronizationManager.getResource(unrelatedKey));
            } finally { TransactionSynchronizationManager.unbindResourceIfPossible(unrelatedKey); }
        } finally {
            if (key == null && !TransactionSynchronizationManager.getResourceMap().isEmpty())
                key = TransactionSynchronizationManager.getResourceMap().keySet().iterator().next();
            if (key != null) TransactionSynchronizationManager.unbindResourceIfPossible(key);
            endRetentionTransaction();
            // RED fixture teardown must not strand the deliberately reproduced old lock leak.
            if (f.physical.get() != null) while (f.physical.get().isHeldByCurrentThread()) f.physical.get().unlock();
        }
    }

    private static void successfulReleaseCanRecoverWithoutTransaction() {
        var f = new RetentionFixture(); Object key = null, unrelatedKey = new Object(), unrelatedValue = new Object();
        beginRetentionTransaction();
        try {
            ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, () -> 1);
            key = TransactionSynchronizationManager.getResourceMap().keySet().iterator().next();
            var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
            Object exactKey = key;
            try (var ignored = mockStatic(TransactionSynchronizationManager.class, invocation -> {
                if (invocation.getMethod().getName().equals("unbindResource"))
                    throw new IllegalStateException("one cleanup fault");
                return invocation.callRealMethod();
            })) {
                assertThrows(IllegalStateException.class, () -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            }
            assertFalse(f.physical.get().isLocked()); assertTrue(TransactionSynchronizationManager.hasResource(key));
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.bindResource(unrelatedKey, unrelatedValue);
            assertEquals(Integer.valueOf(2), ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, () -> {
                assertFalse(TransactionSynchronizationManager.hasResource(exactKey));
                assertTrue("A nontransaction writer still owns the native mutex", f.physical.get().isHeldByCurrentThread());
                callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
                assertTrue(f.physical.get().isHeldByCurrentThread());
                assertSame(unrelatedValue, TransactionSynchronizationManager.getResource(unrelatedKey)); return 2;
            }));
            assertFalse(f.physical.get().isLocked()); assertFalse(TransactionSynchronizationManager.hasResource(key));
            assertSame(unrelatedValue, TransactionSynchronizationManager.getResource(unrelatedKey));
        } finally {
            if (key != null) TransactionSynchronizationManager.unbindResourceIfPossible(key);
            TransactionSynchronizationManager.unbindResourceIfPossible(unrelatedKey); endRetentionTransaction();
            if (f.physical.get() != null) while (f.physical.get().isHeldByCurrentThread()) f.physical.get().unlock();
        }
    }

    private static void uncertainReleaseRemainsBound(boolean unlockFailure) {
        var f = new RetentionFixture(); var actions = new AtomicInteger(); Object key = null;
        beginRetentionTransaction();
        try {
            ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, actions::incrementAndGet);
            key = TransactionSynchronizationManager.getResourceMap().keySet().iterator().next();
            Object marker = TransactionSynchronizationManager.getResource(key);
            f.failUnlock.set(unlockFailure); f.failRelease.set(!unlockFailure);
            var callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
            assertThrows(IllegalStateException.class, () -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            f.failUnlock.set(false); f.failRelease.set(false);
            assertSame("Unknown unlock or provider release keeps its own marker bound", marker,
                    TransactionSynchronizationManager.getResource(key));
            int unlocks = f.unlocks.get(), releases = f.releases.get();
            callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertEquals(unlocks, f.unlocks.get()); assertEquals(releases, f.releases.get());
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            int before = actions.get();
            assertThrows("Unknown markers also block nontransaction native writers", FailedToAcquireLockException.class,
                    () -> ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, actions::incrementAndGet));
            assertEquals(before, actions.get()); assertSame(marker, TransactionSynchronizationManager.getResource(key));
            var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:uncertain-links-" + java.util.UUID.randomUUID());
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            assertThrows(FailedToAcquireLockException.class, () -> tx.execute(status ->
                    ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, actions::incrementAndGet)));
            assertEquals("Unknown release must never authorize the next action", before, actions.get());
            assertSame(marker, TransactionSynchronizationManager.getResource(key)); assertFalse(f.physical.get().isLocked());
        } finally {
            f.failUnlock.set(false); f.failRelease.set(false); clearFixtureQuarantine(f.locks.getLockProvider());
            if (key != null) TransactionSynchronizationManager.unbindResourceIfPossible(key);
            endRetentionTransaction();
            if (f.physical.get() != null) while (f.physical.get().isHeldByCurrentThread()) f.physical.get().unlock();
        }
    }

    private static void clearFixtureQuarantine(Object provider) {
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

    private static void retainedAcquisitionFailureDoesNotUnlockAnUnacquiredReference() {
        var f = new RetentionFixture(); f.failRetainedAcquisition.set(true); var actions = new AtomicInteger();
        beginRetentionTransaction();
        try {
            assertThrows(FailedToAcquireLockException.class,
                    () -> ServiceDiscoveryServiceSetLinksLock.withLock(f.locks, 7, actions::incrementAndGet));
            assertEquals(0, actions.get()); assertEquals(1, f.unlocks.get()); assertEquals(2, f.releases.get());
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty()); assertFalse(f.physical.get().isLocked());
        } finally { endRetentionTransaction(); }
    }

    private static void beginRetentionTransaction() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private static void endRetentionTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private static class RetentionFixture {
        final AtomicInteger unlocks = new AtomicInteger(), releases = new AtomicInteger();
        final AtomicReference<ReentrantLock> physical = new AtomicReference<>();
        final AtomicBoolean failRetainedAcquisition = new AtomicBoolean();
        final AtomicBoolean failUnlock = new AtomicBoolean(), failRelease = new AtomicBoolean();
        final LockManagerImpl locks = new LockManagerImpl();
        RetentionFixture() {
            locks.setLockProvider(new InMemoryLockProvider() {
                @Override protected StandardLock createLock(LockDefinition definition) {
                    var nativeLock = new ReentrantLock(); physical.set(nativeLock);
                    return new StandardLock(definition, nativeLock) {
                        @Override public void lock() {
                            if (failRetainedAcquisition.get() && nativeLock.isHeldByCurrentThread())
                                throw new FailedToAcquireLockException(definition);
                            super.lock();
                        }
                        @Override public void unlock() {
                            unlocks.incrementAndGet(); super.unlock();
                        }
                        @Override public void unlockChecked() {
                            unlocks.incrementAndGet(); super.unlockChecked();
                            if (failUnlock.get()) throw new IllegalStateException("Unknown unlock result");
                        }
                    };
                }
                @Override public void releaseLock(Lock lock) {
                    releases.incrementAndGet(); super.releaseLock(lock);
                    if (failRelease.get()) throw new IllegalStateException("Unknown provider release result");
                }
            });
        }
    }

    private static class Fixture {
        final AiResourceChangePlanServiceTest.Fixture base = new AiResourceChangePlanServiceTest.Fixture();
        final AtomicReference<Set<Long>> links = new AtomicReference<>(Set.of(9L));
        final AtomicReference<Thread> typedThread = new AtomicReference<>(), nativeThread = new AtomicReference<>();
        final AtomicBoolean nativeMutated = new AtomicBoolean(), measureRows = new AtomicBoolean(), nativeObservedTypedOwner = new AtomicBoolean();
        final AtomicInteger rowsBeforeNativeMutation = new AtomicInteger(), typedNativeAttempts = new AtomicInteger();
        final CountDownLatch nativeInside = new CountDownLatch(1), releaseNative = new CountDownLatch(1),
                typedNativeAttempt = new CountDownLatch(1), nativeAttempt = new CountDownLatch(1);
        final SetServiceLinksActionHandler nativeHandler = new SetServiceLinksActionHandler();
        boolean pauseNative;
        Fixture() throws Exception {
            var locks = new LockManagerImpl();
            locks.setLockProvider(new InMemoryLockProvider() {
                @Override protected StandardLock createLock(LockDefinition definition) {
                    return new StandardLock(definition, new ReentrantLock() {
                        @Override public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
                            if (definition.getLockId().equals("SERVICE.SETLINKS.7")) {
                                if (Thread.currentThread() == typedThread.get()) {
                                    typedNativeAttempts.incrementAndGet(); typedNativeAttempt.countDown();
                                }
                                if (Thread.currentThread() == nativeThread.get()) {
                                    nativeObservedTypedOwner.set(getOwner() == typedThread.get()); nativeAttempt.countDown();
                                }
                            }
                            return super.tryLock(timeout, unit);
                        }
                    });
                }
            });
            base.service.locks = locks;
            inject(nativeHandler, "lockManager", locks); inject(nativeHandler, "jsonMapper", new JacksonJsonMapper());
            var objects = mock(ObjectManager.class); inject(nativeHandler, "objMgr", objects);
            for (long id : List.of(9L, 10L, 11L)) {
                var target = new io.cattle.platform.core.model.tables.records.ServiceRecord(); target.setId(id); target.setState("active");
                when(objects.loadResource(Service.class, id)).thenReturn(target);
            }
            var maps = mock(ServiceConsumeMapDao.class); inject(nativeHandler, "consumeMapDao", maps);
            when(maps.findConsumedMapsForMutation(7L)).thenAnswer(call -> {
                if (pauseNative && Thread.currentThread() == nativeThread.get()) { nativeInside.countDown(); await(releaseNative); }
                List<ServiceConsumeMap> rows = new ArrayList<>();
                for (long id : links.get()) { var map = new ServiceConsumeMapRecord(); map.setServiceId(7L); map.setConsumedServiceId(id); rows.add(map); }
                return rows;
            });
            var service = mock(ServiceDiscoveryApiService.class); inject(nativeHandler, "sdService", service);
            doAnswer(call -> { mutate(((ServiceLink) call.getArgument(1)).getServiceId(), false); return null; })
                    .when(service).removeServiceLink(eq(base.target), any());
            doAnswer(call -> { mutate(((ServiceLink) call.getArgument(1)).getServiceId(), true); return null; })
                    .when(service).addServiceLink(eq(base.target), any());
            when(base.domain.serviceLinksSnapshot(any())).thenAnswer(call -> base.copy(Map.of("links", links.get().stream().sorted().toList())));
            when(base.domain.aliasReferenceSnapshot(any(), any(), anyString(), anyMap())).thenReturn(Map.of("before", List.of(), "after", List.of()));
            when(base.domain.lock(eq("service"), eq(7L))).thenAnswer(call -> {
                if (measureRows.get() && !nativeMutated.get()) rowsBeforeNativeMutation.incrementAndGet(); return base.target;
            });
            when(base.domain.dispatch(any(), any(), anyString(), anyMap(), anyString())).thenAnswer(call -> {
                base.dispatched++;
                List<String> ids = (List<String>) ((Map<?, ?>) call.getArgument(3)).get("serviceIds");
                nativeSet(ids.stream().map(id -> Long.valueOf(id.substring(2))).toList()); return base.target;
            });
            base.dispatched = 0; // Replacing the fixture's existing Mockito answer invokes it once during stubbing.
        }
        ServiceDiscoveryApiServiceImpl nativeApiOwner() throws Exception {
            var owner = new ServiceDiscoveryApiServiceImpl(); var maps = mock(ServiceConsumeMapDao.class);
            inject(owner, "lockManager", base.service.locks); inject(owner, "consumeMapDao", maps);
            when(maps.createServiceLink(eq(base.target), any())).thenAnswer(call -> {
                probeMutation(); mutate(((ServiceLink) call.getArgument(1)).getServiceId(), true); return null;
            });
            when(maps.findConsumedMapsForMutation(7L)).thenAnswer(call -> {
                probeMutation(); List<ServiceConsumeMap> rows = new ArrayList<>();
                for (long id : links.get()) { var map = new ServiceConsumeMapRecord(); map.setServiceId(7L); map.setConsumedServiceId(id); rows.add(map); }
                return rows;
            });
            var processes = mock(ObjectProcessManager.class); inject(owner, "objectProcessManager", processes);
            doAnswer(call -> { mutate(((ServiceConsumeMap) call.getArgument(1)).getConsumedServiceId(), false); return null; })
                    .when(processes).scheduleProcessInstance(anyString(), any(ServiceConsumeMap.class), isNull());
            return owner;
        }
        void probeMutation() {
            if (Thread.currentThread() == nativeThread.get()) {
                // Old singular owners enter here without trying SETLINKS: make that failure deterministic too.
                nativeAttempt.countDown();
                if (pauseNative) { nativeInside.countDown(); await(releaseNative); }
            }
        }
        void mutate(long id, boolean add) {
            links.updateAndGet(old -> { var next = new LinkedHashSet<>(old); if (add) next.add(id); else next.remove(id); return Set.copyOf(next); });
            if (Thread.currentThread() == nativeThread.get()) nativeMutated.set(true);
        }
        void nativeSet(List<Long> ids) {
            var request = new ApiRequest(null, null);
            request.setRequestObject(Map.of("serviceLinks", ids.stream().map(id -> Map.of("serviceId", id)).toList()));
            nativeHandler.perform("setservicelinks", base.target, request);
        }
        void preview() {
            var request = base.request(null, false); request.setId(null);
            request.setRequestObject(Map.of("resourceType", "service", "operation", "update_links", "resourceId", "1s7",
                    "parameters", Map.of("serviceIds", List.of("1s10"))));
            base.id = (String) base.service.preview(request).get("id"); base.confirm();
        }
        String apply() {
            ApiContext.newContext().setPolicy(base.policy);
            try { return (String) base.apply(false).get("status"); }
            catch (ClientVisibleException failure) { return failure.getCode(); }
            finally { ApiContext.remove(); }
        }
    }
    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void await(CountDownLatch barrier) {
        try { assertTrue("Native Service links deterministic barrier", barrier.await(4, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
