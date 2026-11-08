package io.cattle.platform.process.driver;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.deferred.util.DeferredUtils;
import io.cattle.platform.engine.handler.ProcessLogic;
import io.cattle.platform.engine.manager.ProcessManager;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.engine.process.ProcessAuthorizationHook;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.engine.process.impl.AbstractStatesBasedProcessState;
import io.cattle.platform.engine.process.impl.ProcessCancelException;
import io.cattle.platform.engine.process.impl.ResourceStatesDefinition;
import io.cattle.platform.core.model.NetworkDriver;
import io.cattle.platform.core.model.StorageDriver;
import io.cattle.platform.core.model.tables.records.NetworkDriverRecord;
import io.cattle.platform.core.model.tables.records.StorageDriverRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.cattle.platform.object.process.impl.DefaultObjectProcessManager;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.After;
import org.junit.Test;
import org.w3c.dom.Element;

/** Real native chain helper and shipped state gates; only persistence/event transport is local memory. */
public class DriverServiceRemoveTest {
    @After public void cleanup() { DeferredUtils.resetDeferred(); }

    @Test public void activatingActiveAndInactiveUseOnlyLegalNativeSchedulingForBothDriverKinds() throws Exception {
        for (String type : DriverServiceActivate.DRIVERS) for (String initial : List.of("activating", "active", "inactive")) {
            Fixture f = new Fixture(type, initial);
            f.remove.handle(f.parent, null);
            assertEquals("removing", f.service.getState());
            assertEquals("activating".equals(initial) ? "deactivating" : "removing", ObjectUtils.getState(f.driver));
            assertEquals(1, f.pending.size()); // No synchronous child execution under the parent lock.
            if ("activating".equals(initial)) {
                var scheduled = f.pending.getFirst();
                assertEquals(type.toLowerCase() + ".deactivate", scheduled.getProcessName());
                assertEquals(type.toLowerCase() + ".remove", scheduled.getData().get(scheduled.getProcessName() + ProcessLogic.CHAIN_PROCESS));
            }
            f.finishScheduledTransitions();
            assertEquals("removed", ObjectUtils.getState(f.driver));
            assertEquals("removing", f.service.getState());
            verify(f.nativeProcesses, never()).executeStandardProcess(any(), any(), any());
            verify(f.objects, never()).setFields(any(), any(Map.class));
        }
    }

    @Test public void terminalChildrenAreSkippedAndDuplicateChildRowsDoNotDuplicateScheduling() throws Exception {
        for (String type : DriverServiceActivate.DRIVERS) {
            for (String terminal : List.of("removing", "removed", "purging", "purged")) {
                Fixture f = new Fixture(type, terminal); f.remove.handle(f.parent, null);
                assertTrue(f.attempts.isEmpty()); assertTrue(f.pending.isEmpty());
            }
            Fixture f = new Fixture(type, "activating"); f.duplicates = true;
            f.remove.handle(f.parent, null); assertEquals(1, f.pending.size());
            f.finishScheduledTransitions(); int attempts = f.attempts.size();
            f.remove.handle(f.parent, null); assertEquals(attempts, f.attempts.size()); assertTrue(f.pending.isEmpty());
        }
        Fixture f = new Fixture("networkDriver", "active"); ObjectProcessManager scheduled = mock(ObjectProcessManager.class);
        var storage = new StorageDriverRecord(); storage.setId(306L); storage.setAccountId(5L); storage.setServiceId(10L); storage.setState("active");
        when(f.objects.children(f.service, StorageDriver.class)).thenReturn(List.of(storage)); f.remove.processManager = scheduled;
        f.remove.handle(f.parent, null);
        verify(scheduled).scheduleStandardChainedProcessAsync(StandardProcess.DEACTIVATE, StandardProcess.REMOVE, f.driver, null);
        verify(scheduled).scheduleStandardChainedProcessAsync(StandardProcess.DEACTIVATE, StandardProcess.REMOVE, storage, null);
        verifyNoMoreInteractions(scheduled);
    }

