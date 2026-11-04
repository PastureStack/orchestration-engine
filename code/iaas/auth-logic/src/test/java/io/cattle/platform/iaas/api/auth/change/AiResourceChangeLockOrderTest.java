package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.impl.LockManagerImpl;
import io.cattle.platform.lock.provider.impl.InMemoryLockProvider;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
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

public class AiResourceChangeLockOrderTest {
    private Object priorWait;
    @Before public void nativeLockWait() {
        var config = com.netflix.config.ConfigurationManager.getConfigInstance();
        priorWait = config.getProperty("default.lock.wait.millis"); config.setProperty("default.lock.wait.millis", 10000L);
    }
    @After public void cleanup() {
        ApiContext.remove(); var config = com.netflix.config.ConfigurationManager.getConfigInstance();
        if (priorWait == null) config.clearProperty("default.lock.wait.millis"); else config.setProperty("default.lock.wait.millis", priorWait);
    }

    @Test public void nativeActionReceiptPrefetchAndConcurrentGetDoNotInvertObservationWriteLocks() throws Exception {
        var f = new AiResourceChangePlanServiceTest.Fixture(); f.preview(false);
        Map<String, Object> plan = f.documents.get(f.id);
        plan.putAll(Map.of("status", "running", "confirmationState", "approved", "operationId", f.id,
                "dispatchAcknowledged", true, "acceptedAt", f.service.clock.millis()));
        var request = f.request("apply", false); request.setUrlBuilder(new NullUrlBuilder());
        IdFormatter formatter = ApiContext.getContext().getIdFormatter();
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:change-order-" + UUID.randomUUID()
                + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000");
        try (var connection = data.getConnection()) {
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(128))");
        }
        var transactions = new DataSourceTransactionManager(data);
        CountDownLatch prefetched = new CountDownLatch(1), refreshAtWrite = new CountDownLatch(1), actionAttempt = new CountDownLatch(1);
        AtomicReference<Thread> postThread = new AtomicReference<>(), refreshThread = new AtomicReference<>();
        AtomicInteger prefetchWrites = new AtomicInteger(), actionCalls = new AtomicInteger();
        var store = new AiResourceChangePlanStore() {
            @Override public void save(Map<String, Object> value) {
                if (Thread.currentThread() == refreshThread.get()) refreshAtWrite.countDown();
                if (Thread.currentThread() == postThread.get() && actionCalls.get() == 0) prefetchWrites.incrementAndGet();
                super.save(value);
            }
        };
        store.json = new JacksonJsonMapper();
        store.setConfiguration(new DefaultConfiguration()
                .set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(data))).set(SQLDialect.H2));
        store.insert(plan); store.save(plan); f.service.store = store;
        LockManagerImpl locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider()); f.service.locks = locks;
        var manager = new AiResourceChangePlanResourceManager() {
            @Override protected Object getByIdInternal(String type, String key, ListOptions options) {
                Object value = super.getByIdInternal(type, key, options);
                if (Thread.currentThread() == postThread.get()) { prefetched.countDown(); await(refreshAtWrite); }
                return value;
            }
            @Override protected Object resourceActionInternal(Object target, ApiRequest action) {
                actionCalls.incrementAndGet(); actionAttempt.countDown(); return super.resourceActionInternal(target, action);
            }
        };
        manager.service = f.service;
        var target = new DefaultReadWriteApiDelegate();
        target.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest action) { manager.resourceAction(AiResourceChangePlanResourceManager.TYPE, action); }
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
                    try { return manager.getById(AiResourceChangePlanResourceManager.TYPE, f.id, new ListOptions()) != null; }
                    finally { ApiContext.remove(); }
                });
                await(actionAttempt);
                try { assertTrue(refresh.get(3, TimeUnit.SECONDS)); assertTrue(post.get(3, TimeUnit.SECONDS)); }
                catch (TimeoutException blocked) {
                    boolean postAtApplicationLock = java.util.Arrays.stream(postThread.get().getStackTrace())
                            .anyMatch(frame -> frame.getClassName().endsWith("StandardLock") && frame.getMethodName().equals("lock"));
                    boolean getAtDatabaseRow = java.util.Arrays.stream(refreshThread.get().getStackTrace())
                            .anyMatch(frame -> frame.getClassName().startsWith("org.h2.mvstore.tx.Transaction"));
                    throw new AssertionError("OFFLINE_NATIVE_CHANGE_LOCK_INVERSION prefetchWrites=" + prefetchWrites.get()
                            + " postAtApplicationLock=" + postAtApplicationLock + " getAtDatabaseRow=" + getAtDatabaseRow, blocked);
                }
                assertEquals(0, prefetchWrites.get()); assertEquals(1, actionCalls.get());
                assertEquals("running", store.load(f.id).get("status")); assertEquals(0, f.claims); assertEquals(0, f.dispatched);
            } finally { workers.shutdownNow(); }
        }
    }

    @Test public void exactSnapshotRetainsOwnerProjectKeyAndReceiptPermissionsWhileGetStillObservesAndApplyRequiresConfirmation() throws Exception {
        var f = new AiResourceChangePlanServiceTest.Fixture(); f.preview(false);
        f.documents.get(f.id).putAll(Map.of("status", "accepted", "operationId", f.id,
                "dispatchAcknowledged", true, "acceptedAt", f.service.clock.millis()));
        var request = f.request("apply", false); request.setUrlBuilder(new NullUrlBuilder()); ApiContext.getContext().setApiRequest(request);
        var manager = new AiResourceChangePlanResourceManager(); manager.service = spy(f.service);
        ListOptions exact = ListOptions.forResourceActionTarget(request, AiResourceChangePlanResourceManager.TYPE, f.id);
        manager.getById(AiResourceChangePlanResourceManager.TYPE, f.id, exact);
        assertEquals("accepted", f.documents.get(f.id).get("status"));
        verify(manager.service).actionTarget(f.id, request); verify(manager.service, never()).get(f.id, request);
        manager.getById(AiResourceChangePlanResourceManager.TYPE, f.id,
                ListOptions.forResourceActionTarget(new ApiRequest(request), AiResourceChangePlanResourceManager.TYPE, f.id));
        verify(manager.service).get(f.id, request); assertEquals("running", f.documents.get(f.id).get("status"));
        when(f.policy.getAuthenticatedAsAccountId()).thenReturn(43L);
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(f.id, request)).getStatus());
        when(f.policy.getAuthenticatedAsAccountId()).thenReturn(42L); when(f.policy.getAccountId()).thenReturn(88L);
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(f.id, request)).getStatus());
        when(f.policy.getAccountId()).thenReturn(77L);
        when(f.domain.receiptTarget(any(), any(), anyMap())).thenThrow(new ClientVisibleException(403, "PermissionDenied"));
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.service.actionTarget(f.id, request)).getCode());
        var key = new AiResourceChangePlanServiceTest.Fixture(); key.preview(true);
        var wrongKey = key.request("apply", true);
        ApiKeyCredentialContext.attach(wrongKey, new ApiKeyCredentialContext(8, 42, "apiKeyRestricted", 3, null));
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> key.service.actionTarget(key.id, wrongKey)).getStatus());
        ApiKeyCredentialContext.attach(wrongKey, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 4, null));
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> key.service.actionTarget(key.id, wrongKey)).getStatus());
        var pending = new AiResourceChangePlanServiceTest.Fixture(); pending.preview(false);
        var apply = pending.request("apply", false); pending.service.actionTarget(pending.id, apply);
        assertEquals("ResourceChangeNeedsConfirmation", assertThrows(ClientVisibleException.class,
                () -> pending.service.action(pending.id, apply)).getCode()); assertEquals(0, pending.claims); assertEquals(0, pending.dispatched);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("Resource-change lock ordering barrier", latch.await(5, TimeUnit.SECONDS)); }
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
