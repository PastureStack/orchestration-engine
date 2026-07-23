package io.cattle.platform.lock.impl;

import static org.junit.Assert.*;
import io.cattle.platform.lock.Lock;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockCallbackNoReturn;
import io.cattle.platform.lock.LockCallbackWithException;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.definition.MultiLockDefinition;
import io.cattle.platform.lock.exception.FailedToAcquireLockException;
import io.cattle.platform.lock.impl.LockTestUtils.TestLock;
import io.cattle.platform.lock.provider.LockProvider;

import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public class LockManagerImplTest {

    LockProvider lockProvider;
    LockManagerImpl lockManager;
    LockDefinition goodLockDef = new TestLockDefinition("good");
    LockDefinition good2LockDef = new TestLockDefinition("good2");
    LockDefinition badLockDef = new TestLockDefinition("bad");

    TestLock goodLock = LockTestUtils.goodLock(goodLockDef);
    TestLock good2Lock = LockTestUtils.goodLock(good2LockDef);
    TestLock badLock = LockTestUtils.badLock(badLockDef);
    TestLockProvider testLockProvider;

    @Before
    public void setUp() {
        testLockProvider = new TestLockProvider();
        testLockProvider.add(goodLockDef, goodLock);
        testLockProvider.add(good2LockDef, good2Lock);
        testLockProvider.add(badLockDef, badLock);
        lockProvider = testLockProvider;

        lockManager = new LockManagerImpl();
        lockManager.setLockProvider(lockProvider);
    }

    @Test
    public void test_bad_multilock() {
        MultiLockDefinition def = new TestMultiLockDefinition(goodLockDef, badLockDef, good2LockDef);

        try {
            lockManager.lock(def, new LockCallbackNoReturn() {
                @Override
                public void doWithLockNoResult() {
                    fail();
                }
            });
        } catch (FailedToAcquireLockException e) {
            assertTrue(e.isLock(badLockDef));
        }

        assertEquals(1, goodLock.lockCount);
        assertEquals(1, badLock.lockCount);
        assertEquals(0, good2Lock.lockCount);

        assertEquals(1, goodLock.unlockCount);
        assertEquals(1, badLock.unlockCount);
        assertEquals(1, good2Lock.unlockCount);

        assertEquals(1, testLockProvider.getLockCount(goodLockDef));
        assertEquals(1, testLockProvider.getLockCount(badLockDef));
        assertEquals(1, testLockProvider.getLockCount(good2LockDef));

        assertEquals(1, testLockProvider.releaseLockCount(goodLock));
        assertEquals(1, testLockProvider.releaseLockCount(badLock));
        assertEquals(1, testLockProvider.releaseLockCount(good2Lock));
    }

    @Test
    public void test_good_multilock() {
        MultiLockDefinition def = new TestMultiLockDefinition(goodLockDef, goodLockDef, good2LockDef);

        final AtomicInteger i = new AtomicInteger();
        lockManager.lock(def, new LockCallbackNoReturn() {
            @Override
            public void doWithLockNoResult() {
                i.incrementAndGet();
            }
        });

        assertEquals(1, i.intValue());

        assertEquals(2, goodLock.lockCount);
        assertEquals(1, good2Lock.lockCount);

        assertEquals(2, goodLock.unlockCount);
        assertEquals(1, good2Lock.unlockCount);

        assertEquals(2, testLockProvider.getLockCount(goodLockDef));
        assertEquals(1, testLockProvider.getLockCount(good2LockDef));

        assertEquals(2, testLockProvider.releaseLockCount(goodLock));
        assertEquals(1, testLockProvider.releaseLockCount(good2Lock));
    }

    @Test
    public void test_exceptions() {
        try {
            lockManager.lock(goodLockDef, new LockCallbackNoReturn() {
                @Override
                public void doWithLockNoResult() {
                    throw new RuntimeException("42");
                }
            });
        } catch (RuntimeException e) {
            assertEquals("42", e.getMessage());
        }

        assertEquals(1, goodLock.lockCount);
        assertEquals(1, goodLock.unlockCount);
        assertEquals(1, testLockProvider.getLockCount(goodLockDef));
        assertEquals(1, testLockProvider.releaseLockCount(goodLock));
    }

    @Test
    public void test_checked_exceptions() {
        try {
            lockManager.lock(goodLockDef, new LockCallbackWithException<Object, FileNotFoundException>() {
                @Override
                public Object doWithLock() throws FileNotFoundException {
                    throw new FileNotFoundException("42");
                }
            }, FileNotFoundException.class);
        } catch (FileNotFoundException e) {
            assertEquals("42", e.getMessage());
        }

        assertEquals(1, goodLock.lockCount);
        assertEquals(1, goodLock.unlockCount);
        assertEquals(1, testLockProvider.getLockCount(goodLockDef));
        assertEquals(1, testLockProvider.releaseLockCount(goodLock));
    }

    @Test
    public void test_return() {
        assertEquals(Long.valueOf(42), lockManager.lock(goodLockDef, new LockCallback<Long>() {
            @Override
            public Long doWithLock() {
                return 42L;
            }
        }));

        assertEquals(1, goodLock.lockCount);
        assertEquals(1, goodLock.unlockCount);
        assertEquals(1, testLockProvider.getLockCount(goodLockDef));
        assertEquals(1, testLockProvider.releaseLockCount(goodLock));
    }

    private static class TestLockProvider implements LockProvider {
        private final Map<LockDefinition, Lock> locks = new HashMap<>();
        private final Map<LockDefinition, Integer> getLockCounts = new HashMap<>();
        private final Map<Lock, Integer> releaseLockCounts = new HashMap<>();

        void add(LockDefinition definition, Lock lock) {
            locks.put(definition, lock);
        }

        int getLockCount(LockDefinition definition) {
            Integer count = getLockCounts.get(definition);
            return count == null ? 0 : count;
        }

        int releaseLockCount(Lock lock) {
            Integer count = releaseLockCounts.get(lock);
            return count == null ? 0 : count;
        }

        @Override
        public Lock getLock(LockDefinition lockDefinition) {
            getLockCounts.put(lockDefinition, getLockCount(lockDefinition) + 1);
            return locks.get(lockDefinition);
        }

        @Override
        public void releaseLock(Lock lock) {
            releaseLockCounts.put(lock, releaseLockCount(lock) + 1);
        }

        @Override
        public String getName() {
            return "test";
        }
    }

}
