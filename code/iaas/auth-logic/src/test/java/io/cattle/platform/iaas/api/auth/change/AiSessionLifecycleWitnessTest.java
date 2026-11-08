package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.engine.manager.impl.DefaultProcessManager;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.manager.impl.ProcessRecordDao;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationHook;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.sql.DriverManager;
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

public class AiSessionLifecycleWitnessTest {
    private static final List<String> SERVICES = List.of("start", "stop", "finish_upgrade", "rollback", "cancel_upgrade");

    @Test public void trueCookieHostAndServiceLifecycleCaptureOneOwnNativeProcessWithoutAnyCredentialMetadata() throws Exception {
        for (String type : List.of("host", "service")) for (String operation : type.equals("host") ? List.of("start", "stop") : SERVICES) {
            try (Fixture f = new Fixture(type, operation)) {
                assertNull(ApiKeyCredentialContext.get(f.request)); assertFalse(f.plan.containsKey("credentialId"));
                ProcessRecord record = f.dispatch();
                assertTrue(ProcessAuthorization.metadata(record).isEmpty()); assertFalse(f.store.execution(f.id).containsKey("credentialId"));
                assertEquals(100L, ((Number) f.store.execution(f.id).get("rootProcessId")).longValue());
                assertNull(f.request.getAttribute(AiResourceChangeProcessObserver.ATTRIBUTE)); assertEquals("running", f.outcome());
                f.success(100L); assertEquals("succeeded", f.outcome());
            }
        }
    }

    @Test public void anotherCookieNativeSuccessAfterTheBaselineNeverSubstitutesForThisPlansExactWitness() throws Exception {
        for (String type : List.of("host", "service")) for (String operation : type.equals("host") ? List.of("start", "stop") : SERVICES) {
            try (Fixture f = new Fixture(type, operation)) {
                f.insert(new ProcessRecord(f.launch(), 99L, "server")); f.success(99L);
                f.terminal(); assertEquals("unknown", f.outcome());
                var foreignOnly = f.observe(); assertEquals("unknown", foreignOnly.get("status"));
                assertNotEquals(true, foreignOnly.get("verifiedResult"));
            }
            try (Fixture f = new Fixture(type, operation)) {
                f.insert(new ProcessRecord(f.launch(), 99L, "server")); f.success(99L);
                f.dispatch(); assertEquals("running", f.outcome());
                f.success(100L); assertEquals("succeeded", f.outcome());
                f.sql.update(PROCESS_INSTANCE).set(PROCESS_INSTANCE.RESOURCE_ID, "4").where(PROCESS_INSTANCE.ID.eq(100L)).execute();
                assertEquals("running", f.outcome());
            }
        }
    }

    @Test public void wrongRootActionUuidOwnerKindAndNonemptyPayloadFailBeforeAnyNativeInsert() throws Exception {
        for (String mutation : List.of("resource", "action", "id", "uuid", "account", "kind", "payload", "actor", "body")) {
            try (Fixture f = new Fixture("service", "start")) {
                LaunchConfiguration config = f.launch();
                f.observer.dispatch(f.request, f.id, () -> {
                    ProcessAuthorization.prepare(config, List.of(f.observer));
                    switch (mutation) {
                    case "resource" -> config.setResourceType("host");
                    case "action" -> config.setProcessName("service.remove");
                    case "id" -> config.setResourceId("4");
                    case "uuid" -> f.service.setUuid("replacement");
                    case "account" -> config.setAccountId(6L);
                    case "kind" -> f.service.setKind("loadBalancerService");
                    case "payload" -> config.setData(Map.of("secret", "PRIVATE"));
                    case "actor" -> { var before = f.store.execution(f.id); var after = new LinkedHashMap<>(before);
                        after.put("actorId", 11); assertTrue(f.store.attest(f.id, before, after)); }
                    case "body" -> f.request.setRequestObject(Map.of("secret", "PRIVATE"));
                    default -> { }
                    }
                    assertThrows(mutation, IllegalStateException.class, () -> f.manager.insert(new ProcessRecord(config, null, null)));
                    return null;
                });
                assertEquals(0, f.count()); assertNull(f.store.execution(f.id).get("rootProcessId"));
            }
        }
    }

