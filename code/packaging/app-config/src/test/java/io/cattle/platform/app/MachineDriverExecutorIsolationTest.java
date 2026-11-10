package io.cattle.platform.app;

import static org.junit.Assert.*;

import com.netflix.config.ConfigurationManager;
import io.cattle.platform.engine.server.ProcessInstanceReference;
import io.cattle.platform.engine.server.impl.ProcessInstanceDispatcherImpl;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Shipping configuration plus real dispatch and barriers, without an Engine or network. */
public class MachineDriverExecutorIsolationTest {
    private final Map<String, Object> previous = new LinkedHashMap<>();
    private final CountDownLatch driversStarted = new CountDownLatch(3);
    private final CountDownLatch releaseDrivers = new CountDownLatch(1);
    private final CountDownLatch controlDone = new CountDownLatch(1);
    private final AtomicReference<String> controlThread = new AtomicReference<>();
    private final ExecutorService blocking = Executors.newFixedThreadPool(3, r -> new Thread(r, "qa-blocking"));
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> new Thread(r, "qa-control"));
    private final ProcessInstanceDispatcherImpl dispatcher = new ProcessInstanceDispatcherImpl() {
        @Override public void execute(long id) {
            if (id <= 103) {
                driversStarted.countDown();
                try { releaseDrivers.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } else {
                controlThread.set(Thread.currentThread().getName());
                controlDone.countDown();
            }
        }
    };

    @Before public void configureShippingRoutes() throws Exception {
        Properties defaults = new Properties();
        try (var input = getClass().getClassLoader().getResourceAsStream("META-INF/cattle/process/defaults.properties")) {
            assertNotNull(input); defaults.load(input);
        }
        assertEquals("true", defaults.getProperty("process.machinedriver.blocking"));
        configure("provisioning.enabled", "true");
        for (String key : new String[] {"process.machinedriver.blocking", "process.machinedriver.reactivate.blocking",
                "process.machinedriver.blockingextra", "process.machinedriver.reactivate.blockingextra",
                "process.stack.blocking", "process.stack.rollback.blocking", "process.stack.blockingextra",
                "process.stack.rollback.blockingextra"}) configure(key, defaults.getProperty(key, "false"));
        inject("blockingExecutor", blocking); inject("blockingSystemExecutor", blocking);
        inject("blockingExtraExecutor", blocking); inject("nonBlockingExecutor", control);
        inject("eventExecutor", control);
    }

    @After public void restoreConfigurationAndStopWorkers() throws Exception {
        releaseDrivers.countDown();
        blocking.shutdownNow(); control.shutdownNow();
        assertTrue(blocking.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(control.awaitTermination(5, TimeUnit.SECONDS));
        previous.forEach((key, value) -> {
            if (value == null) ConfigurationManager.getConfigInstance().clearProperty(key);
            else ConfigurationManager.getConfigInstance().setProperty(key, value);
        });
    }

    @Test public void threeAwaitingDriversCannotStarveStackReplay() throws Exception {
        for (int id = 101; id <= 103; id++) dispatcher.dispatch(reference(id, "machinedriver.reactivate"));
        assertTrue("All three external handlers must be waiting at the barrier", driversStarted.await(5, TimeUnit.SECONDS));
        dispatcher.dispatch(reference(104, "stack.rollback"));
        assertTrue("Control replay must finish before any external driver is released", controlDone.await(5, TimeUnit.SECONDS));
        assertEquals("qa-control", controlThread.get());
        assertEquals("No timeout or sleep may release the driver work", 1, releaseDrivers.getCount());
    }

    @Test public void explicitDriverRouteOverrideIsStillHonored() throws Exception {
        ConfigurationManager.getConfigInstance().setProperty("process.machinedriver.blocking", false);
        dispatcher.dispatch(reference(104, "machinedriver.reactivate"));
        assertTrue(controlDone.await(5, TimeUnit.SECONDS));
        assertEquals("qa-control", controlThread.get());
    }

    private void configure(String key, Object value) {
        previous.put(key, ConfigurationManager.getConfigInstance().getProperty(key));
        ConfigurationManager.getConfigInstance().setProperty(key, value);
    }

    private void inject(String name, ExecutorService executor) throws Exception {
        Field field = ProcessInstanceDispatcherImpl.class.getDeclaredField(name);
        field.setAccessible(true); field.set(dispatcher, executor);
    }

    private static ProcessInstanceReference reference(long id, String name) {
        ProcessInstanceReference ref = new ProcessInstanceReference();
        ref.setProcessId(id); ref.setName(name); // replay, not a fresh API event
        return ref;
    }
}
