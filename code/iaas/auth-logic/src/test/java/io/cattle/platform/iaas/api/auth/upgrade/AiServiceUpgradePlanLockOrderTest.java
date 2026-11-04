package io.cattle.platform.iaas.api.auth.upgrade;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractApiRequestHandler;
import io.github.ibuildthecloud.gdapi.request.handler.write.DefaultReadWriteApiDelegate;
import io.github.ibuildthecloud.gdapi.request.handler.write.ReadWriteApiDelegate;
import io.github.ibuildthecloud.gdapi.url.NullUrlBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class AiServiceUpgradePlanLockOrderTest {
    private Object priorWait;
    @Before public void nativeLockWait() {
        ApiContext.newContext().setIdFormatter(new IdentityFormatter());
        var config = com.netflix.config.ConfigurationManager.getConfigInstance();
        priorWait = config.getProperty("default.lock.wait.millis"); config.setProperty("default.lock.wait.millis", 10000L);
    }
    @After public void cleanup() {
        ApiContext.remove(); var config = com.netflix.config.ConfigurationManager.getConfigInstance();
        if (priorWait == null) config.clearProperty("default.lock.wait.millis"); else config.setProperty("default.lock.wait.millis", priorWait);
    }

    @Test public void acceptedUpgradeNativeActionPrefetchAndConcurrentGetKeepApplicationBeforeRowLock() throws Exception {
        race("accepted");
    }

    @Test public void runningUpgradeNativeActionPrefetchAndConcurrentGetKeepApplicationBeforeRowLock() throws Exception {
        race("running");
    }

    private void race(String initialState) throws Exception {
        var f = new AiServiceUpgradePlanServiceTest.Fixture(); f.preview(); f.action("confirm"); f.action("apply");
        assertEquals(1, f.dispatched); assertEquals("accepted", f.document().get("status"));
        f.document().put("status", initialState);
        f.observed.add(Map.of("name", "service.upgrade", "ended", false));
        var request = f.request("apply", Map.of()); request.setId(f.id); request.setType(AiServiceUpgradePlanResourceManager.TYPE);
        request.setMethod("POST"); request.setUrlBuilder(new NullUrlBuilder());
        IdFormatter formatter = new IdentityFormatter();
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:upgrade-order-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        try (var connection = data.getConnection()) {
            connection.createStatement().execute("CREATE TABLE ai_service_upgrade_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT)");
        }
        var transactions = new DataSourceTransactionManager(data);
        CountDownLatch prefetched = new CountDownLatch(1), refreshAtSave = new CountDownLatch(1);
        AtomicReference<Thread> postThread = new AtomicReference<>(), refreshThread = new AtomicReference<>();
        AtomicInteger prefetchWrites = new AtomicInteger(), actionCalls = new AtomicInteger();
        var store = new AiServiceUpgradePlanStore() {
            @Override public void save(Map<String, Object> plan) {
                if (Thread.currentThread() == refreshThread.get()) refreshAtSave.countDown();
                if (Thread.currentThread() == postThread.get() && actionCalls.get() == 0) prefetchWrites.incrementAndGet();
                super.save(plan);
            }
        };
        store.json = new JacksonJsonMapper();
        store.setConfiguration(new DefaultConfiguration()
                .set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(data))).set(SQLDialect.H2));
        store.insert(f.document()); f.service.store = store;
        var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider()); f.service.locks = locks;
        var manager = new AiServiceUpgradePlanResourceManager() {
            @Override protected Object getByIdInternal(String type, String id, ListOptions options) {
                Object value = super.getByIdInternal(type, id, options);
                if (Thread.currentThread() == postThread.get()) {
                    assertTrue("Native action prefetch joins shipped outer write transaction", TransactionSynchronizationManager.isActualTransactionActive());
                    prefetched.countDown(); await(refreshAtSave);
                }
                return value;
            }
            @Override protected Object resourceActionInternal(Object target, ApiRequest action) {
                actionCalls.incrementAndGet(); return super.resourceActionInternal(target, action);
            }
        };
        manager.service = f.service;
        var target = new DefaultReadWriteApiDelegate();
        target.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest action) { manager.resourceAction(AiServiceUpgradePlanResourceManager.TYPE, action); }
        }));
        try (var spring = advised(target, transactions)) {
            var delegate = spring.getBean("writeDelegate", ReadWriteApiDelegate.class);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var post = workers.submit(() -> {
                    postThread.set(Thread.currentThread()); context(request, f.policy, formatter);
                    try { delegate.write(request); return true; } finally { ApiContext.remove(); }
                });
                var refresh = workers.submit(() -> {
                    await(prefetched); refreshThread.set(Thread.currentThread());
                    var read = new ApiRequest(request); read.setMethod("GET"); read.setAction(null); context(read, f.policy, formatter);
                    try { return manager.getById(AiServiceUpgradePlanResourceManager.TYPE, f.id, new ListOptions()) != null; }
                    finally { ApiContext.remove(); }
                });
                try {
                    assertTrue(refresh.get(5, TimeUnit.SECONDS)); assertTrue(post.get(5, TimeUnit.SECONDS));
                } catch (TimeoutException deadlock) {
                    System.out.println("UPGRADE_LOCK_ORDER_BARRIER prefetchWrites=" + prefetchWrites.get() + " actionCalls=" + actionCalls.get());
                    for (Thread thread : List.of(postThread.get(), refreshThread.get())) {
                        System.out.println("UPGRADE_LOCK_ORDER_THREAD " + (thread == postThread.get() ? "POST" : "GET") + " " + thread.getState());
                        for (StackTraceElement frame : thread.getStackTrace())
                            if (frame.getClassName().startsWith("io.cattle.platform.lock") || frame.getClassName().startsWith("org.h2"))
                                System.out.println(frame);
                    }
                    throw deadlock;
                }
                assertEquals(0, prefetchWrites.get()); assertEquals(1, actionCalls.get());
                assertEquals("running", store.load(f.id).get("status")); assertEquals(1, f.dispatched);
            } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS)); }
        }
    }

    @Test public void onlyExactNativeUpgradeActionMarkerUsesReadSnapshotAndOrdinaryGetStillObserves() {
        var f = new AiServiceUpgradePlanServiceTest.Fixture(); f.preview(); f.action("confirm"); f.action("apply");
        f.observed.add(Map.of("name", "service.upgrade", "ended", false));
        var request = f.request("apply", Map.of()); request.setId(f.id); request.setType(AiServiceUpgradePlanResourceManager.TYPE);
        request.setMethod("POST"); request.setUrlBuilder(new NullUrlBuilder()); context(request, f.policy, new IdentityFormatter());
        var manager = new AiServiceUpgradePlanResourceManager(); manager.service = spy(f.service);
        manager.getById(AiServiceUpgradePlanResourceManager.TYPE, f.id,
                ListOptions.forResourceActionTarget(request, AiServiceUpgradePlanResourceManager.TYPE, f.id));
        verify(manager.service).actionTarget(f.id, request); verify(manager.service, never()).get(f.id, request);
        assertEquals("accepted", f.document().get("status"));
        for (ListOptions options : List.of(new ListOptions(),
                ListOptions.forResourceActionTarget(new ApiRequest(request), AiServiceUpgradePlanResourceManager.TYPE, f.id),
                ListOptions.forResourceActionTarget(request, "service", f.id),
                ListOptions.forResourceActionTarget(request, AiServiceUpgradePlanResourceManager.TYPE, "other")))
            manager.getById(AiServiceUpgradePlanResourceManager.TYPE, f.id, options);
        assertEquals("running", f.document().get("status"));
        var beforeChangedAction = ListOptions.forResourceActionTarget(request, AiServiceUpgradePlanResourceManager.TYPE, f.id);
        request.setAction("cancel"); manager.getById(AiServiceUpgradePlanResourceManager.TYPE, f.id, beforeChangedAction);
        var beforeChangedMethod = ListOptions.forResourceActionTarget(request, AiServiceUpgradePlanResourceManager.TYPE, f.id);
        request.setMethod("GET"); request.setAction(null);
        // Body/query values cannot forge the internal ListOptions marker.
        request.setRequestObject(Map.of("mutationTarget", true)); request.setRequestParams(Map.of("mutationTarget", new String[] {"true"}));
        manager.getById(AiServiceUpgradePlanResourceManager.TYPE, f.id, beforeChangedMethod);
        manager.getById(AiServiceUpgradePlanResourceManager.TYPE, f.id, new ListOptions(request));
        verify(manager.service, times(7)).get(f.id, request);
        f.observed.clear(); f.observed.add(Map.of("name", "service.upgrade", "ended", true, "result", "FAILURE"));
        manager.getById(AiServiceUpgradePlanResourceManager.TYPE, f.id, new ListOptions());
        assertEquals("failed", f.document().get("status")); assertEquals("UpgradeProcessFailed", f.document().get("messageCode"));
    }

    @Test public void upgradeActionSnapshotRetainsOwnerProjectRoleVisibilityAndBoundKeyChecks() {
        var f = new AiServiceUpgradePlanServiceTest.Fixture(); f.preview();
        var request = f.request("apply", Map.of());
        var snapshot = f.service.actionTarget(f.id, request);
        assertFalse(snapshot.containsKey("snapshot")); assertFalse(snapshot.containsKey("credentialId"));
        assertFalse(snapshot.containsKey("body")); assertFalse(snapshot.containsKey("internalServiceId"));
        assertEquals("UpgradeNeedsConfirmation", assertThrows(ClientVisibleException.class,
                () -> f.service.action(f.id, request)).getCode()); assertEquals(0, f.dispatched);
        when(f.policy.getAuthenticatedAsAccountId()).thenReturn(43L);
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(f.id, request)).getStatus());
        when(f.policy.getAuthenticatedAsAccountId()).thenReturn(42L); when(f.policy.getAccountId()).thenReturn(78L);
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(f.id, request)).getStatus());
        when(f.policy.getAccountId()).thenReturn(77L); when(f.schema.getResourceMethods()).thenReturn(List.of());
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(f.id, request)).getCode());
        when(f.schema.getResourceMethods()).thenReturn(List.of("GET")); when(f.manager.getById(anyString(), anyString(), any())).thenReturn(null);
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(f.id, request)).getStatus());
        when(f.manager.getById(anyString(), anyString(), any())).thenReturn(f.target);
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(8, 42, "apiKey", 1, null));
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(f.id, request)).getStatus());
        f.document().put("credentialId", 8L); f.document().put("credentialRevision", 1L); f.document().put("credentialKind", "apiKey");
        assertEquals("preview", f.service.actionTarget(f.id, request).get("status"));
        verify(f.service.keyAuthorization).authorizeService(request, f.target, "read");
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(8, 42, "apiKey", 2, null));
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(f.id, request)).getStatus());
    }

    @Test public void upgradeActionReloadsSnapshotRoleAndKeyAuthorityAfterOperandLookupAndClaimsOnlyOnce() {
        var changed = new AiServiceUpgradePlanServiceTest.Fixture(); changed.preview();
        var request = changed.request("confirm", Map.of()); changed.service.actionTarget(changed.id, request);
        changed.target.setName("changed-after-operand");
        assertEquals("UpgradePlanStale", assertThrows(ClientVisibleException.class,
                () -> changed.service.action(changed.id, request)).getCode()); assertEquals(0, changed.dispatched);
        var role = new AiServiceUpgradePlanServiceTest.Fixture(); role.preview();
        role.service.actionTarget(role.id, role.request("confirm", Map.of())); when(role.schema.getResourceActions()).thenReturn(Map.of());
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class, () -> role.action("confirm")).getCode());
        var key = new AiServiceUpgradePlanServiceTest.Fixture();
        key.key = new ApiKeyCredentialContext(8, 42, "apiKey", 1, null); key.preview();
        key.key = null; key.action("confirm"); key.key = new ApiKeyCredentialContext(8, 42, "apiKey", 1, null);
        var apply = key.request("apply", Map.of()); key.service.actionTarget(key.id, apply);
        doThrow(new ClientVisibleException(403, "ApiKeyPolicyChanged")).when(key.service.keyAuthorization)
                .authorizeBound(anyMap(), eq(key.target), eq("upgrade"), eq(apply));
        assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class, () -> key.service.action(key.id, apply)).getCode());
        assertEquals(0, key.dispatched);
        var once = new AiServiceUpgradePlanServiceTest.Fixture(); once.preview(); once.action("confirm");
        once.service.actionTarget(once.id, once.request("apply", Map.of())); once.action("apply");
        once.service.actionTarget(once.id, once.request("apply", Map.of())); once.action("apply"); assertEquals(1, once.dispatched);
    }

    @Test public void concurrentNativeUpgradeAppliesShareOneDurableClaimAndDispatch() throws Exception {
        var f = new AiServiceUpgradePlanServiceTest.Fixture(); f.preview(); f.action("confirm");
        var request = f.request("apply", Map.of()); request.setId(f.id); request.setType(AiServiceUpgradePlanResourceManager.TYPE);
        request.setMethod("POST"); request.setUrlBuilder(new NullUrlBuilder());
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:upgrade-claim-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        try (var connection = data.getConnection()) {
            connection.createStatement().execute("CREATE TABLE ai_service_upgrade_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT)");
        }
        var transactions = new DataSourceTransactionManager(data); AtomicInteger claims = new AtomicInteger();
        var store = new AiServiceUpgradePlanStore() {
            @Override public boolean claim(Map<String, Object> plan) {
                boolean claimed = super.claim(plan); if (claimed) claims.incrementAndGet(); return claimed;
            }
        };
        store.json = new JacksonJsonMapper();
        store.setConfiguration(new DefaultConfiguration()
                .set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(data))).set(SQLDialect.H2));
        store.insert(f.document()); f.service.store = store;
        var locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider()); f.service.locks = locks;
        CountDownLatch bothPrefetched = new CountDownLatch(2);
        var manager = new AiServiceUpgradePlanResourceManager() {
            @Override protected Object getByIdInternal(String type, String id, ListOptions options) {
                Object value = super.getByIdInternal(type, id, options); bothPrefetched.countDown(); await(bothPrefetched); return value;
            }
        };
        manager.service = f.service; var target = new DefaultReadWriteApiDelegate();
        target.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest action) { manager.resourceAction(AiServiceUpgradePlanResourceManager.TYPE, action); }
        }));
        try (var spring = advised(target, transactions)) {
            var delegate = spring.getBean("writeDelegate", ReadWriteApiDelegate.class); var workers = Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Callable<Boolean> apply = () -> {
                    var ownRequest = new ApiRequest(request); context(ownRequest, f.policy, new IdentityFormatter());
                    try { delegate.write(ownRequest); return true; } finally { ApiContext.remove(); }
                };
                var first = workers.submit(apply); var second = workers.submit(apply);
                assertTrue(first.get(5, TimeUnit.SECONDS)); assertTrue(second.get(5, TimeUnit.SECONDS));
                assertEquals(1, claims.get()); assertEquals(1, f.dispatched);
                assertEquals("accepted", store.load(f.id).get("status"));
            } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS)); }
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("Upgrade lock ordering barrier", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void context(ApiRequest request, Policy actor, IdFormatter formatter) {
        ApiContext.newContext().setApiRequest(request); ApiContext.getContext().setPolicy(actor);
        ApiContext.getContext().setIdFormatter(formatter);
    }
    private static GenericApplicationContext advised(ReadWriteApiDelegate target, DataSourceTransactionManager transactions) throws Exception {
        Path config = null;
        for (Path at = Path.of("").toAbsolutePath(); at != null; at = at.getParent()) {
            Path candidate = at.resolve("code/packaging/app-config/src/main/resources/META-INF/cattle/legacy/spring-legacy-context.xml");
            if (Files.isRegularFile(candidate)) { config = candidate; break; }
        }
        assertNotNull("Shipped API transaction advice", config);
        String shipped = Files.readString(config);
        int start = shipped.indexOf("<tx:advice id=\"ApiDelegateAdvice\"");
        String advice = shipped.substring(start, shipped.indexOf("</tx:advice>", start) + "</tx:advice>".length());
        start = shipped.indexOf("<aop:advisor advice-ref=\"ApiDelegateAdvice\"");
        String advisor = shipped.substring(start, shipped.indexOf("/>", start) + 2);
        String xml = "<beans xmlns=\"http://www.springframework.org/schema/beans\" xmlns:tx=\"http://www.springframework.org/schema/tx\" "
                + "xmlns:aop=\"http://www.springframework.org/schema/aop\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:schemaLocation=\"http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd "
                + "http://www.springframework.org/schema/tx https://www.springframework.org/schema/tx/spring-tx.xsd "
                + "http://www.springframework.org/schema/aop https://www.springframework.org/schema/aop/spring-aop.xsd\">"
                + advice + "<aop:config>" + advisor + "</aop:config></beans>";
        var spring = new GenericApplicationContext();
        spring.registerBean("CoreTransactionManager", DataSourceTransactionManager.class, () -> transactions);
        spring.registerBean("writeDelegate", ReadWriteApiDelegate.class, () -> target);
        new XmlBeanDefinitionReader(spring).loadBeanDefinitions(new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)));
        spring.refresh(); return spring;
    }
}
