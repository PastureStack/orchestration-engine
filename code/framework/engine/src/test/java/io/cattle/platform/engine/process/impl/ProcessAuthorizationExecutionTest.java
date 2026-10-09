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
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
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
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
    }

    @Test
    public void completionPersistenceRetryNeverRepeatsSideEffects() {
        ProcessRecord record = record(true);
        AtomicInteger callbacks = new AtomicInteger();
        ProcessAuthorizationHook audit = new ProcessAuthorizationHook() {
            @Override public boolean recordsCompletion() { return true; }
            @Override public void afterExecution(LaunchConfiguration config, Map<String, Object> metadata, ExitReason reason) {
                assertEquals(ExitReason.DONE, reason);
                if (callbacks.get() > 0) assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
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
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
    }

    @Test
    public void verifiedFrameStartsOnlyAfterAllHooksAndHasImmutableActualIdentity() {
        EngineContext engine = EngineContext.getEngineContext();
        ProcessRecord record = record(true);
        AtomicInteger passed = new AtomicInteger();
        ProcessAuthorizationHook check = new ProcessAuthorizationHook() {
            @Override public boolean recordsCompletion() { return true; }
            @Override public void beforeExecution(LaunchConfiguration config, Map<String, Object> metadata) {
                assertNull(engine.currentVerifiedExecution()); passed.incrementAndGet();
            }
        };
        TestProcess process = process(record, List.of(authorizer(false), check, check));
        process.onRun = () -> {
            assertEquals(2, passed.get());
            var frame = engine.currentVerifiedExecution();
            assertEquals("stack", frame.resourceType()); assertEquals("12", frame.resourceId());
            assertEquals(Map.of("keyId", "1a1", "requestId", "request1"), frame.rootAuthorization());
            assertThrows(UnsupportedOperationException.class, () -> frame.rootAuthorization().put("targetId", "99"));
            record.getData().put(ProcessAuthorization.DATA_KEY, Map.of("keyId", "1a1", "requestId", "mutated"));
            assertEquals("request1", frame.rootAuthorization().get("requestId"));
        };
        assertEquals(ExitReason.DONE, process.run());
        assertNull(engine.currentVerifiedExecution()); assertTrue(engine.peekAuthorization().isEmpty());
    }

    @Test
    public void childValidationSeesActualParentAndEveryExitRestoresIt() {
        EngineContext engine = EngineContext.getEngineContext();
        TestProcess parent = process(record(true), List.of(authorizer(false), audit()));
        parent.onRun = () -> {
            var original = engine.currentVerifiedExecution();
            ProcessRecord childRecord = record(true); childRecord.setResourceType("volume"); childRecord.setResourceId("6");
            ProcessAuthorizationHook validation = new ProcessAuthorizationHook() {
                @Override public void beforeExecution(LaunchConfiguration config, Map<String, Object> metadata) {
                    assertSame(original, engine.currentVerifiedExecution());
                }
            };
            TestProcess child = process(childRecord, List.of(authorizer(false), validation, audit()));
            child.onRun = () -> {
                assertNotSame(original, engine.currentVerifiedExecution());
                assertEquals("volume", engine.currentVerifiedExecution().resourceType());
                assertEquals("6", engine.currentVerifiedExecution().resourceId());
                assertThrows(IllegalStateException.class, () -> engine.popVerifiedExecution(original));
            };
            assertEquals(ExitReason.DONE, child.run()); assertSame(original, engine.currentVerifiedExecution());
            TestProcess denied = process(childRecord, List.of(authorizer(true), audit()));
            assertThrows(ProcessInstanceException.class, denied::run);
            assertSame(original, engine.currentVerifiedExecution());
        };
        assertEquals(ExitReason.DONE, parent.run()); assertNull(engine.currentVerifiedExecution());
    }

    @Test
    public void delegateAndPersistFailuresCannotLeakVerifiedFrame() {
        EngineContext engine = EngineContext.getEngineContext();
        TestProcess delegate = process(record(true), List.of(authorizer(false), audit()));
        delegate.onRun = () -> { throw new IllegalStateException("delegate failed"); };
        assertThrows(IllegalStateException.class, delegate::run);
        assertNull(engine.currentVerifiedExecution()); assertTrue(engine.peekAuthorization().isEmpty());
        ProcessManager failedManager = (ProcessManager) Proxy.newProxyInstance(ProcessManager.class.getClassLoader(),
                new Class<?>[]{ProcessManager.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("persistState")) throw new IllegalStateException("persist failed");
                    return null;
                });
        TestProcess persist = process(record(true), List.of(authorizer(false), audit()), failedManager);
        assertThrows(IllegalStateException.class, persist::run);
        assertNull(engine.currentVerifiedExecution()); assertTrue(engine.peekAuthorization().isEmpty());
    }

    @Test
    public void unavailableLaterHookNeverCreatesVerifiedAuthorityOrSideEffects() {
        EngineContext engine = EngineContext.getEngineContext();
        TestProcess process = process(record(true), List.of(authorizer(false), new ProcessAuthorizationHook() {
            @Override public void beforeExecution(LaunchConfiguration config, Map<String, Object> metadata) {
                assertNull(engine.currentVerifiedExecution()); throw new IllegalStateException("owner lookup unavailable");
            }
        }, audit()));
        ProcessExecutionExitException retry = assertThrows(ProcessExecutionExitException.class, process::run);
        assertEquals(ExitReason.RETRY_EXCEPTION, retry.getExitReason());
        assertEquals(0, process.sideEffects); assertNull(engine.currentVerifiedExecution());
        assertTrue(engine.peekAuthorization().isEmpty());
    }

    private static ProcessAuthorizationHook audit() {
        return new ProcessAuthorizationHook() { @Override public boolean recordsCompletion() { return true; } };
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
        return process(record, hooks, manager);
    }

    private static TestProcess process(ProcessRecord record, List<ProcessAuthorizationHook> hooks, ProcessManager manager) {
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
        Runnable onRun = () -> { };
        TestProcess(ProcessServiceContext context, ProcessRecord record) { super(context, record, null, state(record), false, false); }
        ExitReason run() { return executeWithProcessInstanceLock(); }
        @Override protected void runDelegateLoop(EngineContext context) { sideEffects++; onRun.run(); }
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
