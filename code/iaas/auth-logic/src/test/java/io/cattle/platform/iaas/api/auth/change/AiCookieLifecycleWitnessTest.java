package io.cattle.platform.iaas.api.auth.change;

import static io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.core.model.tables.records.StoragePoolRecord;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.DefaultProcessManager;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.manager.impl.ProcessRecordDao;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessDefinition;
import io.cattle.platform.engine.process.ProcessServiceContext;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.engine.process.impl.DefaultProcessInstanceImpl;
import io.cattle.platform.engine.process.log.ProcessExecutionLog;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

public class AiCookieLifecycleWitnessTest {
    static final List<String> CASES = List.of("registry/start", "registry/stop", "loadBalancerService/start", "loadBalancerService/stop",
            "externalService/start", "externalService/stop", "dnsService/start", "dnsService/stop", "stack/start", "stack/stop",
            "container/start", "container/stop", "container/restart", "service/restart");

    @Test public void fourteenCookieActionsNeedTheirOwnNativeRowsAndNeverBorrowAnUnrelatedSuccess() throws Exception {
        for (String value : CASES) try (Fixture f = new Fixture(value)) {
            var foreign = f.insert(new ProcessRecord(f.launch(), 90L, "server")); f.done(foreign, "DONE");
            assertEquals(value, "unknown", f.outcome());
            f.dispatch(); assertEquals(value, "running", f.outcome());
            if (value.equals("container/restart")) f.restartChain();
            else for (ProcessRecord record : f.records) if (record.getId() != 90) f.done(record, "DONE");
            f.terminal(); assertEquals(value, "succeeded", f.outcome());
        }
    }

    @Test public void stackFanoutPinsAllChildUuidsAndKeepsExplicitStableNoopsSeparateFromTransitionalSkips() throws Exception {
        for (String operation : List.of("start", "stop")) try (Fixture f = new Fixture("stack/" + operation)) {
            ServiceRecord noop = f.child(4L, operation.equals("start") ? "active" : "inactive"); f.children.add(noop); f.reseal();
            f.dispatch(); f.done(f.records.getFirst(), "DONE"); f.terminal(); assertEquals("succeeded", f.outcome());
            noop.setUuid("reused"); assertEquals("unknown", f.outcome()); noop.setUuid("child-4");
            f.children.remove(noop); assertEquals("unknown", f.outcome());
        }
        try (Fixture f = new Fixture("stack/stop")) {
            f.children.getFirst().setState("deactivating"); f.reseal(); f.dispatch(); f.terminal();
            assertEquals("unknown", f.outcome()); // Native skip is not this dispatch's completed process.
        }
    }

    @Test public void emptyAndAlreadyDesiredStacksNeedAnActualClosedNativeDispatchAndStableExactIdentitySet() throws Exception {
        for (boolean empty : List.of(true, false)) try (Fixture f = new Fixture("stack/start")) {
            if (empty) f.children.clear(); else f.children.getFirst().setState("active"); f.reseal();
            assertEquals("unknown", f.outcome()); f.dispatch(); assertEquals(0, f.records.size()); assertEquals("succeeded", f.outcome());
            f.children.add(f.child(8L, "active")); assertEquals("unknown", f.outcome());
        }
    }

    @Test public void nativeRestartHandlersAndFrameworkScheduleChainBindEveryParentBeforeCompletion() throws Exception {
        try (Fixture f = new Fixture("container/restart")) {
            f.dispatch(); f.done(f.records.getFirst(), "CHAIN"); f.terminal(); assertEquals("running", f.outcome());
            f.restartChain(); assertEquals("succeeded", f.outcome());
            Map<String, Object> before = f.store.execution(f.id), after = new LinkedHashMap<>(before);
            var processes = new LinkedHashMap<>((Map<String, Object>) before.get("cookieProcesses"));
            var child = new LinkedHashMap<>((Map<String, Object>) processes.get("instance:3:instance.start")); child.put("parentId", 999L);
            processes.put("instance:3:instance.start", child); after.put("cookieProcesses", processes); assertTrue(f.store.attest(f.id, before, after));
            assertEquals("unknown", f.outcome());
        }
    }

