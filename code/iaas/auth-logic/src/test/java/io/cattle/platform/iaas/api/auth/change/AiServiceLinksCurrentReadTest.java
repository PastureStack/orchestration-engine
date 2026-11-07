package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.model.ProcessInstance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.core.model.tables.records.ProcessInstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.cattle.platform.lock.provider.impl.StandardLock;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.servicediscovery.api.lock.ServiceDiscoveryServiceSetLinksLock;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class AiServiceLinksCurrentReadTest {
    @Test public void nativeUpgradeRowThenLinksCannotDeadlockTypedLinksThenEndpointRows() throws Exception {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:links-nowait-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE;LOCK_TIMEOUT=10000");
        var sql = new JdbcTemplate(source);
        var dsl = DSL.using(source, SQLDialect.H2); dsl.createSchema("cattle").execute();
        dsl.createTable(SERVICE).columns(SERVICE.fields()).execute();
        dsl.insertInto(SERVICE).set(SERVICE.ID, 2L).set(SERVICE.UUID, "target").set(SERVICE.KIND, "service")
                .set(SERVICE.STATE, "active").set(SERVICE.ACCOUNT_ID, 5L).set(SERVICE.STACK_ID, 8L)
                .set(SERVICE.SYSTEM, false).set(SERVICE.SKIP, false).execute();
        var domain = new AiResourceChangeDomain();
        domain.setConfiguration(new DefaultConfiguration().set(new TransactionAwareDataSourceProxy(source)).set(SQLDialect.H2));
        var upgradeThread = new AtomicReference<Thread>(); var upgradeAttempt = new CountDownLatch(1);
        var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider() {
            @Override protected StandardLock createLock(LockDefinition definition) {
                return new StandardLock(definition, new ReentrantLock() {
                    @Override public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
                        if (Thread.currentThread() == upgradeThread.get()) upgradeAttempt.countDown();
                        return super.tryLock(timeout, unit);
                    }
                });
            }
        });
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var targetLocked = new CountDownLatch(1); var typedHeld = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        Object previous = com.netflix.config.ConfigurationManager.getConfigInstance().getProperty("default.lock.wait.millis");
        com.netflix.config.ConfigurationManager.getConfigInstance().setProperty("default.lock.wait.millis", 10000L);
        try {
            var upgrade = workers.submit(() -> tx.execute(status -> {
                sql.queryForObject("select id from cattle.service where id=2 for update", Long.class); targetLocked.countDown(); await(typedHeld);
                upgradeThread.set(Thread.currentThread());
                return ServiceDiscoveryServiceSetLinksLock.withLock(locks, 7, () -> true);
            })); await(targetLocked);
            var typed = workers.submit(() -> tx.execute(status -> ServiceDiscoveryServiceSetLinksLock.withLock(locks, 7, () -> {
                typedHeld.countDown(); await(upgradeAttempt);
                var failure = assertThrows(ClientVisibleException.class, () -> domain.lockServiceLinkRow(2));
                status.setRollbackOnly(); return failure.getCode();
            })));
            assertEquals("ResourceChangeTargetBusy", typed.get(4, TimeUnit.SECONDS));
            assertTrue("Native upgrade continues after typed rollback; neither side waits in the inverse order", upgrade.get(4, TimeUnit.SECONDS));
            assertEquals("target", tx.execute(status -> ((Service) domain.lockServiceLinkRow(2)).getUuid()));
        } finally {
            typedHeld.countDown(); workers.shutdownNow();
            if (previous == null) com.netflix.config.ConfigurationManager.getConfigInstance().clearProperty("default.lock.wait.millis");
            else com.netflix.config.ConfigurationManager.getConfigInstance().setProperty("default.lock.wait.millis", previous);
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("real row/SETLINKS deterministic barrier", latch.await(4, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    @Test public void shippedSpringReadSuspendsAnOldRrViewAndRejectsAnAlreadyScheduledMapRemoval() throws Exception {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:links-current-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var sql = new JdbcTemplate(source); sql.execute("create table related_links (id bigint primary key, consumed bigint)");
        sql.update("insert into related_links values (7, 9)");
        var locked = service(7, "source"); var prepared = new AtomicInteger();
        var domain = new AiResourceChangeDomain() {
            @Override public Object visible(ApiRequest request, AiResourceChangeDescriptor descriptor, String id) { return locked; }
            @Override public Map<String, Object> serviceLinksSnapshot(Object target) {
                assertSame(locked, target); assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                assertEquals(Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED), TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                return Map.of("consumed", sql.queryForObject("select consumed from related_links where id=7", Long.class));
            }
            @Override public Map<String, Object> prepare(ApiRequest request, AiResourceChangeDescriptor descriptor, String id,
                    Map<String, Object> parameters, Map<String, Object> bound) { prepared.incrementAndGet(); return parameters; }
        };
        domain.json = new JacksonJsonMapper(); domain.objects = mock(ObjectManager.class);
        var map = new ServiceConsumeMapRecord(); map.setId(70L); map.setServiceId(7L); map.setConsumedServiceId(9L);
        when(domain.objects.find(ServiceConsumeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(map));
        var transactions = new DataSourceTransactionManager(source);
        try (var spring = new GenericApplicationContext()) {
            spring.registerBean("CoreTransactionManager", DataSourceTransactionManager.class, () -> transactions);
            spring.registerBean("domain", AiResourceChangeDomain.class, () -> domain);
            new XmlBeanDefinitionReader(spring).loadBeanDefinitions(new ByteArrayResource(advice().getBytes(StandardCharsets.UTF_8)));
            spring.refresh(); var advised = spring.getBean(AiResourceChangeDomain.class);
            var outer = new TransactionTemplate(transactions); outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            outer.executeWithoutResult(status -> {
                Connection original = DataSourceUtils.getConnection(source);
                assertEquals(Long.valueOf(9), sql.queryForObject("select consumed from related_links where id=7", Long.class));
                try (Connection writer = source.getConnection(); var statement = writer.createStatement()) {
                    statement.executeUpdate("update related_links set consumed=11 where id=7");
                } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
                assertEquals("Outer RR snapshot is actually stale", Long.valueOf(9), sql.queryForObject("select consumed from related_links where id=7", Long.class));
                var plan = Map.<String, Object>of("resourceType", "service", "operation", "update_links", "resourceId", "1s7", "parameters", Map.of("serviceIds", List.of()));
                Map<String, Object> current = advised.currentServiceLinksState(new ApiRequest(null, null), plan, locked);
                assertEquals(11L, ((Map<?, ?>) current.get("snapshot")).get("consumed")); assertEquals(1, prepared.get());
                assertSame(original, DataSourceUtils.getConnection(source));
                assertEquals(Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ), TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                var removal = new ProcessInstanceRecord(); removal.setId(100L); removal.setProcessName("serviceconsumemap.remove");
                when(domain.objects.find(ProcessInstance.class, "resourceType", "serviceConsumeMap", "resourceId", "70",
                        "processName", "serviceconsumemap.remove", "endTime", null)).thenReturn(List.of(removal));
                assertEquals("ResourceChangeTargetBusy", assertThrows(ClientVisibleException.class,
                        () -> advised.currentServiceLinksState(new ApiRequest(null, null), plan, locked)).getCode());
                assertEquals("Pending remove must precede native preflight/dispatch", 1, prepared.get());
            });
        }
    }

    @Test public void endpointRowsUseOneSortedOrderAndARealRemovingOrReboundTargetRejectsBeforeCas() {
        List<Long> order = new ArrayList<>(); var target = service(2, "target"); var source = service(7, "source");
        var domain = new AiResourceChangeDomain() {
            @Override protected Object lockServiceLinkRow(long id) { order.add(id); return id == 2 ? target : source; }
        };
        domain.json = new JacksonJsonMapper();
        Map<String, Object> pin = Map.of("id", 2, "uuid", "target", "accountId", 5, "stackId", 8, "kind", "service");
        var plan = Map.<String, Object>of("internalResourceId", 7, "referenceSnapshot", Map.of("before", List.of(pin), "after", List.of(pin)));
        assertSame(source, domain.lockServiceLinkTargets(plan)); assertEquals(List.of(2L, 7L), order);
        target.setState("removing");
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class, () -> domain.lockServiceLinkTargets(plan)).getCode());
        target.setState("active"); target.setUuid("replacement");
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class, () -> domain.lockServiceLinkTargets(plan)).getCode());
    }

    private static ServiceRecord service(long id, String uuid) {
        var row = new ServiceRecord(); row.setId(id); row.setUuid(uuid); row.setKind("service"); row.setState("active"); row.setAccountId(5L); row.setStackId(8L); return row;
    }
    private static String advice() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("code/packaging/app-config/src/main/resources/META-INF/cattle/legacy/spring-legacy-context.xml"))) root = root.getParent();
        assertNotNull(root); String shipped = Files.readString(root.resolve("code/packaging/app-config/src/main/resources/META-INF/cattle/legacy/spring-legacy-context.xml"));
        int start = shipped.indexOf("<tx:advice id=\"ServiceLinksCurrentReadAdvice\"");
        String tx = shipped.substring(start, shipped.indexOf("</tx:advice>", start) + "</tx:advice>".length());
        start = shipped.indexOf("<aop:advisor advice-ref=\"ServiceLinksCurrentReadAdvice\"");
        String advisor = shipped.substring(start, shipped.indexOf("/>", start) + 2);
        return "<beans xmlns=\"http://www.springframework.org/schema/beans\" xmlns:tx=\"http://www.springframework.org/schema/tx\" xmlns:aop=\"http://www.springframework.org/schema/aop\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd http://www.springframework.org/schema/tx https://www.springframework.org/schema/tx/spring-tx.xsd http://www.springframework.org/schema/aop https://www.springframework.org/schema/aop/spring-aop.xsd\">"
                + tx + "<aop:config>" + advisor + "</aop:config></beans>";
    }
}