    @Test public void creationCallbackFailureRollsBackTheNewRowAndCannotSealADifferentOrSecondRoot() throws Exception {
        for (String mutation : List.of("callback", "returnedIdentity", "secondRoot")) try (Fixture f = new Fixture("host", "start")) {
            if (mutation.equals("callback")) {
                f.observer.store = spy(f.store); doReturn(false).when(f.observer.store).attestSessionProcess(anyString(), anyMap(), anyMap());
            }
            f.observer.dispatch(f.request, f.id, () -> {
                LaunchConfiguration config = f.launch(); ProcessAuthorization.prepare(config, List.of(f.observer));
                if (mutation.equals("secondRoot")) {
                    f.manager.insert(new ProcessRecord(config, null, null));
                    assertThrows(IllegalStateException.class, () -> f.manager.insert(new ProcessRecord(config, null, null)));
                } else {
                    if (mutation.equals("returnedIdentity")) f.manager.corrupt = true;
                    assertThrows(IllegalStateException.class, () -> f.manager.insert(new ProcessRecord(config, null, null)));
                }
                return null;
            });
            assertEquals(mutation.equals("secondRoot") ? 1 : 0, f.count());
            if (!mutation.equals("secondRoot")) assertNull(f.store.execution(f.id).get("rootProcessId"));
        }
    }

    @Test public void synchronousNativeThrowRestoresScopeAndFollowingOrdinaryApiCreationIsCompletelyNoop() throws Exception {
        try (Fixture f = new Fixture("host", "start")) {
            Object prior = new Object(); f.request.setAttribute(AiResourceChangeProcessObserver.ATTRIBUTE, prior);
            assertThrows(IllegalArgumentException.class, () -> f.observer.dispatch(f.request, f.id, () -> { throw new IllegalArgumentException(); }));
            assertSame(prior, f.request.getAttribute(AiResourceChangeProcessObserver.ATTRIBUTE));
            f.request.setAttribute(AiResourceChangeProcessObserver.ATTRIBUTE, null);
            LaunchConfiguration nativeCall = new LaunchConfiguration("host.evacuate", "host", "4", 6L, 0, Map.of("ordinary", true));
            ProcessRecord record = f.manager.insert(new ProcessRecord(nativeCall, null, null));
            assertEquals("host.evacuate", record.getProcessName()); assertEquals(Map.of("ordinary", true), record.getData());
            assertNull(f.store.execution(f.id).get("rootProcessId")); assertEquals(1, f.count());
            f.observer.dispatch(f.request, f.id, () -> {
                var engine = io.cattle.platform.engine.context.EngineContext.getEngineContext();
                var parent = new io.cattle.platform.engine.process.log.ProcessExecutionLog();
                engine.pushLog(parent);
                try {
                    assertTrue(io.cattle.platform.engine.context.EngineContext.hasParentProcess());
                    f.manager.insert(new ProcessRecord(nativeCall, null, null));
                    assertNull(f.store.execution(f.id).get("rootProcessId"));
                } finally { engine.popLog(); }
                return null;
            });
            assertFalse(io.cattle.platform.engine.context.EngineContext.hasParentProcess()); assertEquals(2, f.count());
            f.manager.setAuthorizationHooks(List.of(new ProcessAuthorizationHook() { }));
            f.manager.insert(new ProcessRecord(nativeCall, null, null)); assertEquals(3, f.count());
        }
    }

    @Test public void serviceOwnSuccessStillRequiresOriginalReadinessAndStoppedOrRetiredInstances() throws Exception {
        for (String operation : List.of("start", "stop")) try (Fixture f = new Fixture("service", operation)) {
            f.dispatch(); f.success(100L); assertEquals("succeeded", f.outcome());
            f.service.setState(operation.equals("start") ? "active" : "inactive");
            var descriptor = AiResourceChangeDescriptor.require("service", operation);
            f.domain.deploymentReadiness = mock(io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness.class);
            when(f.domain.deploymentReadiness.isReady(f.service)).thenReturn(true);
            assertTrue(f.domain.complete(descriptor, f.service, Map.of(), f.snapshot));
            if (operation.equals("start")) {
                when(f.domain.deploymentReadiness.isReady(f.service)).thenReturn(false);
                assertFalse(f.domain.complete(descriptor, f.service, Map.of(), f.snapshot));
            } else {
                var map = new io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord(); map.setManaged(true); map.setInstanceId(8L);
                var instance = new io.cattle.platform.core.model.tables.records.InstanceRecord(); instance.setId(8L); instance.setAccountId(5L);
            instance.setState("running");
                when(f.objects.find(io.cattle.platform.core.model.ServiceExposeMap.class, "serviceId", 3L, "removed", null)).thenReturn(List.of(map));
                when(f.objects.loadResource(io.cattle.platform.core.model.Instance.class, 8L)).thenReturn(instance);
                assertFalse(f.domain.complete(descriptor, f.service, Map.of(), f.snapshot));
            }
        }
    }

