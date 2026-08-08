package io.cattle.platform.lock.impl;

import io.cattle.platform.lock.Lock;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.exception.FailedToAcquireLockException;

public class LockTestUtils {

    public static TestLock goodLock(LockDefinition def) {
        return getLock(true, def);
    }

    public static TestLock badLock(LockDefinition def) {
        return getLock(false, def);
    }

    public static TestLock getLock(boolean good, LockDefinition def) {
        return new TestLock(good, def);
    }

    public static class TestLock implements Lock {
        private final boolean good;
        private final LockDefinition definition;
        int tryLockCount;
        int lockCount;
        int unlockCount;

        TestLock(boolean good, LockDefinition definition) {
            this.good = good;
            this.definition = definition;
        }

        @Override
        public boolean tryLock() {
            tryLockCount++;
            return good;
        }

        @Override
        public void lock() throws FailedToAcquireLockException {
            lockCount++;
            if (!good) {
                throw new FailedToAcquireLockException(definition);
            }
        }

        @Override
        public void unlock() {
            unlockCount++;
        }

        @Override
        public LockDefinition getLockDefinition() {
            return definition;
        }
    }
}
