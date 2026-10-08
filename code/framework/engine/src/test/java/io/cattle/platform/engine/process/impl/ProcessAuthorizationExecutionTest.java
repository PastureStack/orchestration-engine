package io.cattle.platform.engine.process.impl;

import static org.junit.Assert.*;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.ProcessManager;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.*;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ProcessAuthorizationExecutionTest {
    @Test
    public void noKeyRunsOriginalProcessWithoutAuthorizationCallbacks() {
        AtomicInteger callbacks = new AtomicInteger();
        TestProcess process = process(record(false), List.of(new ProcessAuthorizationHook() {
            @Override public void beforeExecution(LaunchConfiguration config, Map<String, Object> metadata) { callbacks.incrementAndGet(); }
        }));
        assertEquals(ExitReason.DONE, process.run());
        assertEquals(1, process.sideEffects);
        assertEquals(0, callbacks.get());
    }

    @Test
    public void deniedKeyIsTerminalAndHasNoSideEffects() {
        AtomicInteger completed = new AtomicInteger();
        ProcessRecord record = record(true);
        TestProcess process = process(record, List.of(authorizer(true), new ProcessAuthorizationHook() {
            @Override public boolean recordsCompletion() { return true; }
            @Override public void afterExecution(LaunchConfiguration config, Map<String, Object> metadata, ExitReason reason) {
                assertEquals(ExitReason.AUTHORIZATION_DENIED, reason);
                completed.incrementAndGet();
            }
        }));
        try { process.run(); fail("Denied key must not run"); } catch (ProcessInstanceException expected) { }
        assertEquals(0, process.sideEffects);
        assertEquals(1, completed.get());
        assertEquals(ExitReason.AUTHORIZATION_DENIED, process.getProcessRecord().getExitReason());
        assertNotNull(record.getEndTime());
        assertTrue(EngineContext.getEngineContext().peekAuthorization().isEmpty());
    }

    @Test
    public void completionPersistenceRetryNeverRepeatsSideEffects() {
        ProcessRecord record = record(true);
        AtomicInteger callbacks = new AtomicInteger();
        ProcessAuthorizationHook audit = new ProcessAuthorizationHook() {
            @Override public boolean recordsCompletion() { return true; }
            @Override public void afterExecution(LaunchConfiguration config, Map<String, Object> metadata, ExitReason reason) {
                assertEquals(ExitReason.DONE, reason);
                if (callbacks.getAndIncrement() == 0) { throw new IllegalStateException("storage unavailable"); }
            }
        };
        TestProcess first = process(record, List.of(authorizer(false), audit));
        try { first.run(); fail("Unavailable audit must retain pending completion"); } catch (ProcessInstanceException expected) { }
        assertEquals(1, first.sideEffects);
        assertEquals("DONE", record.getData().get(ProcessAuthorization.COMPLETION_PENDING_KEY));
        TestProcess replay = process(record, List.of(authorizer(true), audit));
        assertEquals(ExitReason.DONE, replay.run());
        assertEquals(0, replay.sideEffects);
        assertEquals(2, callbacks.get());
        assertFalse(record.getData().containsKey(ProcessAuthorization.COMPLETION_PENDING_KEY));
    }

    private static ProcessAuthorizationHook authorizer(boolean deny) {
        return new ProcessAuthorizationHook() {
            @Override public boolean authorizesExecution() { return true; }
            @Override public void beforeExecution(LaunchConfiguration config, Map<String, Object> metadata) {
                if (deny) { throw new ProcessAuthorizationDeniedException("key_revoked"); }
            }
        };
    }

    private static TestProcess process(ProcessRecord record, List<ProcessAuthorizationHook> hooks) {
        ProcessManager manager = (ProcessManager) Proxy.newProxyInstance(ProcessManager.class.getClassLoader(), new Class<?>[]{ProcessManager.class},
                (proxy, method, arguments) -> null);
        ProcessServiceContext context = new ProcessServiceContext(null, null, manager, null, List.of());
        context.setAuthorizationHooks(hooks);
        return new TestProcess(context, record);
    }

    private static ProcessRecord record(boolean key) {
        ProcessRecord record = new ProcessRecord();
        record.setProcessName("stack.update");
        record.setResourceType("stack");
        record.setResourceId("12");
        Map<String, Object> data = new HashMap<>();
        if (key) { data.put(ProcessAuthorization.DATA_KEY, Map.of("keyId", "1a1", "requestId", "request1")); }
        record.setData(data);
        return record;
    }

    private static final class TestProcess extends DefaultProcessInstanceImpl {
        int sideEffects;
        TestProcess(ProcessServiceContext context, ProcessRecord record) { super(context, record, null, state(record), false, false); }
        ExitReason run() { return executeWithProcessInstanceLock(); }
        @Override protected void runDelegateLoop(EngineContext context) { sideEffects++; }
    }

    private static ProcessState state(ProcessRecord record) {
        AtomicReference<ProcessPhase> phase = new AtomicReference<>(record.getPhase());
        return (ProcessState) Proxy.newProxyInstance(ProcessState.class.getClassLoader(), new Class<?>[]{ProcessState.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("setPhase")) { phase.set((ProcessPhase) arguments[0]); return null; }
                    if (method.getName().equals("getPhase")) return phase.get();
                    if (method.getName().equals("getData")) return record.getData();
                    if (method.getName().equals("getResourceId")) return record.getResourceId();
                    return null;
                });
    }
}
