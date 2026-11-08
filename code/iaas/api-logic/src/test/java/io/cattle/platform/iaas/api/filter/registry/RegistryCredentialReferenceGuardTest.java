package io.cattle.platform.iaas.api.filter.registry;

import static org.junit.Assert.*;
import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;
import static io.cattle.platform.core.model.tables.StoragePoolTable.STORAGE_POOL;

import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.core.model.tables.records.StoragePoolRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.impl.ResourceStatesDefinition;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.definition.Namespace;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.cattle.platform.lock.provider.impl.StandardLock;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.process.common.generic.GenericResourceProcessState;
import io.cattle.platform.process.common.lock.ResourceChangeLock;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.h2.jdbcx.JdbcDataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

public class RegistryCredentialReferenceGuardTest {
    @Test public void originalCredentialKindsAndEveryPreviouslyLegalRegistryParentStateKeepTheirNativeWriter() {
        Fixture f = new Fixture();
        AtomicInteger legacy = new AtomicInteger();
        ResourceManager ordinary = manager((method, args) -> legacy.incrementAndGet());
        for (String kind : List.of("apiKey", "password", "registrationToken", "agentToken")) {
            ApiRequest request = request(kind, Map.of("kind", kind));
            f.guard.create(kind, request, ordinary); f.guard.update(kind, "7", request, ordinary);
        }
        assertEquals(8, legacy.get());
        for (String state : List.of("requested", "registering", "activating", "active", "inactive", "updating-active", "updating-inactive")) {
            f.sql.update("UPDATE cattle.storage_pool SET state=? WHERE id=3", state);
            String type = "active".equals(state) ? "registrycredential" : "registryCredential";
            assertEquals("saved", f.guard.create(type, request(type, Map.of("registryId", 3L)),
                    manager((method, args) -> "saved")));
        }
        for (String state : List.of("removing", "removed", "purging", "purged")) {
            f.sql.update("UPDATE cattle.storage_pool SET state=? WHERE id=3", state);
            assertThrows(ClientVisibleException.class, () -> f.guard.create("registryCredential",
                    request("registryCredential", Map.of("registryId", 3L)), ordinary));
            assertThrows(ClientVisibleException.class, () -> f.guard.update("registryCredential", "11",
                    request("registryCredential", Map.of("registryId", 3L)), f.credentialManager(() -> legacy.incrementAndGet())));
        }
        assertEquals(8, legacy.get());
        f.sql.update("UPDATE cattle.storage_pool SET state='active',removed=CURRENT_TIMESTAMP WHERE id=3");
        assertThrows(ClientVisibleException.class, () -> f.guard.create("registryCredential",
                request("registryCredential", Map.of("registryId", 3L)), ordinary));
    }

    @Test public void createAndReparentCompleteBeforeTheSameNativeRemovingTransitionCanCommit() throws Exception {
        for (boolean update : List.of(false, true)) {
            Fixture f = new Fixture(); var threads = Executors.newFixedThreadPool(2);
            if (update) {
                f.sql.update("INSERT INTO cattle.storage_pool(id,uuid,account_id,kind,state) VALUES(4,'registry-4',5,'registry','active')");
                f.sql.update("UPDATE cattle.credential SET registry_id=4 WHERE id=11");
            }
            CountDownLatch saving = new CountDownLatch(1), release = new CountDownLatch(1);
            try {
                var credential = threads.submit(() -> {
                    Supplier<Object> save = () -> {
                        saving.countDown(); await(release);
                        if (update) f.sql.update("UPDATE cattle.credential SET registry_id=3 WHERE id=11");
                        else f.sql.update("INSERT INTO cattle.credential(id,uuid,account_id,registry_id,kind,state)"
                                + " VALUES(12,'new-child',5,3,'registryCredential','active')");
                        return "saved";
                    };
                    if (update) return f.guard.update("registryCredential", "11", request("registryCredential", Map.of("registryId", 3L)),
                            f.credentialManager(save));
                    return f.guard.create("registryCredential", request("registryCredential", Map.of("registryId", 3L)),
                            manager((method, args) -> save.get()));
                });
                await(saving);
                var removal = threads.submit(() -> { f.competitor.set(Thread.currentThread()); return f.remove(() -> true); });
                await(f.attempt); assertFalse(removal.isDone());
                assertEquals("inactive", f.state()); release.countDown();
                assertEquals("saved", credential.get(4, TimeUnit.SECONDS)); assertTrue(removal.get(4, TimeUnit.SECONDS));
                assertEquals("removing", f.state()); assertEquals(1, f.nativeWrites.get());
            } finally { release.countDown(); threads.shutdownNow(); }
        }
    }

