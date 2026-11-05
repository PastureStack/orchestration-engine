package io.cattle.platform.app;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import io.cattle.platform.api.resource.AbstractObjectResourceManager;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.StackTable;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchBinding;
import io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchPlanStore;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.impl.DefaultObjectManager;
import io.cattle.platform.object.impl.TransactionDelegate;
import io.cattle.platform.object.lifecycle.ObjectLifeCycleHandler;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.postinit.ObjectPostInstantiationHandler;
import io.cattle.platform.object.process.StandardProcess;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.h2.jdbcx.JdbcDataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

public class AiCatalogLaunchObjectLifecycleWiringTest {
    @Test public void actualSpringFactoryLazilyBindsNativeStackBeforeCreateProcessScheduling() throws Exception {
        try (Fixture f = new Fixture(true)) {
            assertEquals(0, f.handlerConstructions.get());
            DefaultObjectManager objects = f.context.getBean(DefaultObjectManager.class);
            Map<String, Object> plan = f.plan("catalog-one");
            AiCatalogLaunchBinding binding = f.context.getBean(AiCatalogLaunchBinding.class);
            NativeManager manager = new NativeManager(objects, f.store(), plan);
            Stack created = envelope(binding, plan, () -> manager.createStack("catalog-one"));
            assertEquals(1, f.handlerConstructions.get());
            assertSame("Stable singleton handlers resolve once", objects.getLifeCycleHandlers(), objects.getLifeCycleHandlers());
            assertEquals(created.getId(), plan.get("internalStackId"));
            assertEquals(created.getId().longValue(), ((Number) f.store().forStack(created.getId()).get("internalStackId")).longValue());
            assertEquals(1, f.store().saves);
            assertEquals(List.of("bound-before-schedule"), manager.scheduled);
            manager.expected = null;
            manager.createStack("ordinary-after-catalog");
            assertEquals("Ordinary creates do not write a Catalog plan", 1, f.store().saves);
        }
    }

    @Test public void emptyHandlersAndLegacyListOverrideRemainValidWithoutEagerDependencyCycles() throws Exception {
        try (Fixture f = new Fixture(false)) {
            DefaultObjectManager objects = f.context.getBean(DefaultObjectManager.class);
            assertTrue(objects.getLifeCycleHandlers().isEmpty());
            NativeManager manager = new NativeManager(objects, null, null);
            manager.createStack("zero-handlers");
            AtomicInteger events = new AtomicInteger();
            ObjectLifeCycleHandler handler = new ObjectLifeCycleHandler() {
                @Override public <T> T onEvent(LifeCycleEvent event, T instance, Class<?> type, Map<String, Object> properties) {
                    events.incrementAndGet(); return instance;
                }
            };
            objects.setLifeCycleHandlers(List.of(handler));
            manager.createStack("legacy-list");
            assertEquals(1, events.get());
            objects.setLifeCycleHandlerSupplier(List::of);
            manager.createStack("supplier-overrides-list");
            assertEquals(1, events.get());
            objects.setLifeCycleHandlers(null);
            manager.createStack("legacy-null-overrides-supplier");
            assertNull(objects.getLifeCycleHandlers());
        }
    }

    @Test public void nativeCreateBindsExactlyOnceAndNestedFailureRestoresThreadLocalEnvelope() throws Exception {
        try (Fixture f = new Fixture(true)) {
            DefaultObjectManager objects = f.context.getBean(DefaultObjectManager.class);
            AiCatalogLaunchBinding binding = f.context.getBean(AiCatalogLaunchBinding.class);
            Map<String, Object> outer = f.plan("outer");
            NativeManager manager = new NativeManager(objects, f.store(), outer);
            envelope(binding, outer, () -> {
                assertThrows(IllegalStateException.class, () -> envelope(binding, f.plan("inner"), () -> {
                    throw new IllegalStateException("Synthetic failure before native create");
                }));
                return manager.createStack("outer");
            });
            assertEquals(1, f.store().saves);
            assertThrows(io.github.ibuildthecloud.gdapi.exception.ClientVisibleException.class,
                    () -> envelope(binding, outer, () -> manager.createStack("outer")));
            assertEquals("A bound plan cannot schedule another native create", 1, manager.scheduled.size());
            assertEquals(1, f.store().saves);
            manager.expected = null;
            manager.createStack("ordinary-after-exception");
            assertEquals("ThreadLocal cleared after success and exception", 1, f.store().saves);
        }
    }