    @Test public void commonPrepareStripsForgedCorrelationAndWrongRuntimeParentsCannotSealAChain() throws Exception {
        try (Fixture f = new Fixture("container/restart")) {
            LaunchConfiguration forged = f.launch(); forged.setData(Map.of(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION, Map.of("planId", f.id)));
            ProcessAuthorization.prepare(forged, List.of(f.observer));
            assertFalse(forged.getData().containsKey(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION));
            f.manager.insert(new ProcessRecord(forged, null, null)); assertEquals("unknown", f.outcome());
            f.dispatch(); ProcessRecord root = f.records.getLast();
            f.chain(root, "instance.stop", 999L);
            assertEquals(3, f.records.size()); assertNotEquals("succeeded", f.outcome());
        }
    }

    @Test public void nativeServiceRestartCountEnrichmentIsAcceptedButUnrelatedFieldsOrCountsNeverAttest() throws Exception {
        try (Fixture f = new Fixture("service/restart")) { f.dispatch(); f.done(f.records.getFirst(), "DONE"); assertEquals("succeeded", f.outcome()); }
        for (String mutation : List.of("count", "field")) try (Fixture f = new Fixture("service/restart")) {
            f.mutation = mutation; assertThrows(IllegalStateException.class, f::dispatch); assertEquals(0, f.records.size());
            assertEquals("unknown", f.outcome());
        }
    }

    @Test public void failedAttestationRollsBackTheNativeInsertAndCannotQueueOrLeakThePrivateScope() throws Exception {
        try (Fixture f = new Fixture("registry/start")) {
            f.observer.store = spy(f.store); doReturn(false).when(f.observer.store).attest(anyString(), anyMap(), anyMap());
            var definition = mock(ProcessDefinition.class); when(definition.constructProcessState(any())).thenReturn(mock(ProcessState.class));
            var definitions = DefaultProcessManager.class.getDeclaredField("definitions"); definitions.setAccessible(true);
            definitions.set(f.manager, Map.of("storagepool.activate", definition)); f.manager.useQueue = true;
            assertThrows(IllegalStateException.class, f::dispatch); assertEquals(0, f.sql.fetchCount(PROCESS_INSTANCE)); assertEquals(0, f.manager.queued);
            f.manager.useQueue = false;
            f.observer.store = f.store;
            LaunchConfiguration ordinary = new LaunchConfiguration("storagepool.activate", "storagePool", "99", 8L, 0, Map.of("ordinary", true));
            ProcessAuthorization.prepare(ordinary, List.of(f.observer)); f.manager.insert(new ProcessRecord(ordinary, null, null));
            assertEquals(1, f.sql.fetchCount(PROCESS_INSTANCE)); assertEquals(Map.of("ordinary", true), f.records.getLast().getData());
        }
    }

    @Test public void originalCompletionPredicateIsStillRequiredAfterTheOwnProcessSucceeds() throws Exception {
        for (String value : List.of("container/restart", "stack/start", "loadBalancerService/start")) try (Fixture f = new Fixture(value)) {
            f.dispatch(); if (value.equals("container/restart")) f.restartChain(); else f.done(f.records.getFirst(), "DONE");
            assertEquals("succeeded", f.outcome());
            var domain = spy(f.domain); doReturn(f.root).when(domain).receiptTarget(any(), any(), anyMap());
            doReturn(false).when(domain).complete(any(), any(), anyMap(), anyMap());
            var service = new AiResourceChangePlanService(); service.domain = domain; service.store = f.store; service.clock = java.time.Clock.systemUTC();
            f.plan.put("status", "accepted"); f.plan.put("dispatchAcknowledged", true); f.plan.put("acceptedAt", System.currentTimeMillis());
                    f.store.save(f.plan);
            service.observe(f.plan, f.request); assertNotEquals(true, f.plan.get("verifiedResult")); assertNotEquals("succeeded", f.plan.get("status"));
        }
    }

