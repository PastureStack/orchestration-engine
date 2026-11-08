package io.cattle.platform.iaas.api.port;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.HostTable.HOST;
import static io.cattle.platform.core.model.tables.InstanceHostMapTable.INSTANCE_HOST_MAP;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.PortTable.PORT;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.app.IaasApiConfig;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.constants.PortConstants;
import io.cattle.platform.iaas.api.filter.port.PortUpdateValidationFilter;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.Lock;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.provider.LockProvider;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.h2.jdbcx.JdbcDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.After;
import org.junit.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Real local H2 transactions + two independent request threads, no product server or external DB. */
public class PortUpdateOwnerConcurrencyTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void twoRunningWritersOnOneHostCannotBothClaimOnePublishedPort() throws Exception {
        race(false, false, false);
    }

    @Test public void hiddenOtherProjectOnTheSamePhysicalHostBlocksWithoutIdentityDisclosure() throws Exception {
        race(true, true, false);
    }

    @Test public void independentEngineLockProvidersStillSerializeOnThePhysicalHostDatabaseRows() throws Exception {
        race(true, true, true);
    }

    private void race(boolean foreignProject, boolean logicalHostAlias, boolean independentProvider) throws Exception {
        Fixture f = new Fixture(); f.seed(1, 5, 10, 8001); f.seed(2, foreignProject ? 6 : 5, logicalHostAlias ? 20 : 10, 8002);
        PortUpdateService secondOwner = independentProvider ? f.independentOwner() : f.owner;
        ApiRequest first = f.request(1), second = independentProvider ? new ApiRequest(null, null) : f.request(2);
        CountDownLatch firstSaveReturned = new CountDownLatch(1), allowCommit = new CountDownLatch(1);
        AtomicInteger savesA = new AtomicInteger(), savesB = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> {
                Thread.currentThread().setName("port-writer-one"); actor(5);
                try {
                    TransactionTemplate outer = new TransactionTemplate(f.owner.transactions);
                    outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    return outer.execute(status -> {
                        f.owner.update(first, 1, 8080, () -> { savesA.incrementAndGet(); f.write(1, 8080); return "saved"; });
                        assertTrue(f.held(PortConstants.ALLOCATION_PORT_LOCK_PREFIX + "tcp.8080"));
                        firstSaveReturned.countDown(); await(allowCommit); return "committed";
                    });
                } finally { ApiContext.remove(); }
            });
            assertTrue(firstSaveReturned.await(5, TimeUnit.SECONDS));
            var b = pool.submit(() -> {
                Thread.currentThread().setName("port-writer-two"); actor(foreignProject ? 6 : 5);
                try {
                    return assertThrows(ClientVisibleException.class, () -> secondOwner.update(second, 2, 8080,
                            () -> { savesB.incrementAndGet(); f.write(2, 8080); return "saved"; }));
                } finally { ApiContext.remove(); }
            });
            assertTrue(f.secondAtConstraint.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> b.get(150, TimeUnit.MILLISECONDS));
            assertEquals(0, savesB.get()); allowCommit.countDown();
            assertEquals("committed", a.get(8, TimeUnit.SECONDS));
            ClientVisibleException denied = b.get(8, TimeUnit.SECONDS);
            assertEquals("PortUnavailable", denied.getCode()); assertEquals(422, denied.getStatus()); assertNull(denied.getDetail());
            assertFalse(denied.toString().contains("HIDDEN-PROJECT-CANARY"));
            assertEquals(1, savesA.get()); assertEquals(0, savesB.get());
            assertEquals(8080, f.port(1).getPublicPort().intValue()); assertEquals(8002, f.port(2).getPublicPort().intValue());
            assertEquals("running", f.source(1).getState()); assertEquals("running", f.source(2).getState());
        } finally { allowCommit.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void staleUiBeforeAndParentOrChildRebindingFailBeforeAnySave() {
        Fixture f = new Fixture(); f.seed(1, 5, 10, 8001); actor(5); ApiRequest stale = f.request(1);
        f.owner.update(f.request(1), 1, 9090, () -> { f.write(1, 9090); return null; });
        AtomicInteger writes = new AtomicInteger();
        assertEquals("PortUpdateConflict", assertThrows(ClientVisibleException.class,
                () -> f.owner.update(stale, 1, 8080, () -> { writes.incrementAndGet(); return null; })).getCode());
        ApiRequest parent = f.request(1); f.sql.update(INSTANCE).set(INSTANCE.UUID, "replacement-source").where(INSTANCE.ID.eq(1L)).execute();
        assertEquals("PortUpdateConflict", assertThrows(ClientVisibleException.class,
                () -> f.owner.update(parent, 1, 8080, () -> { writes.incrementAndGet(); return null; })).getCode());
        ApiRequest child = f.request(1); f.sql.update(PORT).set(PORT.UUID, "replacement-child").where(PORT.ID.eq(1L)).execute();
        assertEquals("PortUpdateConflict", assertThrows(ClientVisibleException.class,
                () -> f.owner.update(child, 1, 8080, () -> { writes.incrementAndGet(); return null; })).getCode());
        assertEquals(0, writes.get()); assertEquals(9090, f.port(1).getPublicPort().intValue());
    }

    @Test public void missingBeforeForeignAccountAndDeniedNativeSaveNeverGrantOrPersist() {
        Fixture f = new Fixture(); f.seed(1, 5, 10, 8001); actor(5); AtomicInteger saves = new AtomicInteger();
        ApiRequest typed = new ApiRequest(null, null); PortUpdateService.requireBefore(typed);
        assertEquals("PortUpdatePreconditionRequired", assertThrows(ClientVisibleException.class,
                () -> f.owner.update(typed, 1, 8080, () -> { saves.incrementAndGet(); return null; })).getCode());
        f.owner.update(new ApiRequest(null, null), 1, 8080, () -> { f.write(1, 8080); return null; });
        assertEquals(8080, f.port(1).getPublicPort().intValue());
        actor(6);
        assertEquals(404, assertThrows(ClientVisibleException.class,
                () -> f.owner.update(new ApiRequest(null, null), 1, 8080, () -> { saves.incrementAndGet(); return null; })).getStatus());
        actor(5);
        for (String code : List.of("KeyScopeDenied", "PermissionDenied"))
            assertEquals(code, assertThrows(ClientVisibleException.class,
                    () -> f.owner.update(new ApiRequest(null, null), 1, 9090, () -> {
                        f.write(1, 9090); throw new ClientVisibleException(403, code);
                    })).getCode());
        assertEquals(0, saves.get()); assertEquals(8080, f.port(1).getPublicPort().intValue());
        f.seed(2, 6, 20, 9090);
        assertEquals("PortUnavailable", assertThrows(ClientVisibleException.class,
                () -> f.owner.update(new ApiRequest(null, null), 1, 9090, () -> { saves.incrementAndGet(); return null; })).getCode());
        assertEquals(0, saves.get());
    }

    @Test public void explicitNonoverlappingBindingsAndProtocolKeepTheirNativeMeaning() {
        Fixture f = new Fixture(); f.seed(1, 5, 10, 8001); f.seed(2, 6, 10, 8080); actor(5);
        f.sql.update(PORT).set(PORT.DATA, Map.of("fields", Map.of("bindAddress", "127.0.0.1"))).where(PORT.ID.eq(1L)).execute();
        f.sql.update(PORT).set(PORT.DATA, Map.of("fields", Map.of("bindAddress", "192.0.2.1"))).where(PORT.ID.eq(2L)).execute();
        f.owner.update(f.request(1), 1, 8080, () -> { f.write(1, 8080); return null; });
        assertEquals(8080, f.port(1).getPublicPort().intValue());
        f.sql.update(PORT).set(PORT.PROTOCOL, "udp").where(PORT.ID.eq(1L)).execute();
        f.owner.update(f.request(1), 1, null, () -> { f.write(1, null); return null; });
        assertNull(f.port(1).getPublicPort());
    }

    @Test public void hiddenHostNetworkPrivatePortIsARealGlobalPublishedConflict() {
        Fixture f = new Fixture(); f.seed(1, 5, 10, 8001); f.seed(2, 6, 20, 8002); actor(5);
        f.sql.update(INSTANCE).set(INSTANCE.DATA, Map.of("fields", Map.of("networkMode", "host"))).where(INSTANCE.ID.eq(2L)).execute();
        f.sql.update(PORT).set(PORT.PRIVATE_PORT, 8080).setNull(PORT.PUBLIC_PORT).where(PORT.ID.eq(2L)).execute();
        AtomicInteger writes = new AtomicInteger();
        ClientVisibleException failure = assertThrows(ClientVisibleException.class,
                () -> f.owner.update(f.request(1), 1, 8080, () -> { writes.incrementAndGet(); return null; }));
        assertEquals("PortUnavailable", failure.getCode()); assertNull(failure.getDetail()); assertEquals(0, writes.get());
        f.sql.update(PORT).set(PORT.PROTOCOL, "udp").where(PORT.ID.eq(1L)).execute();
        f.owner.update(f.request(1), 1, 8080, () -> { f.write(1, 8080); return null; });
        assertEquals(8080, f.port(1).getPublicPort().intValue());
    }

    @Test public void existingProjectPreflightReadsNativeScopedFieldsAndRetainsItsProjectProjection() {
        Fixture f = new Fixture(); f.seed(1, 5, 10, 8001); f.seed(2, 6, 20, 8002);
        for (org.jooq.Table<?> table : List.<org.jooq.Table<?>>of(
                io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP,
                io.cattle.platform.core.model.tables.ServiceTable.SERVICE, io.cattle.platform.core.model.tables.StackTable.STACK))
            f.sql.createTable(table).columns(java.util.Arrays.stream(table.fields())
                    .map(field -> DSL.field(field.getUnqualifiedName(), field.getDataType().nullable(true))).toList()).execute();
        f.sql.update(PORT).set(PORT.DATA, Map.of("fields", Map.of("bindAddress", "127.0.0.1"))).where(PORT.ID.eq(1L)).execute();
        PortPreflightDaoImpl dao = new PortPreflightDaoImpl(); dao.setConfiguration(f.configuration);
        var ordinary = dao.getPortOwners(5); assertEquals(1, ordinary.size());
        assertEquals("127.0.0.1", ordinary.getFirst().bindAddress); assertEquals(8001, ordinary.getFirst().publicPort.intValue());
        f.sql.update(INSTANCE).set(INSTANCE.DATA, Map.of("fields", Map.of("networkMode", "host"))).where(INSTANCE.ID.eq(1L)).execute();
        f.sql.update(PORT).setNull(PORT.PUBLIC_PORT).where(PORT.ID.eq(1L)).execute();
        var host = dao.getPortOwners(5); assertEquals(1, host.size());
        assertEquals("0.0.0.0", host.getFirst().bindAddress); assertEquals(80, host.getFirst().publicPort.intValue());
    }

    @Test public void theActualUiHeaderIsConditionalAndMalformedOrStaleInputNeverSaves() throws Exception {
        Fixture f = new Fixture(); f.seed(1, 5, 10, 8001); actor(5);
        String before = f.owner.json.writeValueAsString(PortUpdateService.before(f.port(1), f.source(1)));
        ApiRequest original = ui(before); AtomicInteger writes = new AtomicInteger();
        f.owner.update(original, 1, 8080, () -> { writes.incrementAndGet(); f.write(1, 8080); return null; });
        assertEquals(1, writes.get());
        assertEquals("PortUpdateConflict", assertThrows(ClientVisibleException.class,
                () -> f.owner.update(ui(before), 1, 9090, () -> { writes.incrementAndGet(); return null; })).getCode());
        for (String invalid : List.of("{}", "[", before.replace("\"protocol\":\"tcp\"", "\"protocol\":\"other\""), "x".repeat(2049)))
            assertThrows(ClientVisibleException.class,
                    () -> f.owner.update(ui(invalid), 1, 9090, () -> { writes.incrementAndGet(); return null; }));
        ApiRequest malformedTyped = ui("{}"); PortUpdateService.attachBefore(malformedTyped, f.port(1), f.source(1));
        assertEquals("PortUpdatePreconditionInvalid", assertThrows(ClientVisibleException.class,
                () -> f.owner.update(malformedTyped, 1, 9090, () -> { writes.incrementAndGet(); return null; })).getCode());
        assertEquals(1, writes.get()); assertEquals(8080, f.port(1).getPublicPort().intValue());
    }

    @Test public void nativeFilterDelegatesTheOriginalManagerAndPreservesItsKeyDenial() throws Exception {
        Fixture f = new Fixture(); f.seed(1, 5, 10, 8001); actor(5);
        PortUpdateValidationFilter filter = new PortUpdateValidationFilter();
        for (String name : List.of("updates", "objects")) {
            var field = PortUpdateValidationFilter.class.getDeclaredField(name); field.setAccessible(true);
            field.set(filter, name.equals("updates") ? f.owner : f.owner.objects);
        }
        ResourceManager manager = mock(ResourceManager.class); ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(Map.of("publicPort", 8080));
        when(manager.update(eq("port"), eq("1"), same(request))).thenThrow(new ClientVisibleException(403, "KeyScopeDenied"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () -> filter.update("port", "1", request, manager)).getCode());
        verify(manager).update("port", "1", request); assertEquals(8001, f.port(1).getPublicPort().intValue());
        doAnswer(call -> { f.write(1, 8080); return "saved-native"; }).when(manager).update("port", "1", request);
        assertEquals("saved-native", filter.update("port", "1", request, manager));
        assertEquals(8080, f.port(1).getPublicPort().intValue());
        request.setRequestObject(Map.of("name", "unchanged-native-field"));
        doReturn("ordinary-native-update").when(manager).update("port", "1", request);
        assertEquals("ordinary-native-update", filter.update("port", "1", request, manager));
    }

    @Test public void typedDispatchReusesOnlyItsActiveServerOwnedNativeScope() throws Exception {
        Fixture f = new Fixture(); f.seed(1, 5, 10, 8001); actor(5); ApiRequest original = f.request(1);
        f.owner.withLocks(original, f.port(1), 8080, () -> {
            ApiRequest nativeRequest = f.request(1); PortUpdateService.transferScope(original, nativeRequest);
            return f.owner.update(nativeRequest, 1, 8080, () -> { f.write(1, 8080); return null; });
        });
        verify(f.owner.locks.getLockProvider(), times(2)).getLock(any());
        assertEquals(8080, f.port(1).getPublicPort().intValue());
        Lock poison = mock(Lock.class), good = mock(Lock.class);
        LockDefinition definition = mock(LockDefinition.class);
        when(definition.getLockId()).thenReturn(PortConstants.ALLOCATION_PORT_LOCK_PREFIX + "tcp.9090");
        when(poison.getLockDefinition()).thenReturn(definition);
        RuntimeException releaseFailure = new IllegalStateException("checked-release-unknown");
        doThrow(releaseFailure).when(poison).unlockChecked();
        var release = PortUpdateService.class.getDeclaredMethod("release", List.class); release.setAccessible(true);
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> release.invoke(f.owner, List.of(good, poison)));
        assertSame(releaseFailure, error.getCause());
        verify(f.owner.locks.getLockProvider(), never()).releaseLock(poison);
        verify(f.owner.locks.getLockProvider()).releaseLock(good);
        assertEquals("PortUpdateLockUnavailable", assertThrows(ClientVisibleException.class,
                () -> f.owner.withLocks(new ApiRequest(null, null), f.port(1), 9090, () -> null)).getCode());

        Fixture g = new Fixture(); g.seed(1, 5, 10, 8001);
        Lock acquired = mock(Lock.class), rejected = mock(Lock.class); when(acquired.getLockDefinition()).thenReturn(definition);
        LockProvider provider = g.owner.locks.getLockProvider();
        doReturn(acquired, rejected).when(provider).getLock(any());
        RuntimeException primary = new IllegalStateException("acquire-failed"), secondary = new IllegalStateException("release-failed");
        doThrow(primary).when(rejected).lock(); doThrow(secondary).when(acquired).unlockChecked();
        assertSame(primary, assertThrows(IllegalStateException.class,
                () -> g.owner.withLocks(new ApiRequest(null, null), g.port(1), 9090, () -> null)));
        assertArrayEquals(new Throwable[] {secondary}, primary.getSuppressed());
        verify(g.owner.locks.getLockProvider(), never()).releaseLock(acquired);
    }

    private static ApiRequest ui(String before) {
        HttpServletRequest http = mock(HttpServletRequest.class); when(http.getHeader(PortUpdateService.BEFORE_HEADER)).thenReturn(before);
        return new ApiRequest(new ApiServletContext(http, null, null), null);
    }

    @Test public void productionNativeBeansAndLockNamesStaySharedWithoutCreateDeleteOrGrantAdapters() throws Exception {
        assertTrue(IaasApiConfig.class.getDeclaredMethod("PortUpdateService").isAnnotationPresent(Bean.class));
        assertTrue(IaasApiConfig.class.getDeclaredMethod("PortUpdateValidationFilter").isAnnotationPresent(Bean.class));
        assertArrayEquals(new String[] {"port"}, new PortUpdateValidationFilter().getTypes());
        assertEquals("ALLOCATE.CONSTRAINT.PORT.tcp.8080", new io.cattle.platform.allocator.lock.AllocateConstraintLock(
                io.cattle.platform.allocator.lock.AllocateConstraintLock.Type.PORT, "tcp.8080").getLockId());
        var source = new io.cattle.platform.core.model.tables.records.InstanceRecord(); source.setId(5L);
        assertEquals(PortConstants.INSTANCE_PORTS_LOCK_PREFIX + "5", new io.cattle.platform.process.lock.InstancePortsLock(source).getLockId());
    }

    private static void actor(long id) {
        Policy policy = mock(Policy.class); when(policy.getAccountId()).thenReturn(id); when(policy.getAuthenticatedAsAccountId()).thenReturn(1L);
        ApiContext.newContext().setPolicy(policy);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    static class Fixture {
        final PortUpdateService owner = new PortUpdateService(); final DSLContext sql;
        final org.jooq.Configuration configuration;
        final ConcurrentHashMap<String, ReentrantLock> keyed = new ConcurrentHashMap<>();
        final CountDownLatch secondAtConstraint = new CountDownLatch(1);
        Fixture() {
            JdbcDataSource source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:port-owner-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000");
            configuration = new DefaultConfiguration().set(SQLDialect.H2).set(new Settings().withRenderSchema(false))
                    .set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(source)));
            sql = DSL.using(configuration); owner.setConfiguration(configuration); owner.transactions = new DataSourceTransactionManager(source);
            owner.json = new JacksonJsonMapper(); owner.objects = mock(ObjectManager.class);
            // Keep real field names/types and row locks; unrelated schema defaults are not this fixture's subject.
            for (org.jooq.Table<?> table : List.<org.jooq.Table<?>>of(PORT, INSTANCE, INSTANCE_HOST_MAP, HOST))
                sql.createTable(table).columns(java.util.Arrays.stream(table.fields())
                        .map(field -> DSL.field(field.getUnqualifiedName(), field.getDataType().nullable(true))).toList()).execute();
            when(owner.objects.loadResource(eq(Port.class), anyLong())).thenAnswer(call -> port(call.getArgument(1)));
            when(owner.objects.loadResource(eq(Port.class), anyString())).thenAnswer(call -> port(Long.parseLong(call.getArgument(1))));
            configureLocks(owner, keyed);
        }
        PortUpdateService independentOwner() {
            PortUpdateService peer = new PortUpdateService(); peer.setConfiguration(configuration);
            peer.transactions = owner.transactions; peer.objects = owner.objects; peer.json = owner.json;
            configureLocks(peer, new ConcurrentHashMap<>()); return peer;
        }
        void configureLocks(PortUpdateService service, ConcurrentHashMap<String, ReentrantLock> local) {
            service.locks = mock(LockManager.class); LockProvider provider = mock(LockProvider.class);
            when(service.locks.getLockProvider()).thenReturn(provider);
            when(provider.getLock(any())).thenAnswer(call -> lock(call.getArgument(0), local));
            when(service.locks.lock(any(LockDefinition.class), any(LockCallback.class))).thenAnswer(call -> {
                Lock lock = lock(call.getArgument(0), local); lock.lock();
                try { return ((LockCallback<?>) call.getArgument(1)).doWithLock(); } finally { lock.unlock(); }
            });
        }
        Lock lock(LockDefinition definition, ConcurrentHashMap<String, ReentrantLock> local) {
            ReentrantLock delegate = local.computeIfAbsent(definition.getLockId(), key -> new ReentrantLock());
            return new Lock() {
                @Override public boolean tryLock() { return delegate.tryLock(); }
                @Override public void lock() {
                    if (Thread.currentThread().getName().equals("port-writer-two")
                            && definition.getLockId().startsWith(PortConstants.ALLOCATION_PORT_LOCK_PREFIX))
                        secondAtConstraint.countDown();
                    delegate.lock();
                }
                @Override public void unlock() { delegate.unlock(); }
                @Override public LockDefinition getLockDefinition() { return definition; }
            };
        }
        boolean held(String key) { return keyed.get(key).isHeldByCurrentThread(); }
        void seed(long id, long project, long hostId, int port) {
            if (!sql.fetchExists(sql.selectFrom(HOST).where(HOST.ID.eq(hostId))))
                sql.insertInto(HOST).set(HOST.ID, hostId).set(HOST.UUID, "host-" + hostId).set(HOST.ACCOUNT_ID, project)
                        .set(HOST.PHYSICAL_HOST_ID, 100L).set(HOST.STATE, "active").set(HOST.KIND, "docker").set(HOST.DATA, Map.of()).execute();
            sql.insertInto(INSTANCE).set(INSTANCE.ID, id).set(INSTANCE.UUID, "source-" + id).set(INSTANCE.ACCOUNT_ID, project)
                    .set(INSTANCE.NAME, "HIDDEN-PROJECT-CANARY").set(INSTANCE.STATE, "running").set(INSTANCE.KIND, "container")
                    .set(INSTANCE.DATA, Map.of("fields", Map.of("networkMode", "bridge"))).execute();
            sql.insertInto(PORT).set(PORT.ID, id).set(PORT.UUID, "child-" + id).set(PORT.ACCOUNT_ID, project).set(PORT.INSTANCE_ID, id)
                    .set(PORT.PUBLIC_PORT, port).set(PORT.PRIVATE_PORT, 80).set(PORT.PROTOCOL, "tcp")
                    .set(PORT.STATE, "active").set(PORT.DATA, Map.of()).execute();
            sql.insertInto(INSTANCE_HOST_MAP).set(INSTANCE_HOST_MAP.ID, id).set(INSTANCE_HOST_MAP.INSTANCE_ID, id)
                    .set(INSTANCE_HOST_MAP.HOST_ID, hostId).set(INSTANCE_HOST_MAP.STATE, "active").set(INSTANCE_HOST_MAP.DATA, Map.of()).execute();
        }
        Port port(long id) { return sql.selectFrom(PORT).where(PORT.ID.eq(id)).fetchOne(); }
        Instance source(long id) { return sql.selectFrom(INSTANCE).where(INSTANCE.ID.eq(id)).fetchOne(); }
        ApiRequest request(long id) {
            ApiRequest request = new ApiRequest(null, null); PortUpdateService.attachBefore(request, port(id), source(id)); return request;
        }
        void write(long id, Integer value) { sql.update(PORT).set(PORT.PUBLIC_PORT, value).where(PORT.ID.eq(id)).execute(); }
    }
}