    @Test public void realPlanObservationAndsOwnSuccessfulProcessWithExistingServiceTerminalPredicates() throws Exception {
        for (String type : List.of("host", "service")) for (String operation : List.of("start", "stop")) try (Fixture f = new Fixture(type, operation)) {
            f.dispatch(); f.terminal();
            var domain = spy(f.domain); doReturn(f.target()).when(domain).receiptTarget(any(), any(), anyMap());
            domain.deploymentReadiness = mock(io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness.class);
            when(domain.deploymentReadiness.isReady(f.service)).thenReturn(true);
            var service = new AiResourceChangePlanService(); service.domain = domain; service.store = f.store; service.clock = java.time.Clock.systemUTC();
            f.plan.put("status", "accepted"); f.plan.put("dispatchAcknowledged", true); f.plan.put("acceptedAt", System.currentTimeMillis());
            f.store.save(f.plan);
            service.observe(f.plan, f.request); assertNotEquals("succeeded", f.plan.get("status")); assertNotEquals(true, f.plan.get("verifiedResult"));
            f.success(100L);
            if (type.equals("service") && operation.equals("start")) {
                when(domain.deploymentReadiness.isReady(f.service)).thenReturn(false); service.observe(f.plan, f.request);
                assertNotEquals("succeeded", f.plan.get("status")); when(domain.deploymentReadiness.isReady(f.service)).thenReturn(true);
            }
            service.observe(f.plan, f.request); assertEquals("succeeded", f.plan.get("status")); assertEquals(true, f.plan.get("verifiedResult"));
        }
    }

    @Test public void nativeCreationDoesNotQueueAProcessWhenItsAtomicWitnessCallbackFails() throws Exception {
        try (Fixture f = new Fixture("host", "start")) {
            var definition = mock(io.cattle.platform.engine.process.ProcessDefinition.class);
            when(definition.constructProcessState(any())).thenReturn(mock(io.cattle.platform.engine.process.ProcessState.class));
            var field = DefaultProcessManager.class.getDeclaredField("definitions"); field.setAccessible(true);
            field.set(f.manager, Map.of("host.activate", definition));
            f.observer.store = spy(f.store); doReturn(false).when(f.observer.store).attestSessionProcess(anyString(), anyMap(), anyMap());
            f.observer.dispatch(f.request, f.id, () -> {
                LaunchConfiguration config = f.launch(); ProcessAuthorization.prepare(config, List.of(f.observer));
                assertThrows(IllegalStateException.class, () -> f.manager.create(new ProcessRecord(config, null, null))); return null;
            });
            assertEquals(0, f.count()); assertEquals(0, f.manager.queued); assertNull(f.store.execution(f.id).get("rootProcessId"));
        }
    }

    static class InsertManager extends DefaultProcessManager {
        boolean corrupt; int queued;
        io.cattle.platform.engine.process.ProcessInstance create(ProcessRecord record) { return createProcessInstance(record, true, false); }
        @Override protected void queue(io.cattle.platform.engine.process.ProcessInstance process) { queued++; }
        ProcessRecord insert(ProcessRecord record) { return insertProcess(record); }
    }