    @Test public void queuedChainsTrackActiveStatesAndKeepNativeQueuesWhenTrackingExpiresOrDisappears() throws Exception {
        for (String state : List.of("accepted", "running", "unknown", "cancelled", "failed", "removed", "failure", "expired")) {
            try (Fixture f = new Fixture("container/restart")) {
                f.dispatch(); f.enableQueue();
                if (state.equals("removed")) f.sql.deleteFrom(DSL.table("ai_resource_change_plan")).execute();
                else {
                    f.sql.update(DSL.table("ai_resource_change_plan")).set(DSL.field("state"), state.equals("failure") || state.equals("expired") ?
                            "accepted" : state).execute();
                    if (state.equals("failure")) f.sql.update(DSL.table("ai_resource_change_plan")).set(DSL.field("failure_code"),
                            AiResourceChangePlanStore.AUTHORIZATION_DENIED).execute();
                    if (state.equals("expired")) f.sql.update(DSL.table("ai_resource_change_plan")).set(DSL.field("expires_at"), 0L).execute();
                }
                f.restartChain(); assertEquals(state, 2, f.manager.queued); assertEquals(state, 3, f.sql.fetchCount(PROCESS_INSTANCE));
                if (List.of("accepted", "running", "unknown", "expired").contains(state)) assertEquals(state, "succeeded", f.outcome());
                else assertNotEquals(state, "succeeded", f.outcome());
            }
        }
    }

    @Test public void trackerFailureDoesNotMarkNativeTransactionRollbackOnlyOrSwallowNativeInsertAndQueueErrors() throws Exception {
        for (String failure : List.of("false", "throw", "sql")) try (Fixture f = new Fixture("container/restart")) {
            f.dispatch(); f.enableQueue(); f.observer.store = spy(f.store);
            if (failure.equals("throw"))
                doThrow(new IllegalStateException("tracker-only")).when(f.observer.store).attestCookieChain(anyString(), anyMap(), anyMap());
            else if (failure.equals("false")) doReturn(false).when(f.observer.store).attestCookieChain(anyString(), anyMap(), anyMap());
            else f.sql.execute("CREATE TRIGGER tracker_failure BEFORE UPDATE ON ai_resource_change_plan FOR EACH ROW CALL "
                    + "'io.cattle.platform.iaas.api.auth.change.AiCookieLifecycleWitnessTest$TrackingSqlFailure'");
            new org.springframework.transaction.support.TransactionTemplate(f.store.transactions).execute(status -> {
                f.restartChain(); assertFalse(status.isRollbackOnly()); assertEquals(3, f.sql.fetchCount(PROCESS_INSTANCE)); return null;
            });
            assertEquals(3, f.sql.fetchCount(PROCESS_INSTANCE)); assertEquals(2, f.manager.queued); assertEquals("unknown", f.outcome());
        }
        for (boolean queue : List.of(false, true)) try (Fixture f = new Fixture("container/restart")) {
            f.dispatch(); f.enableQueue(); RuntimeException exact = new IllegalArgumentException("native-failure");
            if (queue) f.manager.queueFailure = exact; else f.nativeInsertFailure = exact;
            assertSame(exact, assertThrows(RuntimeException.class, () -> f.chain(f.records.getFirst(), "instance.stop", null)));
            assertEquals(queue ? 1 : 0, f.manager.queued);
        }
        for (String fatal : List.of("rollback-only", "savepoint", "deadlock")) try (Fixture f = new Fixture("container/restart")) {
            f.dispatch(); f.enableQueue(); f.observer.store = spy(f.store);
            RuntimeException exact = fatal.equals("savepoint") ? new org.springframework.transaction.TransactionSystemException("savepoint failed")
                    : fatal.equals("deadlock") ? new IllegalStateException(new java.sql.SQLException("native transaction rolled back", "40001"))
                    : new IllegalStateException("native transaction rollback-only");
            doAnswer(call -> {
                if (fatal.equals("rollback-only")) {
                    var holder = (org.springframework.transaction.support.ResourceHolderSupport)
                            org.springframework.transaction.support.TransactionSynchronizationManager.getResource(f.source);
                    holder.setRollbackOnly();
                }
                throw exact;
            }).when(f.observer.store).attestCookieChain(anyString(), anyMap(), anyMap());
            RuntimeException observed = assertThrows(fatal, RuntimeException.class, () ->
                    new org.springframework.transaction.support.TransactionTemplate(f.store.transactions).execute(status -> {
                        f.chain(f.records.getFirst(), "instance.stop", null); return null;
                    }));
            if (fatal.equals("rollback-only")) {
                assertTrue(observed instanceof org.springframework.transaction.UnexpectedRollbackException); assertSame(exact, observed.getCause());
            } else assertSame(exact, observed);
            assertEquals(0, f.manager.queued); assertEquals(1, f.sql.fetchCount(PROCESS_INSTANCE));
        }
    }