    @Test public void authorizationAndSchedulingFailuresRemainVisibleWithoutMutatingOrRemovingTheChild() throws Exception {
        for (String type : DriverServiceActivate.DRIVERS) for (RuntimeException failure : List.of(
                new ProcessAuthorizationDeniedException("KeyScopeDenied"), new IllegalStateException("native-scheduling-unavailable"))) {
            Fixture f = new Fixture(type, "activating"); f.failure = failure;
            assertSame(failure, assertThrows(RuntimeException.class, () -> f.remove.handle(f.parent, null)));
            assertEquals("activating", ObjectUtils.getState(f.driver)); assertEquals("removing", f.service.getState());
            assertTrue(f.pending.isEmpty());
            assertEquals(List.of(type.toLowerCase() + ".remove", type.toLowerCase() + ".deactivate"), f.attempts);
        }
    }

    @Test public void nativeHelperRetainsCapturedActorKeyAndParentOperationWithoutCreatingAGrant() throws Exception {
        Fixture f = new Fixture("networkDriver", "activating");
        Map<String, Object> authority = Map.of("keyId", 31L, "policyRevision", 2L, "principalAccountId", 1L,
                "accountId", 5L, "targetType", "service", "targetId", "10", "operation", "remove", "requestMethod", "DELETE");
        f.nativeProcesses.setAuthorizationHooks(List.of(new ProcessAuthorizationHook() {
            @Override public Map<String, Object> capture(LaunchConfiguration config) { return authority; }
        }));
        f.remove.handle(f.parent, null);
        LaunchConfiguration next = f.pending.getFirst();
        assertTrue(next.isAuthorizationCaptured()); assertEquals(authority, ProcessAuthorization.metadata(next));
        assertEquals(5L, next.getAccountId()); assertEquals("306", next.getResourceId());
        assertFalse(next.getData().containsKey("force")); assertFalse(next.getData().containsKey("skipAuthorization"));
    }