    static class Fixture implements AutoCloseable {
        final String id = "aichange-" + UUID.randomUUID(); final String type; final String operation;
        final SingleConnectionDataSource dataSource; final org.jooq.DSLContext sql;
        final JacksonJsonMapper json = new JacksonJsonMapper(); final AiResourceChangePlanStore store = new AiResourceChangePlanStore();
        final AiResourceChangeProcessObserver observer = new AiResourceChangeProcessObserver();
            final AiResourceChangeDomain domain = new AiResourceChangeDomain();
        final ObjectManager objects = mock(ObjectManager.class); final Policy owner = mock(Policy.class); final InsertManager manager = new InsertManager();
        final ApiRequest request = new ApiRequest(null, null); final ServiceRecord service = new ServiceRecord();
        final io.cattle.platform.core.model.tables.records.HostRecord host = AiHostLifecycleChangeTest.host();
        final Map<String, Object> plan; final Map<String, Object> snapshot; long next = 100;
        Fixture(String type, String operation) throws Exception {
            this.type = type; this.operation = operation;
            dataSource = new SingleConnectionDataSource(DriverManager.getConnection(
                    "jdbc:h2:mem:cookie-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE"), true);
            var configuration = new DefaultConfiguration().set(new TransactionAwareDataSourceProxy(dataSource)).set(SQLDialect.H2);
            sql = DSL.using(configuration); sql.execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            sql.createSchemaIfNotExists("cattle").execute();
            sql.createTable(PROCESS_INSTANCE).columns(java.util.Arrays.stream(PROCESS_INSTANCE.fields()).map(field -> DSL.field(
                    field.getUnqualifiedName(), field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            store.json = json; store.setConfiguration(configuration); store.transactions = new DataSourceTransactionManager(dataSource);
            observer.store = store; observer.objects = objects; observer.json = json;
            domain.setConfiguration(configuration); domain.objects = objects; domain.json = json; domain.processObserver = observer;
            service.setId(3L); service.setUuid("service-uuid"); service.setKind("service"); service.setAccountId(5L); service.setStackId(9L);
            service.setData(Map.of()); service.setState(operation.equals("start") ? "inactive" : "active");
            host.setState(operation.equals("start") ? "inactive" : "active");
            Object root = type.equals("host") ? host : service;
            when(objects.loadResource(type, 3L)).thenReturn(root); when(objects.loadResource(type, "3")).thenReturn(root);
            when(objects.loadResource(io.cattle.platform.core.model.Account.class, 5L)).thenReturn(AiHostLifecycleChangeTest.project());
            when(owner.getAccountId()).thenReturn(5L); when(owner.getAuthenticatedAsAccountId()).thenReturn(10L);
            snapshot = type.equals("host") ? domain.hostLifecycleSnapshot(host) : domain.serviceLifecycleSnapshot(service);
            plan = new LinkedHashMap<>(Map.of("id", id, "actorId", 10L, "accountId", 5L, "resourceType", type, "operation", operation,
                    "status", "preview", "internalResourceId", 3L, "expiresAt", "2030-01-01T00:00:00Z", "parameters", Map.of(), "body", Map.of()));
            plan.put("snapshot", snapshot); plan.put("processBaseline", 0L); store.insert(plan);
            plan.put("status", "dispatching"); plan.put("operationId", id); assertTrue(store.claim(plan));
            request.setType(type); request.setId("3"); request.setMethod("POST");
            request.setAction(AiResourceChangeDescriptor.require(type, operation).action()); request.setRequestObject(Map.of());
            ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setApiRequest(request);
            ProcessRecordDao dao = mock(ProcessRecordDao.class);
            when(dao.insert(any())).thenAnswer(call -> {
                ProcessRecord result = new ProcessRecord(call.getArgument(0), next++, "server"); insert(result);
                if (manager.corrupt) result.setResourceId("4"); return result;
            });
            var field = DefaultProcessManager.class.getDeclaredField("processRecordDao"); field.setAccessible(true); field.set(manager, dao);
            manager.setAuthorizationHooks(List.of(observer));
        }
        LaunchConfiguration launch() { return new LaunchConfiguration(type + "." + request.getAction(), type, "3", 5L, 0, Map.of()); }
        ProcessRecord dispatch() {
            return observer.dispatch(request, id, () -> {
                LaunchConfiguration config = launch(); ProcessAuthorization.prepare(config, List.of(observer));
                return manager.insert(new ProcessRecord(config, null, null));
            });
        }
        void insert(ProcessRecord record) {
            sql.insertInto(PROCESS_INSTANCE)
                    .set(PROCESS_INSTANCE.ID, record.getId()).set(PROCESS_INSTANCE.ACCOUNT_ID, ((Number) record.getAccountId()).longValue())
                    .set(PROCESS_INSTANCE.RESOURCE_TYPE, record.getResourceType()).set(PROCESS_INSTANCE.RESOURCE_ID, record.getResourceId())
                    .set(PROCESS_INSTANCE.PROCESS_NAME, record.getProcessName()).set(PROCESS_INSTANCE.DATA, record.getData()).execute();
        }
        void success(long id) {
            sql.update(PROCESS_INSTANCE).set(PROCESS_INSTANCE.END_TIME, new Date()).set(PROCESS_INSTANCE.RESULT, "SUCCESS")
                    .set(PROCESS_INSTANCE.PHASE, "DONE").set(PROCESS_INSTANCE.EXIT_REASON, "DONE").where(PROCESS_INSTANCE.ID.eq(id)).execute();
        }
        Object target() { return type.equals("host") ? host : service; }
        void terminal() {
            host.setState(operation.equals("start") ? "active" : "inactive");
            service.setState(operation.equals("stop") ? "inactive" : operation.equals("cancel_upgrade") ? "canceled-upgrade" : "active");
        }
        Map<String, Object> observe() {
            var current = new LinkedHashMap<>(plan); current.put("status", "accepted"); current.put("dispatchAcknowledged", true);
            current.put("acceptedAt", System.currentTimeMillis()); store.save(current);
            var service = new AiResourceChangePlanService(); service.domain = spy(domain);
            doReturn(target()).when(service.domain).receiptTarget(any(), any(), anyMap());
            service.store = store; service.clock = java.time.Clock.systemUTC(); service.observe(current, request); return current;
        }
        String outcome() { return type.equals("host") ? domain.hostLifecycleProcessOutcome(plan, host) : domain.upgradeLifecycleProcessOutcome(plan, service); }
        int count() { return sql.fetchCount(PROCESS_INSTANCE); }
        @Override public void close() { ApiContext.remove(); dataSource.destroy(); }
    }
}