    @Test public void originalStackHandlerPreservesTwoNamedAliasesToOneWaitTargetAndLateFanoutFailureStaysPartialUnknown() throws Exception {
        try (Fixture f = new Fixture("stack/start")) {
            f.children.add(f.child(8L, "inactive")); f.duplicateAliases = true; f.reseal(); f.dispatch();
            ProcessRecord dependent = f.records.stream().filter(row -> row.getResourceId().equals("7")).findFirst().orElseThrow();
            assertEquals(List.of(8, 8), dependent.getData().get("waitForConsumedServicesIds"));
            for (ProcessRecord record : f.records) f.done(record, "DONE"); f.terminal(); assertEquals("succeeded", f.outcome());
        }
        try (Fixture f = new Fixture("stack/start")) {
            f.children.add(f.child(8L, "inactive")); f.reseal(); f.enableQueue(); f.observer.store = spy(f.store); int[] calls = {0};
            doAnswer(call -> ++calls[0] == 2 ? false : call.callRealMethod()).when(f.observer.store).attest(anyString(), anyMap(), anyMap());
            assertThrows(IllegalStateException.class, f::dispatch);
            assertEquals(1, f.manager.queued); assertEquals(1, f.sql.fetchCount(PROCESS_INSTANCE)); assertEquals("unknown", f.outcome());
        }
    }

    @Test public void fullPayloadJsonRoundtripKeepsChangedValuesTypesNullsFieldsAndArrayOrderDistinct() throws Exception {
        for (String mutation : List.of("value", "string", "extra", "null", "absence")) try (Fixture f = new Fixture("service/restart")) {
            f.returnedDataMutation = mutation; assertThrows(mutation, IllegalStateException.class, f::dispatch);
            assertEquals(0, f.sql.fetchCount(PROCESS_INSTANCE)); assertEquals("unknown", f.outcome());
        }
        try (Fixture f = new Fixture("stack/start")) {
            f.children.add(f.child(8L, "inactive")); f.children.add(f.child(9L, "inactive")); f.orderedDependencies = true;
            f.returnedDataMutation = "order"; f.reseal(); assertThrows(IllegalStateException.class, f::dispatch);
            assertEquals(2, f.sql.fetchCount(PROCESS_INSTANCE)); assertEquals("unknown", f.outcome());
        }
    }

