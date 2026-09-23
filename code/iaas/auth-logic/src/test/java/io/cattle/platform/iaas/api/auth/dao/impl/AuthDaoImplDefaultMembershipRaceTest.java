package io.cattle.platform.iaas.api.auth.dao.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.ProjectMember;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.ProjectMemberRecord;
import io.cattle.platform.iaas.api.auth.projects.Member;
import io.cattle.platform.iaas.api.auth.projects.ProjectLock;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockCallbackWithException;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.provider.LockProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.Test;

public class AuthDaoImplDefaultMembershipRaceTest {

    @Test
    public void concurrentRoleAssignmentCannotBeOverriddenByBaselineMember() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                final TestAuthDao dao = new TestAuthDao();
                final SerialLockManager locks = new SerialLockManager();
                dao.lockManager = locks;
                final Account project = account(7L);
                final ProjectMemberRecord restrictedGroup = member(7L, "oidc_group", "qa-team", "restricted");
                final CountDownLatch adminHasLock = new CountDownLatch(1);
                final CountDownLatch allowAdminCommit = new CountDownLatch(1);

                Future<?> admin = executor.submit(() -> locks.lock(new ProjectLock(project), () -> {
                    adminHasLock.countDown();
                    await(allowAdminCommit);
                    dao.members.add(restrictedGroup);
                    return null;
                }));
                if (!adminHasLock.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("admin did not acquire the project lock");
                }

                Set<Identity> loginIdentities = new HashSet<Identity>();
                loginIdentities.add(new Identity("oidc_group", "qa-team"));
                Future<ProjectMember> login = executor.submit(() -> dao.ensureProjectMemberIfNoIdentityMembership(
                        project, loginIdentities,
                        new Member(new Identity(ProjectConstants.RANCHER_ID, "42"), ProjectConstants.MEMBER)));

                if (!locks.secondLockAttempt.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("login did not reach the project lock");
                }
                assertEquals("iteration " + iteration + " queried membership outside the lock",
                        0, dao.queries.get());
                allowAdminCommit.countDown();
                admin.get(5, TimeUnit.SECONDS);
                ProjectMember result = login.get(5, TimeUnit.SECONDS);

                assertSame("iteration " + iteration, restrictedGroup, result);
                assertEquals("iteration " + iteration, 0, dao.created.get());
                assertEquals("iteration " + iteration, 1, dao.members.size());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void createsOneStableMemberWhenNoLoginIdentityHasMembership() {
        TestAuthDao dao = new TestAuthDao();
        dao.lockManager = new SerialLockManager();
        Account project = account(7L);
        Set<Identity> identities = Collections.singleton(new Identity("oidc_user", "subject-42"));

        ProjectMember first = dao.ensureProjectMemberIfNoIdentityMembership(project, identities,
                new Member(new Identity(ProjectConstants.RANCHER_ID, "42"), ProjectConstants.MEMBER));
        ProjectMember second = dao.ensureProjectMemberIfNoIdentityMembership(project, identities,
                new Member(new Identity(ProjectConstants.RANCHER_ID, "42"), ProjectConstants.MEMBER));

        assertSame(first, second);
        assertEquals(1, dao.created.get());
        assertEquals(1, dao.members.size());
    }

    private static Account account(long id) {
        AccountRecord result = new AccountRecord();
        result.setId(id);
        result.setKind(ProjectConstants.TYPE);
        return result;
    }

    private static ProjectMemberRecord member(long projectId, String type, String id, String role) {
        ProjectMemberRecord result = new ProjectMemberRecord();
        result.setProjectId(projectId);
        result.setExternalIdType(type);
        result.setExternalId(id);
        result.setRole(role);
        return result;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("barrier timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static class TestAuthDao extends AuthDaoImpl {
        private final List<ProjectMember> members = Collections.synchronizedList(new ArrayList<ProjectMember>());
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger queries = new AtomicInteger();

        @Override
        public List<? extends ProjectMember> getProjectMembersByIdentity(long projectId, Set<Identity> identities) {
            queries.incrementAndGet();
            List<ProjectMember> result = new ArrayList<ProjectMember>();
            synchronized (members) {
                for (ProjectMember member : members) {
                    Identity candidate = new Identity(member.getExternalIdType(), member.getExternalId());
                    if (member.getProjectId() == projectId && identities.contains(candidate)) {
                        result.add(member);
                    }
                }
            }
            return result;
        }

        @Override
        public ProjectMember createProjectMember(Account project, Member member) {
            ProjectMemberRecord result = member(project.getId(), member.getExternalIdType(),
                    member.getExternalId(), member.getRole());
            members.add(result);
            created.incrementAndGet();
            return result;
        }
    }

    private static class SerialLockManager implements LockManager {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch secondLockAttempt = new CountDownLatch(1);

        private void beforeLock() {
            if (attempts.incrementAndGet() == 2) {
                secondLockAttempt.countDown();
            }
        }

        @Override
        public <T, E extends Throwable> T lock(LockDefinition lockDef,
                LockCallbackWithException<T, E> callback, Class<E> clz) throws E {
            beforeLock();
            lock.lock();
            try {
                return callback.doWithLock();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public <T> T lock(LockDefinition lockDef, LockCallback<T> callback) {
            beforeLock();
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
