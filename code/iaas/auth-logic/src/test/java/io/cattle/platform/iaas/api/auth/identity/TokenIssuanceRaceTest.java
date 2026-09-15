package io.cattle.platform.iaas.api.auth.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.cattle.platform.core.model.AuthToken;
import io.cattle.platform.core.model.tables.records.AuthTokenRecord;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockCallbackWithException;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.provider.LockProvider;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.Test;

public class TokenIssuanceRaceTest {

    private static final long AUTHENTICATED_ACCOUNT = 42L;
    private static final long TOKEN_ACCOUNT = 7L;
    private static final String OLDER_SESSION =
            "1726358400000.0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String NEWER_SESSION =
            "1726358400001.fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    @Test
    public void delayedOlderLoginCannotRevokeNewerSessionAcrossOneHundredDeterministicRuns()
            throws Exception {
        for (int run = 0; run < 100; run++) {
            final FakeAuthTokenDao dao = new FakeAuthTokenDao();
            final TestManager manager = manager(dao, true);
            final CountDownLatch olderRequestCaptured = new CountDownLatch(1);
            final CountDownLatch releaseOlderResponse = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<ClientVisibleException> older = executor.submit(() -> {
                    olderRequestCaptured.countDown();
                    assertTrue(releaseOlderResponse.await(5, TimeUnit.SECONDS));
                    try {
                        manager.issueToken(token("older-jwt"), TOKEN_ACCOUNT, OLDER_SESSION);
                        fail("The delayed older login must be rejected");
                        return null;
                    } catch (ClientVisibleException expected) {
                        return expected;
                    }
                });

                assertTrue(olderRequestCaptured.await(5, TimeUnit.SECONDS));
                Token newer = manager.issueToken(token("newer-jwt"), TOKEN_ACCOUNT, NEWER_SESSION);
                releaseOlderResponse.countDown();

                ClientVisibleException rejected = older.get(5, TimeUnit.SECONDS);
                assertNotNull(rejected);
                assertEquals(409, rejected.getStatus());
                assertEquals("ClientSessionSuperseded", rejected.getCode());
                assertNotNull(dao.getTokenByKey(newer.getJwt()));
                assertEquals(NEWER_SESSION, dao.getTokenByKey(newer.getJwt()).getClientSessionId());
                assertEquals(1, dao.createCount);
                assertEquals(1, manager.disconnectCount);
            } finally {
                releaseOlderResponse.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void restrictedReplacementCreatesNewTokenBeforeDeletingPreviousOnes() {
        FakeAuthTokenDao dao = new FakeAuthTokenDao();
        dao.addExisting("previous-key", OLDER_SESSION);
        TestManager manager = manager(dao, true);

        Token issued = manager.issueToken(token("new-jwt"), TOKEN_ACCOUNT, NEWER_SESSION);

        assertEquals(2, dao.operations.size());
        assertEquals("create:" + issued.getJwt(), dao.operations.get(0));
        assertEquals("delete-previous:" + issued.getJwt(), dao.operations.get(1));
        assertNull(dao.getTokenByKey("previous-key"));
        assertNotNull(dao.getTokenByKey(issued.getJwt()));
        assertEquals(1, manager.disconnectCount);
    }

    @Test
    public void legacyLoginCannotReplaceAnActiveBoundSession() {
        FakeAuthTokenDao dao = new FakeAuthTokenDao();
        dao.addExisting("bound-key", NEWER_SESSION);
        TestManager manager = manager(dao, true);

        try {
            manager.issueToken(token("legacy-jwt"), TOKEN_ACCOUNT, null);
            fail("A legacy page must not replace an active bound session");
        } catch (ClientVisibleException expected) {
            assertEquals("ClientSessionSuperseded", expected.getCode());
        }

        assertNotNull(dao.getTokenByKey("bound-key"));
        assertEquals(0, dao.createCount);
        assertEquals(0, manager.disconnectCount);
    }

    @Test
    public void unrestrictedConcurrentSessionsRemainIndependent() {
        FakeAuthTokenDao dao = new FakeAuthTokenDao();
        TestManager manager = manager(dao, false);

        Token older = manager.issueToken(token("older-jwt"), TOKEN_ACCOUNT, OLDER_SESSION);
        Token newer = manager.issueToken(token("newer-jwt"), TOKEN_ACCOUNT, NEWER_SESSION);

        assertNotNull(dao.getTokenByKey(older.getJwt()));
        assertNotNull(dao.getTokenByKey(newer.getJwt()));
        assertEquals(2, dao.createCount);
        assertTrue(dao.operations.stream().noneMatch(value -> value.startsWith("delete-previous:")));
        assertEquals(0, manager.disconnectCount);
    }

    @Test
    public void failedRestrictedReplacementRemovesOnlyItsNewToken() {
        FakeAuthTokenDao dao = new FakeAuthTokenDao();
        dao.addExisting("previous-key", OLDER_SESSION);
        dao.failDeletePrevious = true;
        TestManager manager = manager(dao, true);

        try {
            manager.issueToken(token("new-jwt"), TOKEN_ACCOUNT, NEWER_SESSION);
            fail("Replacement failure must be returned");
        } catch (IllegalStateException expected) {
            assertEquals("simulated delete failure", expected.getMessage());
        }

        assertNotNull(dao.getTokenByKey("previous-key"));
        assertEquals(1, dao.createCount);
        assertEquals(1, dao.deleteCount);
        assertEquals(0, manager.disconnectCount);
    }

    private static TestManager manager(FakeAuthTokenDao dao, boolean restricted) {
        TestManager manager = new TestManager();
        manager.authTokenDao = dao;
        manager.lockManager = new SerialLockManager();
        manager.restricted = restricted;
        return manager;
    }

    private static Token token(String jwt) {
        Token token = new Token();
        token.setJwt(jwt);
        token.setAuthProvider("oidcconfig");
        token.setAuthenticatedAsAccountId(AUTHENTICATED_ACCOUNT);
        return token;
    }

    private static class TestManager extends TokenResourceManager {
        boolean restricted;
        int disconnectCount;

        @Override
        protected boolean restrictConcurrentSessions() {
            return restricted;
        }

        @Override
        protected void publishSessionDisconnect(long authenticatedAsAccountId) {
            assertEquals(AUTHENTICATED_ACCOUNT, authenticatedAsAccountId);
            disconnectCount++;
        }
    }

    private static class FakeAuthTokenDao implements AuthTokenDao {
        final List<AuthTokenRecord> tokens = Collections.synchronizedList(new ArrayList<>());
        final List<String> operations = new ArrayList<>();
        int nextKey = 1;
        int createCount;
        int deleteCount;
        boolean failDeletePrevious;

        void addExisting(String key, String clientSessionId) {
            tokens.add(record(key, clientSessionId));
        }

        @Override
        public AuthToken getTokenByKey(String key) {
            synchronized (tokens) {
                for (AuthTokenRecord token : tokens) {
                    if (key.equals(token.getKey())) {
                        return token;
                    }
                }
            }
            return null;
        }

        @Override
        public AuthToken createToken(String jwt, String provider, long accountId,
                long authenticatedAsAccountId) {
            return createToken(jwt, provider, accountId, authenticatedAsAccountId, null);
        }

        @Override
        public AuthToken createToken(String jwt, String provider, long accountId,
                long authenticatedAsAccountId, String clientSessionId) {
            AuthTokenRecord record = record("issued-key-" + nextKey++, clientSessionId);
            record.setValue(jwt);
            record.setProvider(provider);
            record.setAccountId(accountId);
            record.setAuthenticatedAsAccountId(authenticatedAsAccountId);
            tokens.add(record);
            createCount++;
            operations.add("create:" + record.getKey());
            return record;
        }

        @Override
        public AuthToken getTokenByAccountId(long accountId) {
            return null;
        }

        @Override
        public String getNewestClientSessionId(long authenticatedAsAccountId, long tokenAccountId) {
            synchronized (tokens) {
                return tokens.stream()
                        .filter(token -> authenticatedAsAccountId == token.getAuthenticatedAsAccountId())
                        .filter(token -> tokenAccountId == token.getAccountId())
                        .map(AuthTokenRecord::getClientSessionId)
                        .filter(value -> value != null && !value.isEmpty())
                        .max(Comparator.naturalOrder())
                        .orElse(null);
            }
        }

        @Override
        public void deletePreviousTokens(long authenticatedAsAccountId, long tokenAccountId,
                String keepKey) {
            operations.add("delete-previous:" + keepKey);
            if (failDeletePrevious) {
                throw new IllegalStateException("simulated delete failure");
            }
            synchronized (tokens) {
                Iterator<AuthTokenRecord> iterator = tokens.iterator();
                while (iterator.hasNext()) {
                    AuthTokenRecord token = iterator.next();
                    if (authenticatedAsAccountId == token.getAuthenticatedAsAccountId()
                            && tokenAccountId == token.getAccountId()
                            && !keepKey.equals(token.getKey())) {
                        iterator.remove();
                    }
                }
            }
        }

        @Override
        public int deleteTokensForAccount(long authenticatedAsAccountId) {
            return 0;
        }

        @Override
        public boolean deleteToken(String key) {
            synchronized (tokens) {
                Iterator<AuthTokenRecord> iterator = tokens.iterator();
                while (iterator.hasNext()) {
                    if (key.equals(iterator.next().getKey())) {
                        iterator.remove();
                        deleteCount++;
                        return true;
                    }
                }
            }
            return false;
        }

        private static AuthTokenRecord record(String key, String clientSessionId) {
            AuthTokenRecord record = new AuthTokenRecord();
            record.setKey(key);
            record.setClientSessionId(clientSessionId);
            record.setAccountId(TOKEN_ACCOUNT);
            record.setAuthenticatedAsAccountId(AUTHENTICATED_ACCOUNT);
            record.setExpires(new Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)));
            return record;
        }
    }

    private static class SerialLockManager implements LockManager {
        private final ReentrantLock lock = new ReentrantLock();

        @Override
        public <T, E extends Throwable> T lock(LockDefinition lockDef,
                LockCallbackWithException<T, E> callback, Class<E> clz) throws E {
            lock.lock();
            try {
                return callback.doWithLock();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public <T> T lock(LockDefinition lockDef, LockCallback<T> callback) {
            lock.lock();
            try {
                return callback.doWithLock();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public <T> T tryLock(LockDefinition lockDef, LockCallback<T> callback) {
            return lock(lockDef, callback);
        }

        @Override
        public <T, E extends Throwable> T tryLock(LockDefinition lockDef,
                LockCallbackWithException<T, E> callback, Class<E> clz) throws E {
            return lock(lockDef, callback, clz);
        }

        @Override
        public LockProvider getLockProvider() {
            return null;
        }
    }
}
