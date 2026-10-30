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
import org.junit.Test;

public class NestedAuthorizationDenialTest {
    @Test
    public void synchronousChildDenialTerminatesParentAndAuditsExactReason() {
        ProcessRecord record = record();
        AtomicInteger completed = new AtomicInteger();
        var process = process(record, ExitReason.AUTHORIZATION_DENIED, false, completed);
        ProcessInstanceException denied = assertThrows(ProcessInstanceException.class, process::run);
        assertEquals(ExitReason.AUTHORIZATION_DENIED, denied.getExitReason());
        assertEquals(ExitReason.AUTHORIZATION_DENIED, process.getProcessRecord().getExitReason());
        assertNotNull(record.getEndTime());
        assertEquals(1, process.calls);
        assertEquals(1, completed.get());
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
        assertTrue(EngineContext.getEngineContext().peekAuthorization().isEmpty());
    }

    @Test
    public void deniedCompletionReplayRetriesOnlyAuditEvenIfKeyIsNowRevoked() {
        ProcessRecord record = record();
        AtomicInteger completed = new AtomicInteger();
        var process = process(record, ExitReason.AUTHORIZATION_DENIED, true, completed);
        assertEquals(ExitReason.AUDIT_PENDING, assertThrows(ProcessInstanceException.class, process::run).getExitReason());
        assertEquals("AUTHORIZATION_DENIED", record.getData().get(ProcessAuthorization.COMPLETION_PENDING_KEY));
        assertNull(record.getEndTime());
        var replay = process(record, ExitReason.AUTHORIZATION_DENIED, true, completed);
        assertEquals(ExitReason.AUTHORIZATION_DENIED, replay.run());
        assertEquals(0, replay.calls);
        assertEquals(2, completed.get());
        assertNotNull(record.getEndTime());
        assertFalse(record.getData().containsKey(ProcessAuthorization.COMPLETION_PENDING_KEY));
    }

    @Test
    public void nonAuthorizationChildFailuresKeepExistingRetryClassification() {
        for (ExitReason child : List.of(ExitReason.UNKNOWN_EXCEPTION, ExitReason.RETRY_EXCEPTION, ExitReason.CANCELED)) {
            ProcessRecord record = record();
            var process = process(record, child, false, new AtomicInteger());
            assertEquals(child, assertThrows(ProcessInstanceException.class, process::run).getExitReason());
            assertEquals(ExitReason.UNKNOWN_EXCEPTION, process.getProcessRecord().getExitReason());
            assertNull(record.getEndTime());
        }
    }

    private static ProcessRecord record() {
        ProcessRecord record = new ProcessRecord();
        record.setResourceType("instance"); record.setResourceId("3"); record.setProcessName("instance.remove");
        record.setData(new HashMap<>(Map.of(ProcessAuthorization.DATA_KEY, Map.of("keyId", "1c1", "requestId", "own-request"))));
        return record;
    }

    private static NativeLoop process(ProcessRecord record, ExitReason child, boolean auditFailsFirst, AtomicInteger completed) {
        ProcessManager manager = (ProcessManager) Proxy.newProxyInstance(ProcessManager.class.getClassLoader(),
                new Class<?>[] {ProcessManager.class}, (proxy, method, args) -> null);
        ProcessServiceContext context = new ProcessServiceContext(null, null, manager, null, List.of());
        context.setAuthorizationHooks(List.of(new ProcessAuthorizationHook() {
            @Override public boolean authorizesExecution() { return true; }
            @Override public boolean recordsCompletion() { return true; }
            @Override public void beforeExecution(LaunchConfiguration config, Map<String, Object> metadata) {
                if (auditFailsFirst && completed.get() > 0) throw new ProcessAuthorizationDeniedException("revoked");
            }
            @Override public void afterExecution(LaunchConfiguration config, Map<String, Object> metadata, ExitReason reason) {
                if (child == ExitReason.AUTHORIZATION_DENIED) assertEquals(ExitReason.AUTHORIZATION_DENIED, reason);
                if (completed.getAndIncrement() == 0 && auditFailsFirst) throw new IllegalStateException("audit unavailable");
            }
        }));
        ProcessDefinition definition = (ProcessDefinition) Proxy.newProxyInstance(ProcessDefinition.class.getClassLoader(),
                new Class<?>[] {ProcessDefinition.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return record.getProcessName();
                    if (method.getName().equals("getResourceType")) return record.getResourceType();
                    return null;
                });
        ProcessState state = (ProcessState) Proxy.newProxyInstance(ProcessState.class.getClassLoader(),
                new Class<?>[] {ProcessState.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getResourceId")) return record.getResourceId();
                    if (method.getName().equals("getData")) return record.getData();
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        return new NativeLoop(context, record, definition, state, child);
    }

    private static final class NativeLoop extends DefaultProcessInstanceImpl {
        private final ExitReason child;
        int calls;
        NativeLoop(ProcessServiceContext context, ProcessRecord record, ProcessDefinition definition, ProcessState state, ExitReason child) {
            super(context, record, definition, state, false, false); this.child = child;
        }
        ExitReason run() { return executeWithProcessInstanceLock(); }
        @Override protected void acquireLockAndRun() {
            calls++;
            assertEquals("instance.remove", EngineContext.getEngineContext().currentVerifiedExecution().processName());
            throw new ProcessInstanceException(null, new ProcessExecutionExitException(child));
        }
    }
}
