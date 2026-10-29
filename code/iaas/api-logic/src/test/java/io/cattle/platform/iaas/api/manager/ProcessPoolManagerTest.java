package io.cattle.platform.iaas.api.manager;

import static org.junit.Assert.*;

import io.cattle.platform.core.addon.ProcessPool;
import io.cattle.platform.util.type.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;

public class ProcessPoolManagerTest {
    @Test public void realOwnerKeepsNamesCountersAndNullNativeDetailWhileKeysSurviveReorderAndRestart() {
        Map<String, ThreadPoolExecutor> first = pools(), restarted = pools();
        try {
            ProcessPoolManager manager = manager(first);
            List<ProcessPool> rows = rows(manager);
            assertEquals(List.of("", "shared", "shared"), rows.stream().map(ProcessPool::getName).toList());
            assertEquals(List.of("AnonymousExecutor", "FirstExecutor", "SecondExecutor"), rows.stream().map(ProcessPool::getPoolKey).toList());
            for (ProcessPool pool : rows) {
                assertEquals(0, pool.getActiveTasks()); assertEquals(0, pool.getPoolSize()); assertEquals(1, pool.getMinPoolSize());
                assertEquals(1, pool.getMaxPoolSize()); assertEquals(0, pool.getCompletedTasks()); assertEquals(8, pool.getQueueRemainingCapacity());
            }
            assertNull(manager.getByIdInternal("processPool", "AnonymousExecutor", null));
            List<String> original = rows.stream().map(ProcessPool::getPoolKey).sorted().toList();
            Collections.reverse(manager.executors);
            assertEquals(original, rows(manager).stream().map(ProcessPool::getPoolKey).sorted().toList());
            assertEquals(original, rows(manager(restarted)).stream().map(ProcessPool::getPoolKey).sorted().toList());
            assertNotSame(first.get("AnonymousExecutor"), restarted.get("AnonymousExecutor"));
        } finally { first.values().forEach(ThreadPoolExecutor::shutdownNow); restarted.values().forEach(ThreadPoolExecutor::shutdownNow); }
    }

    @Test public void springInjectsCanonicalBeanNamesRatherThanDisplayNamesOrAliases() {
        ThreadPoolExecutor anonymous = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(8)) { };
        ThreadPoolExecutor named = new NamedExecutor();
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
            context.registerBean("AnonymousExecutor", ThreadPoolExecutor.class, () -> anonymous);
            context.registerAlias("AnonymousExecutor", "AliasNotASeparatePool");
            context.registerBean("FirstExecutor", ThreadPoolExecutor.class, () -> named);
            context.registerBean(io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator.class,
                    () -> empty(io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator.class));
            context.registerBean(io.github.ibuildthecloud.gdapi.response.ResourceOutputFilterManager.class,
                    () -> empty(io.github.ibuildthecloud.gdapi.response.ResourceOutputFilterManager.class));
            context.registerBean(ProcessPoolManager.class); context.refresh();
            ProcessPoolManager owner = context.getBean(ProcessPoolManager.class);
            assertEquals(List.of("AnonymousExecutor", "FirstExecutor"), rows(owner).stream().map(ProcessPool::getPoolKey).sorted().toList());
            assertEquals("", rows(owner).getFirst().getName());
        } finally { anonymous.shutdownNow(); named.shutdownNow(); }
    }

    private static List<ProcessPool> rows(ProcessPoolManager manager) {
        List<?> raw = (List<?>) manager.listInternal(null, "processPool", Map.of(), null);
        return raw.stream().map(ProcessPool.class::cast).toList();
    }

    private static Map<String, ThreadPoolExecutor> pools() {
        Map<String, ThreadPoolExecutor> result = new LinkedHashMap<>();
        result.put("AnonymousExecutor", new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(8)) { });
        result.put("FirstExecutor", new NamedExecutor()); result.put("SecondExecutor", new NamedExecutor()); return result;
    }

    private static ProcessPoolManager manager(Map<String, ThreadPoolExecutor> beans) {
        ProcessPoolManager result = new ProcessPoolManager(); result.executorBeans = beans; result.executors = new ArrayList<>(beans.values()); return result;
    }

    private static <T> T empty(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> null));
    }

    private static class NamedExecutor extends ThreadPoolExecutor implements Named {
        NamedExecutor() { super(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(8)); }
        @Override public String getName() { return "shared"; }
    }
}