    public static class TrackingSqlFailure implements org.h2.api.Trigger {
        @Override public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow) throws java.sql.SQLException {
            Object raw = newRow[5];
            String value = raw instanceof java.sql.Clob clob ? clob.getSubString(1, Math.toIntExact(clob.length())) : raw.toString();
            if (value.contains("instance:3:instance.stop")) throw new java.sql.SQLException("tracking update rejected", "45000");
        }
    }

    static class InsertManager extends DefaultProcessManager {
        int queued; boolean useQueue; RuntimeException queueFailure;
        @Override protected void queue(io.cattle.platform.engine.process.ProcessInstance process) { queued++; if (queueFailure != null) throw queueFailure; }
        ProcessRecord insert(ProcessRecord record) {
            return useQueue ? ((DefaultProcessInstanceImpl) createProcessInstance(record, true, false)).getProcessRecord() : insertProcess(record);
        }
    }
    static class NativeChain extends DefaultProcessInstanceImpl {
        NativeChain(ProcessServiceContext context, ProcessRecord record, ProcessState state) { super(context, record, mock(ProcessDefinition.class),
                state, false, false); }
        void chain(String name) { scheduleChain(name); }
    }

    static class Fixture implements AutoCloseable {
        final String id = "aichange-" + UUID.randomUUID(); final String type, operation;
        final JacksonJsonMapper json = new JacksonJsonMapper(); final ObjectManager objects = mock(ObjectManager.class);
        final AiResourceChangePlanStore store = new AiResourceChangePlanStore(); final AiResourceChangeProcessObserver observer = new
                AiResourceChangeProcessObserver();
        final io.cattle.platform.servicediscovery.api.dao.ServiceExposeMapDao restartMaps =
                mock(io.cattle.platform.servicediscovery.api.dao.ServiceExposeMapDao.class);
        final AiResourceChangeDomain domain = new AiResourceChangeDomain(); final InsertManager manager = new InsertManager();
        final SingleConnectionDataSource source; final org.jooq.DSLContext sql; final ApiRequest request = new ApiRequest(null, null);
        final List<ServiceRecord> children = new ArrayList<>(); final List<ProcessRecord> records = new ArrayList<>();
        final InstanceRecord managed = new InstanceRecord(); final Object root; final Map<String, Object> plan = new LinkedHashMap<>();
        long next = 100; String mutation; boolean duplicateAliases, orderedDependencies; String returnedDataMutation; RuntimeException nativeInsertFailure;
        Fixture(String value) throws Exception {
            type = value.split("/")[0]; operation = value.split("/")[1];
            source = new SingleConnectionDataSource(DriverManager.getConnection("jdbc:h2:mem:all-cookie-" + UUID.randomUUID() +
                    ";DATABASE_TO_LOWER=TRUE"), true);
            var config = new DefaultConfiguration().set(new TransactionAwareDataSourceProxy(source)).set(SQLDialect.H2); sql = DSL.using(config);
            sql.execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),document CLOB,"
                    + "actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            sql.createSchemaIfNotExists("cattle").execute(); sql.createTable(PROCESS_INSTANCE).columns(java.util.Arrays.stream(PROCESS_INSTANCE.fields())
                    .map(field -> DSL.field(field.getUnqualifiedName(), field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            store.json = json; store.transactions = new DataSourceTransactionManager(source); store.setConfiguration(config);
            observer.store = store; observer.objects = objects; observer.json = json; observer.restartMaps = () -> restartMaps;
            domain.processObserver = observer; domain.objects = objects; domain.json = json; domain.setConfiguration(config);
            AccountRecord project = new AccountRecord(); project.setId(5L); project.setUuid("adminProject"); project.setKind("project");
            when(objects.loadResource(Account.class, 5L)).thenReturn(project);
            String state = operation.equals("start") ? type.equals("container") ? "stopped" : "inactive" : type.equals("container") ? "running" : "active";
            if (type.equals("registry")) { var row = new StoragePoolRecord(); row.setId(3L); row.setUuid("root-3"); row.setAccountId(5L);
                    row.setKind("registry"); row.setState(state); row.setData(Map.of()); root = row; }
            else if (type.equals("container")) { var row = new InstanceRecord(); row.setId(3L); row.setUuid("root-3"); row.setAccountId(5L);
                    row.setKind("container"); row.setState(state); row.setStartCount(1L); row.setData(Map.of()); root = row; }
            else if (type.equals("stack")) { var row = new StackRecord(); row.setId(3L); row.setUuid("root-3"); row.setAccountId(5L);
                    row.setKind("stack"); row.setState("active"); row.setData(Map.of()); root = row; children.add(child(7L, operation.equals("start")
                    ? "inactive" : "active")); }
            else { var row = child(3L, state); row.setKind(type); row.setUuid("root-3"); root = row; }
            String nativeType = type.equals("registry") ? "storagePool" : type.equals("container") ? "instance" : type;
            when(objects.loadResource(eq(nativeType), anyString())).thenAnswer(call -> find(call.getArgument(0), Long.parseLong(call.getArgument(1))));
            when(objects.loadResource(eq(nativeType), anyLong())).thenAnswer(call -> find(call.getArgument(0), call.getArgument(1)));
            when(objects.loadResource(eq("service"), anyString())).thenAnswer(call -> find("service", Long.parseLong(call.getArgument(1))));
            when(objects.loadResource(eq("service"), anyLong())).thenAnswer(call -> find("service", call.getArgument(1)));
            when(objects.find(Service.class, "stackId", 3L, "removed", null)).thenAnswer(call -> new ArrayList<>(children));
            when(objects.find(Service.class, io.cattle.platform.core.model.tables.ServiceTable.SERVICE.STACK_ID, 3L,
                    io.cattle.platform.core.model.tables.ServiceTable.SERVICE.REMOVED, null)).thenAnswer(call -> new ArrayList<>(children));
            when(objects.loadResource(Service.class, "3")).thenReturn(root instanceof Service service ? service : null);
            managed.setId(30L); managed.setAccountId(5L); managed.setStartCount(1L);
            when(restartMaps.listServiceManagedInstances(any())).thenAnswer(call -> List.of(managed));
            var policy = mock(Policy.class); when(policy.getAccountId()).thenReturn(5L); when(policy.getAuthenticatedAsAccountId()).thenReturn(10L);
            ApiContext.newContext().setPolicy(policy); ApiContext.getContext().setApiRequest(request);
            request.setType(type); request.setId("3"); request.setMethod("POST"); request.setAction(descriptor().action());
                    request.setRequestObject(descriptor().body(Map.of()));
            plan.putAll(Map.of("id", id, "actorId", 10L, "accountId", 5L, "resourceType", type, "operation", operation, "status", "preview",
                    "internalResourceId", 3L,
                    "expiresAt", "2030-01-01T00:00:00Z", "parameters", Map.of(), "body", descriptor().body(Map.of())));
            plan.put("snapshot", domain.cookieLifecycleSnapshot(descriptor(), root)); store.insert(plan); claim();
            ProcessRecordDao dao = mock(ProcessRecordDao.class); when(dao.insert(any())).thenAnswer(call -> { if (nativeInsertFailure != null) throw
                    nativeInsertFailure; return insert(new ProcessRecord(call.getArgument(0), next++, "server")); });
            var field = DefaultProcessManager.class.getDeclaredField("processRecordDao"); field.setAccessible(true); field.set(manager, dao);
                    manager.setAuthorizationHooks(List.of(observer));
        }
        AiResourceChangeDescriptor descriptor() { return AiResourceChangeDescriptor.require(type, operation); }
        void claim() { plan.put("status", "dispatching"); plan.put("operationId", id); assertTrue(store.claim(plan)); }
        void reseal() { sql.deleteFrom(org.jooq.impl.DSL.table("ai_resource_change_plan")).execute(); plan.put("status", "preview");
                plan.put("snapshot", domain.cookieLifecycleSnapshot(descriptor(), root)); store.insert(plan); claim(); }
        ServiceRecord child(long id, String state) { var row = new ServiceRecord(); row.setId(id); row.setUuid("child-" + id); row.setAccountId(5L);
                row.setKind("service"); row.setStackId(type.equals("stack") ? 3L : 9L); row.setState(state); row.setData(Map.of()); return row; }
        Object find(String nativeType, long id) { if (id == 3 && !type.equals("stack") || type.equals("stack") && nativeType.equals("stack") && id ==
                3) return root; return children.stream().filter(row -> row.getId() == id).findFirst().orElse(null); }
        LaunchConfiguration launch() { String n = type.equals("registry") ? "storagePool" : type.equals("container") ? "instance" : "service";
            long id = type.equals("stack") && !children.isEmpty() ? children.getFirst().getId() : 3L;
            String action = type.equals("stack") ? operation.equals("start") ? "activate" : "deactivate" : descriptor().action();
            return new LaunchConfiguration(n.toLowerCase() + "." + action, n, Long.toString(id), 5L, 0, request.getRequestObject() instanceof Map<?,
                    ?> ? new LinkedHashMap<>((Map<String, Object>) request.getRequestObject()) : Map.of()); }
        void dispatch() {
            observer.dispatch(request, id, () -> {
                if (type.equals("service") && operation.equals("restart")) {
                    var filter = new io.cattle.platform.servicediscovery.api.filter.ServiceRestartValidationFilter();
                    for (var entry : Map.of("objectManager", objects, "jsonMapper", json, "exposeMapDao", restartMaps).entrySet()) {
                        try { var field = filter.getClass().getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(filter,
                                entry.getValue()); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    }
                    filter.resourceAction(type, request, mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManager.class));
                    var body = (Map<String, Object>) request.getRequestObject(); var strategy = (Map<String, Object>) body.get("rollingRestartStrategy");
                    if ("count".equals(mutation)) strategy.put("instanceToStartCount", Map.of("30", 999)); if ("field".equals(mutation))
                            body.put("secret", "unapproved");
                }
                if (type.equals("stack")) {
                    var nativeProcesses = mock(io.cattle.platform.object.process.ObjectProcessManager.class);
                    doAnswer(call -> { Service child = call.getArgument(1); LaunchConfiguration config = launch();
                        config.setResourceId(child.getId().toString()); config.setData(call.getArgument(2)); create(config); return null;
                    }).when(nativeProcesses).scheduleStandardProcess(eq(io.cattle.platform.object.process.StandardProcess.ACTIVATE), any(), anyMap());
                    doAnswer(call -> { Service child = call.getArgument(1); LaunchConfiguration config = launch();
                        config.setResourceId(child.getId().toString()); config.setData(call.getArgument(2)); create(config); return null;
                    }).when(nativeProcesses).scheduleProcessInstance(anyString(), any(), nullable(Map.class));
                    Object handler = operation.equals("start")
                            ? new io.cattle.platform.servicediscovery.api.action.StackActivateServicesActionHandler()
                            : new io.cattle.platform.servicediscovery.api.action.StackDeactivateServicesActionHandler();
                    var discovery = mock(io.cattle.platform.servicediscovery.api.service.ServiceDiscoveryApiService.class);
                    doAnswer(call -> new ArrayList<>(children)).when(discovery).listStackServices(3L);
                    var links = mock(io.cattle.platform.servicediscovery.api.dao.ServiceConsumeMapDao.class);
                    if (duplicateAliases || orderedDependencies) doAnswer(call -> {
                        if (!call.getArgument(0).equals(7L)) return List.of();
                        var a = new io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord(); a.setConsumedServiceId(8L); a.setName("alias-a");
                        var b = new io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord();
                                b.setConsumedServiceId(orderedDependencies ? 9L : 8L); b.setName("alias-b");
                        return List.of(a, b);
                    }).when(links).findConsumedServices(anyLong());
                    for (var entry : Map.of("objectProcessManager", nativeProcesses, "objectManager", objects,
                            "sdService", discovery, "consumeMapDao", links).entrySet()) {
                        try { var field = handler.getClass().getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(handler,
                                entry.getValue()); }
                        catch (NoSuchFieldException absentOnOtherHandler) { }
                        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    }
                    ((io.cattle.platform.api.action.ActionHandler) handler).perform(request.getAction(), root, request);
                } else create(launch()); return null;
            });
        }
        void enableQueue() throws ReflectiveOperationException {
            var definition = mock(ProcessDefinition.class); when(definition.constructProcessState(any())).thenReturn(mock(ProcessState.class));
            var definitions = DefaultProcessManager.class.getDeclaredField("definitions"); definitions.setAccessible(true);
            definitions.set(manager, Map.of("instance.restart", definition, "instance.stop", definition, "instance.start", definition,
                    "service.activate", definition, "service.deactivate", definition)); manager.useQueue = true;
        }
        ProcessRecord create(LaunchConfiguration config) { ProcessAuthorization.prepare(config, List.of(observer)); return manager.insert(new
                ProcessRecord(config, null, null)); }
        ProcessRecord insert(ProcessRecord record) { sql.insertInto(PROCESS_INSTANCE).set(PROCESS_INSTANCE.ID,
                record.getId()).set(PROCESS_INSTANCE.ACCOUNT_ID, ((Number) record.getAccountId()).longValue())
                .set(PROCESS_INSTANCE.PROCESS_NAME, record.getProcessName()).set(PROCESS_INSTANCE.RESOURCE_TYPE,
                        record.getResourceType()).set(PROCESS_INSTANCE.RESOURCE_ID, record.getResourceId()).set(PROCESS_INSTANCE.DATA,
                        record.getData()).execute(); var loaded = sql.selectFrom(PROCESS_INSTANCE).where(PROCESS_INSTANCE.ID.eq(record.getId())).fetchOne();
            ProcessRecord result = new ProcessRecord(record, record.getId(), "server"); result.setData(loaded.getData());
            if (returnedDataMutation != null && (type.equals("service") || record.getResourceId().equals("7"))) {
                Map<String, Object> changed = new LinkedHashMap<>(result.getData());
                if (returnedDataMutation.equals("extra")) changed.put("unexpected", true);
                if (returnedDataMutation.equals("null")) changed.put("unexpected", null);
                if (returnedDataMutation.equals("absence")) changed.remove("rollingRestartStrategy");
                if (returnedDataMutation.equals("value") || returnedDataMutation.equals("string")) {
                    Map<String, Object> strategy = new LinkedHashMap<>((Map<String, Object>) changed.get("rollingRestartStrategy"));
                    strategy.put("instanceToStartCount", returnedDataMutation.equals("value") ? Map.of("30", 2) : Map.of("30", "1"));
                    changed.put("rollingRestartStrategy", strategy);
                }
                if (returnedDataMutation.equals("order")) changed.put("waitForConsumedServicesIds", List.of(9, 8));
                result.setData(changed);
            }
            records.add(result); return result; }
        void done(ProcessRecord record, String reason) { sql.update(PROCESS_INSTANCE).set(PROCESS_INSTANCE.END_TIME, new
                Date()).set(PROCESS_INSTANCE.RESULT, "SUCCESS").set(PROCESS_INSTANCE.PHASE, "DONE").set(PROCESS_INSTANCE.EXIT_REASON,
                reason).where(PROCESS_INSTANCE.ID.eq(record.getId())).execute(); }
        String outcome() { return domain.cookieLifecycleOutcome(plan, root); }
        void terminal() { if (root instanceof InstanceRecord row) { row.setState(operation.equals("stop") ? "stopped" : "running"); row.setStartCount(2L); }
            else if (root instanceof StoragePoolRecord row) row.setState(operation.equals("stop") ? "inactive" : "active");
            else if (root instanceof ServiceRecord row) row.setState(operation.equals("stop") ? "inactive" : "active");
            for (ServiceRecord child : children) child.setState(operation.equals("stop") ? "inactive" : "active"); }
        void restartChain() { ProcessRecord first = records.stream().filter(row -> row.getProcessName().equals("instance.restart") && row.getId() >=
                100).findFirst().orElseThrow();
            ProcessRecord stop = chain(first, "instance.stop", null); ProcessRecord start = chain(stop, "instance.start", null); done(first,
                    "CHAIN"); done(stop, "CHAIN"); done(start, "DONE"); }
        ProcessRecord chain(ProcessRecord parent, String name, Long corruptParent) {
            var state = mock(ProcessState.class); var data = new LinkedHashMap<>(parent.getData()); when(state.getData()).thenReturn(data);
                    when(state.getResource()).thenReturn(root);
            if (parent.getProcessName().equals("instance.restart")) new io.cattle.platform.process.instance.InstanceRestart().handle(state, null);
            else assertEquals("instance.start", new io.cattle.platform.process.instance.InstanceStopPostAction().handle(state, null).getChainProcessName());
            var manager = mock(io.cattle.platform.engine.manager.ProcessManager.class);
            doAnswer(call -> { LaunchConfiguration config = call.getArgument(0); if (corruptParent != null)
                    config.setChainParentProcessId(corruptParent); create(config); return null; }).when(manager).scheduleProcessInstance(any());
            var context = mock(ProcessServiceContext.class); when(context.getProcessManager()).thenReturn(manager);
            var engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution(parent.getResourceType(), parent.getResourceId(),
                    parent.getProcessName(), Map.of());
            engine.pushLog(new ProcessExecutionLog());
            try { new NativeChain(context, parent, state).chain(name); return records.getLast(); }
            finally { engine.popLog(); engine.popVerifiedExecution(frame); }
        }
        @Override public void close() { ApiContext.remove(); source.destroy(); }
    }
}
