package io.cattle.platform.iaas.api.auth.ai.catalog;

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
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
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

public class AiCatalogLaunchLockOrderTest {
    private Object priorWait;
    @Before public void nativeLockWait() {
        var config = com.netflix.config.ConfigurationManager.getConfigInstance();
        priorWait = config.getProperty("default.lock.wait.millis"); config.setProperty("default.lock.wait.millis", 10000L);
    }
    @After public void cleanup() {
        ApiContext.remove(); var config = com.netflix.config.ConfigurationManager.getConfigInstance();
        if (priorWait == null) config.clearProperty("default.lock.wait.millis"); else config.setProperty("default.lock.wait.millis", priorWait);
    }

    @Test public void nativeActionPrefetchAndConcurrentNativeGetCompleteWithApplicationBeforeRowLock() throws Exception {
        var f = new AiCatalogLaunchWorkflowTest.Fixture(false); f.preview();
        String id = (String) f.store.plan.get("id"); IdFormatter formatter = ApiContext.getContext().getIdFormatter();
        f.request.setId(id); f.request.setAction("confirm"); f.request.setRequestObject(Map.of()); f.request.setUrlBuilder(new NullUrlBuilder());
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:catalog-order-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (var connection = data.getConnection()) {
            connection.createStatement().execute("CREATE TABLE ai_catalog_stack_launch_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,account_id BIGINT,stack_id BIGINT UNIQUE,expires_at BIGINT)");
        }
        var transactions = new DataSourceTransactionManager(data);
        CountDownLatch prefetched = new CountDownLatch(1), refreshAtRow = new CountDownLatch(1);
        AtomicReference<Thread> postThread = new AtomicReference<>(), refreshThread = new AtomicReference<>();
        AtomicInteger prefetchRowLocks = new AtomicInteger(), actionCalls = new AtomicInteger();
        var store = new AiCatalogLaunchPlanStore() {
            @Override public Map<String, Object> loadForUpdate(String key) {
                if (Thread.currentThread() == refreshThread.get()) refreshAtRow.countDown();
                if (Thread.currentThread() == postThread.get() && actionCalls.get() == 0) prefetchRowLocks.incrementAndGet();
                return super.loadForUpdate(key);
            }
        };
        store.json = new JacksonJsonMapper(); store.transactions = transactions;
        store.setConfiguration(new DefaultConfiguration()
                .set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(data))).set(SQLDialect.H2));
        store.insert(f.store.plan); f.service.store = store;
        LockManagerImpl locks = new LockManagerImpl(); locks.setLockProvider(new InMemoryLockProvider()); f.service.locks = locks;
        var manager = new AiCatalogLaunchPlanResourceManager() {
            @Override protected Object getByIdInternal(String type, String key, ListOptions options) {
                Object value = super.getByIdInternal(type, key, options);
                if (Thread.currentThread() == postThread.get()) { prefetched.countDown(); await(refreshAtRow); }
                return value;
            }
            @Override protected Object resourceActionInternal(Object target, ApiRequest request) {
                actionCalls.incrementAndGet(); return super.resourceActionInternal(target, request);
            }
        };
        manager.service = f.service;
        var target = new DefaultReadWriteApiDelegate();
        target.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest request) { manager.resourceAction(AiCatalogLaunchInput.PLAN, request); }
        }));
        try (var spring = advised(target, transactions)) {
            var delegate = spring.getBean("writeDelegate", ReadWriteApiDelegate.class);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var post = workers.submit(() -> {
                    postThread.set(Thread.currentThread()); context(f.request, f.actor, formatter);
                    try { delegate.write(f.request); return true; } finally { ApiContext.remove(); }
                });
                var refresh = workers.submit(() -> {
                    await(prefetched); refreshThread.set(Thread.currentThread());
                    var read = new ApiRequest(f.request); read.setMethod("GET"); read.setAction(null); context(read, f.actor, formatter);
                    try { return manager.getById(AiCatalogLaunchInput.PLAN, id, new ListOptions()) != null; }
                    finally { ApiContext.remove(); }
                });
                assertTrue(refresh.get(5, TimeUnit.SECONDS)); assertTrue(post.get(5, TimeUnit.SECONDS));
                assertEquals(0, prefetchRowLocks.get()); assertEquals(1, actionCalls.get());
                assertEquals("approved", store.load(id).get("confirmationState"));
            } finally { workers.shutdownNow(); }
        }
    }

    @Test public void onlyExactInternalActionOperandUsesSnapshotAndOrdinaryGetStillChecksSource() throws Exception {
        var f = new AiCatalogLaunchWorkflowTest.Fixture(false); f.preview(); String id = (String) f.store.plan.get("id");
        f.request.setId(id); f.request.setAction("confirm"); f.request.setUrlBuilder(new NullUrlBuilder());
        var manager = new AiCatalogLaunchPlanResourceManager(); manager.service = spy(f.service);
        manager.getById(AiCatalogLaunchInput.PLAN, id, ListOptions.forResourceActionTarget(f.request, AiCatalogLaunchInput.PLAN, id));
        verify(manager.service).actionTarget(id, f.request); verify(manager.service, never()).get(id, f.request);
        for (ListOptions options : List.of(new ListOptions(),
                ListOptions.forResourceActionTarget(new ApiRequest(f.request), AiCatalogLaunchInput.PLAN, id),
                ListOptions.forResourceActionTarget(f.request, "stack", id),
                ListOptions.forResourceActionTarget(f.request, AiCatalogLaunchInput.PLAN, "other")))
            manager.getById(AiCatalogLaunchInput.PLAN, id, options);
        verify(manager.service, times(4)).get(id, f.request);
        f.digest = "b".repeat(64); f.request.setMethod("GET"); f.request.setAction(null);
        manager.getById(AiCatalogLaunchInput.PLAN, id, new ListOptions());
        assertEquals("failed", f.store.plan.get("status")); assertEquals("CatalogLaunchSourceChanged", f.store.plan.get("reason"));
    }

    @Test public void snapshotRetainsOwnerProjectRoleKeyBindingAndDoesNotAuthorizeUnconfirmedApply() throws Exception {
        var f = new AiCatalogLaunchWorkflowTest.Fixture(false); f.preview(); String id = (String) f.store.plan.get("id");
        f.request.setId(id); f.request.setAction("apply"); f.request.setRequestObject(Map.of());
        var safe = f.service.actionTarget(id, f.request);
        assertFalse(f.json.writeValueAsString(safe).contains("privateEnvelope"));
        assertEquals("CatalogLaunchNeedsConfirmation", assertThrows(ClientVisibleException.class,
                () -> f.service.action(id, f.request)).getCode()); assertEquals(0, f.store.claims);
        when(f.actor.getAuthenticatedAsAccountId()).thenReturn(43L);
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(id, f.request)).getStatus());
        when(f.actor.getAuthenticatedAsAccountId()).thenReturn(42L); when(f.actor.getAccountId()).thenReturn(6L);
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> f.service.actionTarget(id, f.request)).getStatus());
        when(f.actor.getAccountId()).thenReturn(5L);
        ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(7, 42, "apiKey", 1, null));
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.service.actionTarget(id, f.request)).getCode());
        var role = new AiCatalogLaunchWorkflowTest.Fixture(false); role.preview(); role.request.setAction("confirm");
        ((SchemaImpl) role.schemas.getSchema(AiCatalogLaunchInput.PLAN)).setResourceActions(Map.of());
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> role.service.actionTarget((String) role.store.plan.get("id"), role.request)).getCode());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue("Catalog lock ordering barrier", latch.await(5, TimeUnit.SECONDS)); }
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