    @Test public void acceptedRemovingFencesCreateAndUpdateThroughTheRealCommitTailWithoutAChildWrite() throws Exception {
        for (boolean update : List.of(false, true)) {
            Fixture f = new Fixture(); var threads = Executors.newFixedThreadPool(2);
            CountDownLatch removing = new CountDownLatch(1), commit = new CountDownLatch(1);
            AtomicInteger childWrites = new AtomicInteger();
            try {
                var removal = threads.submit(() -> f.remove(() -> { removing.countDown(); await(commit); return true; }));
                await(removing);
                var child = threads.submit(() -> {
                    f.competitor.set(Thread.currentThread());
                    return assertThrows(ClientVisibleException.class, () -> {
                        if (update) f.guard.update("registryCredential", "11", request("registryCredential", Map.of("registryId", 3L)),
                                f.credentialManager(childWrites::incrementAndGet));
                        else f.guard.create("registryCredential", request("registryCredential", Map.of("registryId", 3L)),
                                manager((method, args) -> childWrites.incrementAndGet()));
                    });
                });
                await(f.attempt); assertFalse(child.isDone()); assertEquals(0, childWrites.get());
                commit.countDown(); assertTrue(removal.get(4, TimeUnit.SECONDS)); assertNotNull(child.get(4, TimeUnit.SECONDS));
                assertEquals("removing", f.state()); assertEquals(0, childWrites.get());
            } finally { commit.countDown(); threads.shutdownNow(); }
        }
    }

    @Test public void typedFullSetPreconditionRunsInsideTheNativeOwnerAndLegacyDeleteStillUsesTheOriginalScheduler() {
        Fixture f = new Fixture(); ApiRequest request = request("registry", Map.of()); request.setMethod("DELETE");
        ResourceManager nativeDelete = manager((method, args) -> {
            if (method.equals("getById")) return f.parent();
            if (method.equals("delete")) { assertTrue(f.remove(() -> true)); return f.parent(); }
            throw new AssertionError(method);
        });
        f.sql.update("INSERT INTO cattle.credential(id,uuid,account_id,registry_id,kind,state)"
                + " VALUES(12,'not-reviewed',5,3,'registryCredential','active')");
        assertThrows(ClientVisibleException.class, () -> RegistryCredentialReferenceGuard.withRemovalPrecondition(request, 3, "registry-3", 5,
                parent -> {
                    assertEquals("inactive", parent.getState());
                    if (f.sql.queryForObject("SELECT COUNT(*) FROM cattle.credential WHERE registry_id=3", Integer.class) != 1)
                        throw new ClientVisibleException(409, "ResourceChangeConflict");
                }, () -> f.guard.delete("registry", "3", request, nativeDelete)));
        assertEquals(0, f.nativeWrites.get()); assertEquals("inactive", f.state());
        // The same native request without a private typed precondition keeps its original legal REMOVE behavior.
        f.guard.delete("registry", "3", request, nativeDelete);
        assertEquals(1, f.nativeWrites.get()); assertEquals("removing", f.state());
    }

