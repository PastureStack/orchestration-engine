package io.cattle.platform.servicediscovery.dao.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ServiceConsumeMapTable.SERVICE_CONSUME_MAP;
import io.cattle.platform.core.addon.ServiceLink;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.cattle.platform.object.ObjectManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.provider.impl.StandardLock;
import io.cattle.platform.servicediscovery.api.lock.ServiceDiscoveryServiceSetLinksLock;
import io.cattle.platform.servicediscovery.process.LoadBalancerServiceCreatePostListener;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.lock.exception.FailedToAcquireLockException;
import java.util.Set;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.Test;

public class ServiceLinksMutationCurrentReadTest {
    @Test public void originalLbListenerAlsoRetriesDescendingSourceContentionThroughTheCommonOwner() throws Exception {
        var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider());
        var highHeld = new CountDownLatch(1); var lowHeld = new CountDownLatch(1);
        var nativeDao = new ServiceConsumeMapDaoImpl() {
            @Override protected ServiceConsumeMap createServiceLinkImpl(Service source, ServiceLink link) { return null; }
        }; nativeDao.lockManager = locks;
        var listener = new LoadBalancerServiceCreatePostListener() {
            @Override protected void setLoadBalancerServiceLinkedServicesIds(ProcessState state, ProcessInstance process, Service service,
                    Set<Long> oldIds, Set<Long> newIds, List<Service> targets) { if (service.getId() == 9) { highHeld.countDown(); await(lowHeld); newIds.add(20L); } }
            @Override protected void addServiceLink(Service service, Long targetId) { nativeDao.createServiceLink(service, new ServiceLink(targetId, null, null)); }
        };
        var objects = mock(ObjectManager.class); inject(listener, "objectManager", objects); inject(listener, "lockManager", locks);
        var source = new ServiceRecord(); source.setId(7L); source.setKind("service"); source.setAccountId(5L);
        var high = new ServiceRecord(); high.setId(9L); var low = new ServiceRecord(); low.setId(2L);
        when(objects.find(Service.class, io.cattle.platform.core.model.tables.ServiceTable.SERVICE.ACCOUNT_ID, 5L,
                io.cattle.platform.core.model.tables.ServiceTable.SERVICE.REMOVED, null,
                io.cattle.platform.core.model.tables.ServiceTable.SERVICE.KIND, "loadBalancerService")).thenReturn(List.of(high, low));
        var state = mock(ProcessState.class); when(state.getResource()).thenReturn(source);
        // Actual Spring synchronization/commit tail; no database is needed for this native callback-order test.
        var tx = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        }); var workers = Executors.newFixedThreadPool(2);
        Object previous = com.netflix.config.ConfigurationManager.getConfigInstance().getProperty("default.lock.wait.millis");
        com.netflix.config.ConfigurationManager.getConfigInstance().setProperty("default.lock.wait.millis", 10000L);
        try {
            var upper = workers.submit(() -> tx.execute(status -> {
                assertThrows(FailedToAcquireLockException.class, () -> listener.handle(state, null)); status.setRollbackOnly(); return true;
            }));
            if (!highHeld.await(4, TimeUnit.SECONDS)) { upper.get(1, TimeUnit.SECONDS); fail("listener did not enter its first source owner"); }
            var lower = workers.submit(() -> ServiceDiscoveryServiceSetLinksLock.withLock(locks, 2, () -> {
                lowHeld.countDown(); return ServiceDiscoveryServiceSetLinksLock.withLock(locks, 9, () -> true);
            }));
            assertTrue(upper.get(4, TimeUnit.SECONDS)); assertTrue(lower.get(4, TimeUnit.SECONDS));
        } finally {
            lowHeld.countDown(); workers.shutdownNow();
            if (previous == null) com.netflix.config.ConfigurationManager.getConfigInstance().clearProperty("default.lock.wait.millis");
            else com.netflix.config.ConfigurationManager.getConfigInstance().setProperty("default.lock.wait.millis", previous);
        }
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    @Test public void directCloneDaoWaitsForTheWholeSetOwnerBeforeTakingItsEdgeLock() throws Exception {
        var clone = new AtomicReference<Thread>(); var attempted = new CountDownLatch(1);
        var inside = new CountDownLatch(1); var release = new CountDownLatch(1);
        List<String> cloneOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        var locks = new LockManagerImpl();
        locks.setLockProvider(new InMemoryLockProvider() {
            @Override protected StandardLock createLock(LockDefinition definition) {
                return new StandardLock(definition, new ReentrantLock() {
                    @Override public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
                        if (Thread.currentThread() == clone.get()) { cloneOrder.add(definition.getLockId()); attempted.countDown(); }
                        return super.tryLock(timeout, unit);
                    }
                    @Override public boolean tryLock() {
                        if (Thread.currentThread() == clone.get()) { cloneOrder.add(definition.getLockId()); attempted.countDown(); }
                        return super.tryLock();
                    }
                });
            }
        });
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var dao = new ServiceConsumeMapDaoImpl() {
            @Override protected ServiceConsumeMap createServiceLinkImpl(Service source, ServiceLink link) { writes.incrementAndGet(); return null; }
        }; dao.lockManager = locks;
        var source = new ServiceRecord(); source.setId(7L);
        var workers = Executors.newFixedThreadPool(2);
        Object previous = com.netflix.config.ConfigurationManager.getConfigInstance().getProperty("default.lock.wait.millis");
        com.netflix.config.ConfigurationManager.getConfigInstance().setProperty("default.lock.wait.millis", 10000L);
        try {
            var fullSet = workers.submit(() -> ServiceDiscoveryServiceSetLinksLock.withLock(locks, 7, () -> {
                inside.countDown(); await(release); return true;
            })); await(inside);
            var direct = workers.submit(() -> { clone.set(Thread.currentThread()); dao.createServiceLink(source, new ServiceLink(9L, null, null)); return true; });
            await(attempted);
            assertEquals("The direct DAO/clone caller must not take edge/map locks ahead of the shared set owner", List.of("SERVICE.SETLINKS.7"), cloneOrder);
            assertEquals(0, writes.get()); release.countDown();
            assertTrue(fullSet.get(4, TimeUnit.SECONDS)); assertTrue(direct.get(4, TimeUnit.SECONDS)); assertEquals(1, writes.get());
            assertEquals(List.of("SERVICE.SETLINKS.7", "SERVICE.7.SERVICE.9null"), cloneOrder);
        } finally {
            release.countDown(); workers.shutdownNow();
            if (previous == null) com.netflix.config.ConfigurationManager.getConfigInstance().clearProperty("default.lock.wait.millis");
            else com.netflix.config.ConfigurationManager.getConfigInstance().setProperty("default.lock.wait.millis", previous);
        }
    }

    @Test public void bulkCloneTakesEverySourceMutexInSortedOrderBeforeAnyEdge() {
        List<String> order = new ArrayList<>(); var locks = new LockManagerImpl();
        locks.setLockProvider(new InMemoryLockProvider() {
            @Override public io.cattle.platform.lock.Lock getLock(LockDefinition definition) { order.add(definition.getLockId()); return super.getLock(definition); }
        });
        var dao = new ServiceConsumeMapDaoImpl() {
            @Override protected ServiceConsumeMap createServiceLinkImpl(Service source, ServiceLink link) { return null; }
        }; dao.lockManager = locks; dao.objectManager = mock(ObjectManager.class);
        for (long id : List.of(2L, 9L)) { var source = new ServiceRecord(); source.setId(id); when(dao.objectManager.loadResource(Service.class, id)).thenReturn(source); }
        var first = new ServiceLink(20L, null, null); first.setConsumingServiceId(9L);
        var second = new ServiceLink(20L, null, null); second.setConsumingServiceId(2L);
        dao.createServiceLinks(List.of(first, second));
        assertEquals(List.of("SERVICE.SETLINKS.2", "SERVICE.SETLINKS.9", "SERVICE.SETLINKS.9", "SERVICE.9.SERVICE.20null", "SERVICE.SETLINKS.2", "SERVICE.2.SERVICE.20null"), order);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("direct DAO deterministic barrier", latch.await(4, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    @Test public void mutationUsesCurrentRowsWhilePreMutexValidationRemainsNonLocking() {
        List<String> queries = new ArrayList<>(); var dao = new ServiceConsumeMapDaoImpl();
        var current = new ServiceConsumeMapRecord(); current.setId(8L); current.setServiceId(2L); current.setConsumedServiceId(9L); current.setState("active");
        var stale = new ServiceConsumeMapRecord(); stale.setId(7L); stale.setServiceId(2L); stale.setConsumedServiceId(9L); stale.setState("active");
        var connection = new MockConnection(context -> {
            String sql = context.sql().toLowerCase(); queries.add(sql);
            assertTrue(sql.contains("where")); assertTrue(sql.contains("service_id")); assertTrue(sql.contains("for update"));
            assertEquals(2L, ((Number) context.bindings()[0]).longValue());
            var rows = DSL.using(SQLDialect.H2).newResult(SERVICE_CONSUME_MAP); rows.add(current);
            return new MockResult[]{new MockResult(1, rows)};
        });
        dao.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
        dao.objectManager = mock(ObjectManager.class);
        when(dao.objectManager.find(ServiceConsumeMap.class, SERVICE_CONSUME_MAP.SERVICE_ID, 2L,
                SERVICE_CONSUME_MAP.CONSUMED_SERVICE_ID, 9L, SERVICE_CONSUME_MAP.REMOVED, null)).thenReturn(List.of(stale));
        var source = new ServiceRecord(); source.setId(2L); source.setState("active");
        var target = new ServiceRecord(); target.setId(9L); target.setState("active");
        when(dao.objectManager.reload(source)).thenReturn(source); when(dao.objectManager.loadResource(Service.class, 9L)).thenReturn(target);
        var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider()); dao.lockManager = locks;
        assertSame(stale, dao.findNonRemovedMap(2, 9, null)); assertTrue(queries.isEmpty());
        assertEquals(Long.valueOf(8), dao.findConsumedMapsForMutation(2).getFirst().getId());
        assertEquals(Long.valueOf(8), dao.createServiceLink(source, new ServiceLink(9L, null, null)).getId());
        assertEquals(2, queries.size()); verify(dao.objectManager, never()).create(eq(ServiceConsumeMap.class), anyMap());
    }

    @Test public void fullSetExactPairCreateKeepsLegacyNullLookupAndSupportsAliasToPlain() {
        var dataSource = new org.h2.jdbcx.JdbcDataSource(); dataSource.setURL("jdbc:h2:mem:named-pair-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var sql = DSL.using(dataSource, SQLDialect.H2); sql.createSchema("cattle").execute();
        sql.createTable(SERVICE_CONSUME_MAP).columns(SERVICE_CONSUME_MAP.fields()).execute();
        for (var entry : java.util.Map.of(7L, "red", 8L, "blue").entrySet()) sql.insertInto(SERVICE_CONSUME_MAP)
                .set(SERVICE_CONSUME_MAP.ID, entry.getKey()).set(SERVICE_CONSUME_MAP.KIND, "serviceConsumeMap")
                .set(SERVICE_CONSUME_MAP.UUID, "named-" + entry.getKey()).set(SERVICE_CONSUME_MAP.SERVICE_ID, 2L)
                .set(SERVICE_CONSUME_MAP.CONSUMED_SERVICE_ID, 9L).set(SERVICE_CONSUME_MAP.NAME, entry.getValue())
                .set(SERVICE_CONSUME_MAP.STATE, "active").execute();
        var dao = new ServiceConsumeMapDaoImpl(); dao.setConfiguration(new DefaultConfiguration().set(dataSource).set(SQLDialect.H2));
        dao.objectManager = mock(ObjectManager.class); var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider()); dao.lockManager = locks;
        var source = new ServiceRecord(); source.setId(2L); source.setState("active");
        var target = new ServiceRecord(); target.setId(9L); target.setState("active");
        when(dao.objectManager.reload(source)).thenReturn(source); when(dao.objectManager.loadResource(Service.class, 9L)).thenReturn(target);
        var legacy = dao.createServiceLink(source, new ServiceLink(9L, null, null));
        assertTrue(java.util.Set.of(7L, 8L).contains(legacy.getId()));
        assertEquals(Long.valueOf(7), dao.createServiceLinkExactPair(source, new ServiceLink(9L, "red", null)).getId());
        assertEquals(Long.valueOf(8), dao.createServiceLinkExactPair(source, new ServiceLink(9L, "blue", null)).getId());
        var plain = new ServiceConsumeMapRecord(); plain.setId(10L); plain.setServiceId(2L); plain.setConsumedServiceId(9L); plain.setState("active");
        when(dao.objectManager.convertToPropertiesFor(eq(ServiceConsumeMap.class), anyMap())).thenAnswer(call -> call.getArgument(1));
        when(dao.objectManager.create(eq(ServiceConsumeMap.class), anyMap())).thenReturn(plain);
        assertSame(plain, dao.createServiceLinkExactPair(source, new ServiceLink(9L, null, null)));
        verify(dao.objectManager, times(1)).create(eq(ServiceConsumeMap.class), anyMap());
        sql.insertInto(SERVICE_CONSUME_MAP).set(SERVICE_CONSUME_MAP.ID, 10L).set(SERVICE_CONSUME_MAP.KIND, "serviceConsumeMap")
                .set(SERVICE_CONSUME_MAP.UUID, "plain").set(SERVICE_CONSUME_MAP.SERVICE_ID, 2L)
                .set(SERVICE_CONSUME_MAP.CONSUMED_SERVICE_ID, 9L).set(SERVICE_CONSUME_MAP.STATE, "active").execute();
        assertEquals(Long.valueOf(10), dao.createServiceLinkExactPair(source, new ServiceLink(9L, "", null)).getId());
    }

}