    private static Stack envelope(AiCatalogLaunchBinding binding, Map<String, Object> plan, Supplier<Stack> action) {
        try {
            var method = AiCatalogLaunchBinding.class.getDeclaredMethod("create", Map.class, Supplier.class);
            method.setAccessible(true);
            return Stack.class.cast(method.invoke(binding, plan, action));
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    static class CountingStore extends AiCatalogLaunchPlanStore {
        int saves;
        @Override public void save(Map<String, Object> plan) { super.save(plan); saves++; }
    }

    static class NativeManager extends AbstractObjectResourceManager {
        final CountingStore store;
        final List<String> scheduled = new ArrayList<>();
        Map<String, Object> expected;
        NativeManager(DefaultObjectManager objects, CountingStore store, Map<String, Object> expected) {
            setObjectManager(objects); this.store = store; this.expected = expected;
        }
        Stack createStack(String name) { return (Stack) createAndScheduleObject(StackRecord.class, Map.of("name", name)); }
        @Override protected void scheduleProcess(StandardProcess process, Object resource, Map<String, Object> data) {
            assertEquals(StandardProcess.CREATE, process);
            if (expected != null) {
                Stack stack = (Stack) resource;
                assertEquals(stack.getId(), expected.get("internalStackId"));
                assertNotNull("The real plan SQL row must bind before scheduling", store.forStack(stack.getId()));
                scheduled.add("bound-before-schedule");
            } else scheduled.add("ordinary");
        }
        @Override protected Object removeFromStore(String type, String id, Object obj, ApiRequest request) {
            throw new UnsupportedOperationException();
        }
        @Override protected Object listInternal(SchemaFactory schemas, String type, Map<Object, Object> criteria,
                io.github.ibuildthecloud.gdapi.model.ListOptions options) {
            throw new UnsupportedOperationException();
        }
        @Override public String[] getTypes() { return new String[] { "stack" }; }
        @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { Stack.class }; }
    }

    static class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final AtomicInteger handlerConstructions = new AtomicInteger();
        final io.cattle.platform.db.jooq.config.Configuration configuration = new io.cattle.platform.db.jooq.config.Configuration();
        Fixture(boolean withBinding) throws Exception {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:lifecycle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            configuration.set(source); configuration.set(SQLDialect.H2);
            configuration.settings().setRenderSchema(false);
            DSL.using(configuration).createTable(StackTable.STACK).columns(StackTable.STACK.fields()).execute();
            DSL.using(configuration).execute("create table ai_catalog_stack_launch_plan "
                    + "(id varchar primary key, state varchar, document clob, stack_id bigint)");
            context.getBeanFactory().registerSingleton("configuration", configuration);
            context.getBeanFactory().registerSingleton("CoreTransactionManager", new DataSourceTransactionManager(source));
            context.getBeanFactory().registerSingleton("metadata", mock(ObjectMetaDataManager.class));
            context.getBeanFactory().registerSingleton("transactionDelegate", mock(TransactionDelegate.class));
            context.registerBean(JacksonJsonMapper.class);
            context.registerBean(ObjectPostInstantiationHandler.class, () -> new ObjectPostInstantiationHandler() {
                @Override public <T> T postProcess(T instance, Class<?> type, Map<String, Object> properties) {
                    Stack row = (Stack) instance;
                    row.setAccountId(5L); row.setKind("stack"); row.setName((String) properties.get("name"));
                    row.setUuid(UUID.randomUUID().toString()); row.setState("creating"); row.setSystem(false);
                    return instance;
                }
            });
            context.registerBean(DefaultObjectManager.class, () -> {
                ObjectProvider<ObjectLifeCycleHandler> handlers = context.getBeanProvider(ObjectLifeCycleHandler.class);
                return new SystemServicesConfig().defaultObjectManager(configuration, mock(SchemaFactory.class), handlers);
            });
            if (withBinding) {
                context.registerBean(CountingStore.class, () -> {
                    assertNotNull("Handler dependency may depend on the already constructed ObjectManager",
                            context.getBean(DefaultObjectManager.class));
                    return new CountingStore();
                }, definition -> definition.setLazyInit(true));
                context.registerBean(AiCatalogLaunchBinding.class, () -> {
                    handlerConstructions.incrementAndGet();
                    return new IaasApiConfig().AiCatalogLaunchBinding();
                }, definition -> definition.setLazyInit(true));
            }
            context.refresh();
        }
        CountingStore store() { return context.getBean(CountingStore.class); }
        Map<String, Object> plan(String name) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("id", "aicataloglaunch-" + UUID.randomUUID()); plan.put("status", "dispatching");
            plan.put("name", name); plan.put("accountId", 5L);
            DSL.using(configuration).execute("insert into ai_catalog_stack_launch_plan(id,state,document) values (?, ?, ?)",
                    plan.get("id"), "dispatching", "{}");
            return plan;
        }
        @Override public void close() { context.close(); }
    }
}