    static class Fixture {
        final DriverServiceRemove remove = new DriverServiceRemove();
        final DefaultObjectProcessManager nativeProcesses = spy(new DefaultObjectProcessManager());
        final ObjectManager objects = mock(ObjectManager.class); final ProcessState parent = mock(ProcessState.class);
        final ServiceRecord service = new ServiceRecord(); final Object driver; final String type;
        final ArrayDeque<LaunchConfiguration> pending = new ArrayDeque<>(); final List<String> attempts = new ArrayList<>();
        final Map<String, ResourceStatesDefinition> rules = new HashMap<>();
        boolean duplicates; RuntimeException failure;
        Fixture(String type, String state) throws Exception {
            this.type = type; service.setId(10L); service.setAccountId(5L); service.setState("removing");
            if ("networkDriver".equals(type)) {
                var row = new NetworkDriverRecord(); row.setId(306L); row.setUuid("network-own"); row.setServiceId(10L); row.setAccountId(5L);
                driver = row;
            } else {
                var row = new StorageDriverRecord(); row.setId(306L); row.setUuid("storage-own"); row.setServiceId(10L); row.setAccountId(5L);
                driver = row;
            }
            state(state);
            SchemaFactory schemas = mock(SchemaFactory.class); Schema schema = mock(Schema.class); Field id = mock(Field.class);
            when(schemas.getSchemaClass("networkDriver")).thenReturn((Class) NetworkDriver.class);
            when(schemas.getSchemaClass("storageDriver")).thenReturn((Class) StorageDriver.class);
            when(schemas.getSchema(any(Class.class))).thenReturn(schema); when(schema.getId()).thenReturn(type);
            when(schema.getResourceFields()).thenReturn(Map.of("id", id)); when(id.getValue(driver)).thenReturn(306L);
            when(objects.getSchemaFactory()).thenReturn(schemas); when(objects.getType(driver)).thenReturn(type);
            when(objects.children(eq(service), any(Class.class))).thenAnswer(call -> {
                boolean selected = (type.equals("networkDriver") ? NetworkDriver.class : StorageDriver.class).equals(call.getArgument(1));
                return selected ? duplicates ? List.of(driver, driver) : List.of(driver) : List.of();
            });
            when(parent.getResource()).thenReturn(service); remove.objectManager = objects; remove.processManager = nativeProcesses;
            nativeProcesses.setObjectManager(objects); nativeProcesses.setSchemaFactory(schemas);
            ProcessManager transport = mock(ProcessManager.class); nativeProcesses.setProcessManager(transport);
            doAnswer(call -> { schedule(call.getArgument(0)); return null; }).when(transport).scheduleProcessInstance(any());
            Path root = Path.of(System.getProperty("driverRemoval.engineRoot", ".")).toAbsolutePath();
            String relative = "code/packaging/app-config/src/main/resources/META-INF/cattle/process/spring-process-context.xml";
            while (root != null && !root.resolve(relative).toFile().isFile()) root = root.getParent();
            assertNotNull(root); var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var definitions = factory.newDocumentBuilder().parse(root.resolve(relative).toFile()).getElementsByTagName("process:process");
            for (int i = 0; i < definitions.getLength(); i++) {
                Element row = (Element) definitions.item(i); if (!row.getAttribute("name").startsWith(type.toLowerCase() + ".")) continue;
                var rule = new ResourceStatesDefinition(); rule.setStartStates(new HashSet<>(List.of(row.getAttribute("start").split(","))));
                Map<String, String> transition = new HashMap<>(), done = new HashMap<>();
                transition.put(null, row.getAttribute("transitioning")); done.put(null, row.getAttribute("done"));
                rule.setTransitioningStatesMap(transition); rule.setDoneStatesMap(done); rules.put(row.getAttribute("name"), rule);
            }
        }
        void state(String value) {
            if (driver instanceof NetworkDriverRecord row) row.setState(value); else ((StorageDriverRecord) driver).setState(value);
        }
        void schedule(LaunchConfiguration config) {
            attempts.add(config.getProcessName()); MemoryState state = new MemoryState(rules.get(config.getProcessName()), this);
            if (state.shouldCancel(null)) throw new ProcessCancelException("Native state gate rejects " + state.getState());
            if (failure != null) throw failure;
            if (state.isDone(true)) return;
            if (state.isStart(null)) state.setTransitioning();
            pending.add(config);
        }
        void finishScheduledTransitions() {
            while (!pending.isEmpty()) {
                LaunchConfiguration config = pending.removeFirst(); new MemoryState(rules.get(config.getProcessName()), this).setDone();
                String chain = (String) config.getData().get(config.getProcessName() + ProcessLogic.CHAIN_PROCESS);
                if (chain != null) nativeProcesses.scheduleProcessInstanceAsync(chain, driver, null);
            }
        }
    }

    static class MemoryState extends AbstractStatesBasedProcessState {
        final Fixture fixture;
        MemoryState(ResourceStatesDefinition rule, Fixture fixture) { super(new JacksonJsonMapper(), rule); this.fixture = fixture; }
        @Override protected boolean setState(boolean transitioning, String old, String next) { fixture.state(next); return true; }
        @Override public String getState() { return ObjectUtils.getState(fixture.driver); }
        @Override public String getResourceId() { return "306"; }
        @Override public Object getResource() { return fixture.driver; }
        @Override public io.cattle.platform.lock.definition.LockDefinition getProcessLock() { return null; }
        @Override public Map<String, Object> getData() { return Map.of(); }
        @Override public void reload() { }
        @Override public void applyData(Map<String, Object> data) { }
        @Override public void rebuild() { }
        @Override protected Map<String, Object> convertMap(Map<?, ?> data) { return Map.of(); }
    }
}
