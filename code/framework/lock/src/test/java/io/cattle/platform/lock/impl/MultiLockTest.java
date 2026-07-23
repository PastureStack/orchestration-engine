package io.cattle.platform.lock.impl;

import static org.junit.Assert.*;
import io.cattle.platform.lock.impl.LockTestUtils.TestLock;
import io.cattle.platform.lock.exception.FailedToAcquireLockException;

import org.junit.Test;

public class MultiLockTest {

    @Test
    public void test_good_lock() {
        TestLock good = LockTestUtils.goodLock(null);
        TestLock good2 = LockTestUtils.goodLock(null);

        MultiLock multiLock = new MultiLock(null, good, good2);
        multiLock.lock();

        assertEquals(0, good.tryLockCount);
        assertEquals(1, good.lockCount);
        assertEquals(0, good2.tryLockCount);
        assertEquals(1, good2.lockCount);
    }

    @Test
    public void test_good_tryLock() {
        TestLock good = LockTestUtils.goodLock(null);
        TestLock good2 = LockTestUtils.goodLock(null);

        MultiLock multiLock = new MultiLock(null, good, good2);
        multiLock.tryLock();

        assertEquals(1, good.tryLockCount);
        assertEquals(0, good.lockCount);
        assertEquals(1, good2.tryLockCount);
        assertEquals(0, good2.lockCount);
    }

    @Test
    public void test_good_unlock() {
        TestLock good = LockTestUtils.goodLock(null);
        TestLock good2 = LockTestUtils.goodLock(null);

        MultiLock multiLock = new MultiLock(null, good, good2);
        multiLock.unlock();

        assertEquals(1, good.unlockCount);
        assertEquals(1, good2.unlockCount);
    }

    @Test
    public void test_bad_lock() {
        TestLock good = LockTestUtils.goodLock(null);
        TestLock bad = LockTestUtils.badLock(null);
        TestLock good2 = LockTestUtils.goodLock(null);

        try {
            MultiLock multiLock = new MultiLock(null, good, bad, good2);
            multiLock.lock();
            fail();
        } catch (FailedToAcquireLockException e) {
        }

        assertEquals(1, good.lockCount);
        assertEquals(1, bad.lockCount);
        assertEquals(0, good2.lockCount);
    }

    @Test
    public void test_bad_trylock() {
        TestLock good = LockTestUtils.goodLock(null);
        TestLock bad = LockTestUtils.badLock(null);
        TestLock good2 = LockTestUtils.goodLock(null);

        MultiLock multiLock = new MultiLock(null, good, bad, good2);
        assertTrue(!multiLock.tryLock());

        assertEquals(1, good.tryLockCount);
        assertEquals(1, bad.tryLockCount);
        assertEquals(0, good2.tryLockCount);
    }

}