    private interface Call { Object invoke(String method, Object[] args); }
    private static ResourceManager manager(Call call) {
        return (ResourceManager) Proxy.newProxyInstance(RegistryCredentialReferenceGuardTest.class.getClassLoader(),
                new Class<?>[] { ResourceManager.class }, (proxy, method, args) -> call.invoke(method.getName(), args));
    }
    private static ApiRequest request(String type, Map<String, Object> body) {
        ApiRequest request = new ApiRequest(null, null); request.setType(type); request.setRequestObject(new java.util.LinkedHashMap<>(body)); return request;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue("deterministic barrier timed out", latch.await(4, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static class Fixture {
        final JdbcDataSource source = new JdbcDataSource();
        final JdbcTemplate sql;
        final RegistryCredentialReferenceGuard guard = new RegistryCredentialReferenceGuard();
        final LockManagerImpl locks = new LockManagerImpl();
        final AtomicReference<Thread> competitor = new AtomicReference<>();
        final CountDownLatch attempt = new CountDownLatch(1);
        final AtomicInteger nativeWrites = new AtomicInteger();
        Fixture() {
            source.setURL("jdbc:h2:mem:registry-reference-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            sql = new JdbcTemplate(source); sql.execute("CREATE SCHEMA cattle");
            for (org.jooq.Table<?> table : List.<org.jooq.Table<?>>of(STORAGE_POOL, CREDENTIAL)) {
                String fields = java.util.Arrays.stream(table.fields()).map(field -> {
                    Class<?> type = field.getDataType().getType();
                    String sqlType = type == Long.class ? "BIGINT" : type == Integer.class ? "INT" : type == Boolean.class ? "BOOLEAN"
                            : java.util.Date.class.isAssignableFrom(type) ? "TIMESTAMP" : "CLOB";
                    return field.getName() + " " + sqlType;
                }).collect(java.util.stream.Collectors.joining(","));
                sql.execute("CREATE TABLE cattle." + table.getName() + "(" + fields + ")");
            }
            sql.update("INSERT INTO cattle.storage_pool(id,uuid,account_id,kind,state) VALUES(3,'registry-3',5,'registry','inactive')");
            sql.update("INSERT INTO cattle.credential(id,uuid,account_id,registry_id,kind,state) VALUES(11,'credential-11',5,3,'registryCredential','active')");
            guard.rows = new RegistryCredentialReferenceGuard.RegistryRead();
            guard.rows.setConfiguration(new DefaultConfiguration().set(SQLDialect.H2)
                    .set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(source))));
            locks.setLockProvider(new InMemoryLockProvider() {
                @Override protected StandardLock createLock(LockDefinition definition) {
                    return new StandardLock(definition, new ReentrantLock()) {
                        @Override public void lock() {
                            if (Thread.currentThread() == competitor.get() && definition.getLockId().contains("storagePool.3.CHANGE")) attempt.countDown();
                            super.lock();
                        }
                    };
                }
            });
            guard.locks = locks; guard.transactions = new DataSourceTransactionManager(source);
        }
        String state() { return sql.queryForObject("SELECT state FROM cattle.storage_pool WHERE id=3", String.class); }
        StoragePoolRecord parent() {
            StoragePoolRecord row = new StoragePoolRecord(); row.setId(3L); row.setUuid("registry-3"); row.setAccountId(5L);
            row.setKind("registry"); row.setState(state()); return row;
        }
        ResourceManager credentialManager(Supplier<Object> save) {
            CredentialRecord credential = new CredentialRecord(); credential.setId(11L); credential.setUuid("credential-11");
            credential.setAccountId(5L); credential.setRegistryId(sql.queryForObject("SELECT registry_id FROM cattle.credential WHERE id=11", Long.class));
            credential.setKind("registryCredential");
            return manager((method, args) -> method.equals("getById") ? credential : save.get());
        }
        boolean remove(Supplier<Boolean> tail) {
            return new TransactionTemplate(guard.transactions).execute(status -> {
                // This is the scheduler's actual lock key and GenericResourceProcessState initial state-write path.
                boolean written = locks.lock(new Namespace("schedule").getLockDefinition(new ResourceChangeLock("storagePool", 3)), () -> {
                    ObjectManager objects = (ObjectManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ObjectManager.class },
                            (proxy, method, args) -> {
                                if (Set.of("loadResource", "reload").contains(method.getName())) return parent();
                                if (method.getName().equals("setFields")) {
                                    String value = ((Object[]) args[2])[0].toString();
                                    sql.update("UPDATE cattle.storage_pool SET state=? WHERE id=3", value); nativeWrites.incrementAndGet(); return parent();
                                }
                                throw new AssertionError(method.getName());
                            });
                    class State extends GenericResourceProcessState {
                        State() { super(null, new ResourceStatesDefinition(),
                                new LaunchConfiguration("storagepool.remove", "storagePool", "3", 5L, null, Map.of()), objects, List.of(guard)); }
                        boolean write() { return setState(true, "inactive", "removing"); }
                    }
                    return new State().write();
                });
                return written && tail.get();
            });
        }
    }
}
