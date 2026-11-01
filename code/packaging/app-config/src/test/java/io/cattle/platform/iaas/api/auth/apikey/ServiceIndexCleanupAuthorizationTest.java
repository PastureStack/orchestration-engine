package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ServiceIndexTable.SERVICE_INDEX;

import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.*;
import io.cattle.platform.core.model.tables.records.*;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.cattle.platform.servicediscovery.deployment.DeploymentUnitInstanceIdGenerator;
import io.cattle.platform.servicediscovery.deployment.ServiceDeploymentPlanner;
import io.cattle.platform.servicediscovery.deployment.impl.DeploymentManagerImpl;
import io.cattle.platform.servicediscovery.deployment.impl.unit.DeploymentUnit;
import io.cattle.platform.servicediscovery.service.impl.ServiceDiscoveryServiceImpl;
import io.cattle.platform.servicediscovery.process.ServiceRemove;
import io.cattle.platform.servicediscovery.process.ServiceSuffixRemove;
import io.cattle.platform.servicediscovery.process.StackRemove;
import io.cattle.platform.servicediscovery.deployment.DeploymentManager;
import io.cattle.platform.servicediscovery.upgrade.UpgradeManager;
import io.cattle.platform.core.dao.NetworkDao;
import io.cattle.platform.network.impl.NetworkServiceImpl;
import io.cattle.platform.resource.pool.ResourcePoolManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/** Native owner scheduling with the real queued Key hook, without HTTP context. */
public class ServiceIndexCleanupAuthorizationTest {
    ApiKeyProcessAuthorization hook;
    ObjectManager objects;
    ObjectProcessManager processes;
    CredentialRecord key;
    AccountRecord project;
    ServiceRecord service;
    StackRecord stack;
    ServiceIndexRecord used;
    ServiceIndexRecord unused;
    Schema schema;
    Map<String, Object> binding;
    final List<ProcessRecord> scheduled = new ArrayList<>();
    final Instant now = Instant.parse("2026-10-30T06:05:30Z");

    @Before public void setup() {
        ApiContext.remove();
        objects = mock(ObjectManager.class); processes = mock(ObjectProcessManager.class);
        hook = new ApiKeyProcessAuthorization(); hook.objectManager = objects;
        hook.authenticator = mock(ApiAuthenticator.class); hook.clock = Clock.fixed(now, ZoneOffset.UTC);
        var factory = mock(SchemaFactory.class); schema = mock(Schema.class);
        when(schema.getResourceMethods()).thenReturn(List.of("PUT", "DELETE"));
        when(factory.getSchema("service")).thenReturn(schema); when(factory.getSchema("stack")).thenReturn(schema);
        when(factory.getBaseType("project")).thenReturn("account");
        when(factory.getBaseType("stack")).thenReturn("environment");
        when(objects.getSchemaFactory()).thenReturn(factory);
        project = new AccountRecord(); project.setId(5L); project.setKind("project"); project.setState("active");
        var principal = new AccountRecord(); principal.setId(1L);
        var owner = new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions());
        when(hook.authenticator.currentAuthorization(eq(1L), eq(5L), any()))
                .thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, factory));
        var targets = new ApiKeyTargetResolver(); targets.objectManager = objects;
        var roles = mock(AuthDao.class); var accounts = mock(AccountDao.class);
        when(roles.hasAccessToProject(eq(5L), eq(5L), eq(false), any())).thenReturn(true);
        when(accounts.isActiveAccount(project)).thenReturn(true);
        targets.projectAccess = new ProjectAccessService(roles, accounts);
        var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(factory);
        formatter.setTypeMappings(Map.of("credential", "c", "account", "a", "environment", "st",
                "service", "s", "serviceIndex", "si"));
        targets.idFormatter = formatter; hook.targets = targets;
        key = new CredentialRecord(); key.setId(1L); key.setAccountId(1L);
        key.setKind("apiKeyRestricted"); key.setState("active");
        when(objects.loadResource(Credential.class, 1L)).thenReturn(key);
        when(objects.loadResource(Account.class, 5L)).thenReturn(project);
        stack = new StackRecord(); stack.setId(958L); stack.setUuid("stack-958-original");
        stack.setAccountId(5L); stack.setState("active"); stack.setKind("stack");
        service = new ServiceRecord(); service.setId(821L); service.setUuid("b6c5e48e-1780-40ab-9958-3caed8b82904");
        service.setAccountId(5L); service.setStackId(958L); service.setState("updating-active"); service.setKind("service");
        used = index(971L, "a245151a-1612-4267-87c4-e9059992e222");
        unused = index(972L, "6e9def0d-ba4f-4904-96fa-34225c3c88b4");
        when(objects.loadResource("service", 821L)).thenReturn(service);
        when(objects.loadResource(Service.class, 821L)).thenReturn(service);
        when(objects.loadResource("stack", 958L)).thenReturn(stack);
        when(objects.loadResource(Stack.class, 958L)).thenReturn(stack);
        when(objects.loadResource("serviceIndex", "972")).thenReturn(unused);
        when(objects.loadResource(ServiceIndex.class, 972L)).thenReturn(unused);
        when(objects.find(ServiceIndex.class, SERVICE_INDEX.SERVICE_ID, 821L, SERVICE_INDEX.REMOVED, null))
                .thenReturn(List.of(used, unused));
        binding = new LinkedHashMap<>(Map.of("keyId", "1c1", "policyRevision", 1L, "principalAccountId", 1L,
                "accountId", 5L, "operation", "update", "targetType", "service", "targetId", "821",
                "requestMethod", "PUT", "requestCollection", false));
        binding.put("preview", false); binding.put("requestId", "aichange-scale-down-service-index");
        policy(List.of(), now.plusSeconds(3600), 1);
        doAnswer(call -> {
            ServiceIndex index = call.getArgument(1);
            var launch = new LaunchConfiguration("serviceindex.remove", "serviceIndex", index.getId().toString(), 5L, 0, Map.of());
            ProcessAuthorization.prepare(launch, List.of());
            var record = new ProcessRecord(launch, null, "offline-native-owner");
            hook.beforeExecution(record, ProcessAuthorization.metadata(record));
            record.setId(1112849L); scheduled.add(record);
            return null;
        }).when(processes).scheduleStandardProcessAsync(eq(StandardProcess.REMOVE), any(ServiceIndex.class), isNull());
    }

    private ServiceIndexRecord index(long id, String uuid) {
        var index = new ServiceIndexRecord(); index.setId(id); index.setUuid(uuid);
        index.setAccountId(5L); index.setServiceId(821L); index.setState("requested"); index.setLaunchConfigName("primary");
        return index;
    }

    private void policy(List<ApiKeyPolicy.Rule> extra, Instant expires, long revision) {
        var rules = new ArrayList<ApiKeyPolicy.Rule>();
        rules.add(new ApiKeyPolicy.Rule("project", ApiKeyPolicy.Effect.ALLOW,
                ApiKeyPolicy.Scope.project("1a5"), Set.of("read", "create", "update", "delete")));
        rules.addAll(extra);
        key.setData(new ApiKeyPolicyCodec().store(key, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM,
                ApiKeyPolicy.Effect.DENY, expires, rules, ApiKeyPolicy.Purpose.AI_BRIDGE), revision));
    }

    private void owner(String process, Runnable action) {
        var engine = EngineContext.getEngineContext(); engine.pushAuthorization(binding);
        var frame = engine.pushVerifiedExecution("service", "821", process, binding);
        try { action.run(); }
        finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
    }

    private ServiceDeploymentPlanner planner() throws Exception {
        var deployment = new DeploymentManagerImpl();
        set(deployment, "objectMgr", objects); set(deployment, "objectProcessMgr", processes);
        return new ServiceDeploymentPlanner(service, List.of(), deployment.new DeploymentServiceContext(), stack) {
            @Override protected Map<String, List<Long>> getUsedServiceIndexesIds(boolean cleanup) {
                assertTrue(cleanup); return Map.of("primary", List.of(971L));
            }
            @Override protected List<DeploymentUnit> deployHealthyUnits(DeploymentUnitInstanceIdGenerator generator) { return List.of(); }
            @Override protected boolean needToReconcileDeploymentImpl() { return false; }
        };
    }

    private static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException next) { }
        }
        throw new NoSuchFieldException(name);
    }

    @Test public void scaleDownPlannerSchedulesOnlyUnusedIndexUnderOriginalUpdate() throws Exception {
        var planner = planner();
        owner("service.update", planner::cleanupUnusedAndDuplicatedServiceIndexes);
        assertEquals(1, scheduled.size());
        assertEquals("972", scheduled.getFirst().getResourceId());
        assertEquals(binding, ProcessAuthorization.metadata(scheduled.getFirst()));
        assertNotNull(ProcessAuthorization.verifiedChild(scheduled.getFirst()));
        assertNull(ApiContext.getContext());
        verify(processes, never()).scheduleStandardProcessAsync(StandardProcess.REMOVE, used, null);
    }

    private ProcessRecord queued() throws Exception {
        owner("service.update", planner()::cleanupUnusedAndDuplicatedServiceIndexes);
        ProcessRecord original = scheduled.getLast();
        // Model the saved process document: neither a live frame nor mutable
        // Java object identity is available to the detached worker.
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> data = mapper.readValue(mapper.writeValueAsBytes(original.getData()),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        return new ProcessRecord(new LaunchConfiguration(original.getProcessName(), original.getResourceType(),
                original.getResourceId(), 5L, 0, data), original.getId(), "detached-worker");
    }

    private void authorize(ProcessRecord record) {
        assertNull(ApiContext.getContext());
        hook.beforeExecution(record, ProcessAuthorization.metadata(record));
    }

    private void denied(ProcessRecord record, String code) {
        assertEquals(code, assertThrows(ProcessAuthorizationDeniedException.class, () -> authorize(record)).getCode());
    }

    @Test public void savedWorkerPinsIndexServiceStackAndAccountWithoutBorrowingOtherParents() throws Exception {
        ProcessRecord record = queued(); authorize(record);
        assertEquals(7, ProcessAuthorization.verifiedChild(record).references().size());
        for (String field : List.of("indexUuid", "serviceUuid", "stackUuid", "serviceId", "stackId", "accountId")) {
            String indexUuid = unused.getUuid(), serviceUuid = service.getUuid(), stackUuid = stack.getUuid();
            switch (field) {
            case "indexUuid" -> unused.setUuid("same-id-replaced-index");
            case "serviceUuid" -> service.setUuid("same-id-replaced-service");
            case "stackUuid" -> stack.setUuid("same-id-replaced-stack");
            case "serviceId" -> unused.setServiceId(822L);
            case "stackId" -> service.setStackId(959L);
            case "accountId" -> unused.setAccountId(6L);
            default -> throw new AssertionError(field);
            }
            denied(record, field.equals("accountId") ? "OwnerPermissionDenied" : "KeyScopeDenied");
            unused.setUuid(indexUuid); service.setUuid(serviceUuid); stack.setUuid(stackUuid);
            unused.setServiceId(821L); service.setStackId(958L); unused.setAccountId(5L);
        }
        authorize(record);
        assertTrue(ProcessAuthorization.verifiedChild(record).references().values().stream()
                .noneMatch(value -> value.contains("secret")));
    }

    @Test public void queuedWorkerRechecksRevisionExpiryRevocationRoleAndEveryExplicitDenial() throws Exception {
        ProcessRecord record = queued();
        key.setState("inactive"); denied(record, "ApiKeyRevoked"); key.setState("active");
        policy(List.of(), now.plusSeconds(3600), 2); denied(record, "ApiKeyPolicyChanged");
        policy(List.of(), now.minusSeconds(1), 1); denied(record, "ApiKeyExpired");
        policy(List.of(), now.plusSeconds(3600), 1);
        when(schema.getResourceMethods()).thenReturn(List.of()); denied(record, "OwnerPermissionDenied");
        when(schema.getResourceMethods()).thenReturn(List.of("PUT", "DELETE"));
        for (var scope : List.of(ApiKeyPolicy.Scope.resource("serviceIndex", "1si972"),
                ApiKeyPolicy.Scope.resource("service", "1s821"), ApiKeyPolicy.Scope.stack("1st958"))) {
            policy(List.of(new ApiKeyPolicy.Rule("deny-exact", ApiKeyPolicy.Effect.DENY, scope, Set.of("update"))),
                    now.plusSeconds(3600), 1);
            denied(record, "KeyPolicyDenied");
        }
        policy(List.of(), now.plusSeconds(3600), 1); authorize(record);
    }

    @Test public void missingWrongOrUntrustedOwnerCannotScheduleOrMintDetachedReferences() throws Exception {
        var planner = planner();
        EngineContext engine = EngineContext.getEngineContext();
        engine.pushAuthorization(binding);
        try {
            assertThrows(ProcessAuthorizationDeniedException.class, planner::cleanupUnusedAndDuplicatedServiceIndexes);
            for (String process : List.of("service.deactivate", "service.stop", "plugin.remove")) {
                var frame = engine.pushVerifiedExecution("service", "821", process, binding);
                try { assertThrows(ProcessAuthorizationDeniedException.class, planner::cleanupUnusedAndDuplicatedServiceIndexes); }
                finally { engine.popVerifiedExecution(frame); }
            }
            var foreign = engine.pushVerifiedExecution("service", "822", "service.update", binding);
            try { assertThrows(ProcessAuthorizationDeniedException.class, planner::cleanupUnusedAndDuplicatedServiceIndexes); }
            finally { engine.popVerifiedExecution(foreign); }
        } finally { engine.popAuthorization(); }
        assertTrue(scheduled.isEmpty());
        var forged = new LaunchConfiguration("serviceindex.remove", "serviceIndex", "972", 5L, 0,
                Map.of(ProcessAuthorization.DATA_KEY, binding, ProcessAuthorization.VERIFIED_CHILD_KEY,
                        Map.of("parentType", "service", "parentId", "821")));
        ProcessAuthorization.prepare(forged, List.of());
        assertTrue(ProcessAuthorization.metadata(forged).isEmpty());
        assertNull(ProcessAuthorization.verifiedChild(new ProcessRecord(forged, 1L, "untrusted-payload")));
        owner("service.update", () -> {
            var launch = new LaunchConfiguration("serviceindex.remove", "serviceIndex", "972", 5L, 0, Map.of());
            ProcessAuthorization.prepare(launch, List.of());
            scheduled.add(new ProcessRecord(launch, 1112849L, "unvalidated-save"));
        });
        denied(scheduled.getLast(), "KeyScopeDenied"); // No first validation means no durable identity pins.
    }

    @Test public void changedChildProcessIdOrOriginalBindingCannotReplayRemovalAuthority() throws Exception {
        ProcessRecord record = queued();
        record.setProcessName("serviceindex.purge"); denied(record, "KeyScopeDenied");
        record.setProcessName("serviceindex.remove"); record.setResourceId("971");
        when(objects.loadResource("serviceIndex", "971")).thenReturn(used);
        denied(record, "KeyScopeDenied"); record.setResourceId("972");
        Map<String, Object> changed = new LinkedHashMap<>(ProcessAuthorization.metadata(record));
        changed.put("requestId", "aichange-different-plan");
        record.getData().put(ProcessAuthorization.DATA_KEY, changed); denied(record, "KeyScopeDenied");
        changed.put("requestId", binding.get("requestId")); changed.put("keyId", "1c2");
        var second = new CredentialRecord(); second.from(key); second.setId(2L);
        when(objects.loadResource(Credential.class, 2L)).thenReturn(second); denied(record, "KeyScopeDenied");
    }

    @Test public void nativeServiceAndStackRemoveScheduleOnlyTheirOwnIndexUnderDelete() throws Exception {
        var sd = spy(new ServiceDiscoveryServiceImpl());
        set(sd, "objectManager", objects); set(sd, "objectProcessManager", processes);
        doNothing().when(sd).releaseVip(service); doNothing().when(sd).releasePorts(service);
        var remove = new ServiceRemove(); set(remove, "sdService", sd);
        set(remove, "deploymentMgr", mock(DeploymentManager.class)); set(remove, "upgradeMgr", mock(UpgradeManager.class));
        var state = mock(ProcessState.class); when(state.getResource()).thenReturn(service);
        when(objects.find(ServiceIndex.class, SERVICE_INDEX.SERVICE_ID, 821L, SERVICE_INDEX.REMOVED, null)).thenReturn(List.of(unused));
        binding.put("requestMethod", "DELETE"); binding.put("operation", "delete"); service.setState("removing");
        owner("service.remove", () -> remove.handle(state, null));
        assertEquals("delete", ProcessAuthorization.metadata(scheduled.getLast()).get("operation"));
        binding.put("targetType", "stack"); binding.put("targetId", "958"); stack.setState("removing");
        var stackRemove = new StackRemove(); set(stackRemove, "objectManager", objects);
        set(stackRemove, "objectProcessManager", processes);
        when(objects.find(Service.class, io.cattle.platform.core.model.tables.ServiceTable.SERVICE.STACK_ID,
                958L, io.cattle.platform.core.model.tables.ServiceTable.SERVICE.REMOVED, null)).thenReturn(List.of(service));
        doAnswer(call -> { owner("service.remove", () -> remove.handle(state, null)); return null; })
                .when(processes).scheduleStandardProcess(StandardProcess.REMOVE, service, null);
        var stackState = mock(ProcessState.class); when(stackState.getResource()).thenReturn(stack);
        stackRemove.handle(stackState, null);
        assertEquals(2, scheduled.size());
        assertEquals("stack", ProcessAuthorization.metadata(scheduled.getLast()).get("targetType"));
        authorize(scheduled.getLast());
    }

    @Test public void fullLegacyAndNoKeyKeepTheirExistingOwnerPath() throws Exception {
        var record = new ProcessRecord(new LaunchConfiguration("serviceindex.remove", "serviceIndex", "972", 5L, 0,
                Map.of(ProcessAuthorization.DATA_KEY, binding)), null, "ordinary-owner");
        key.setData(new ApiKeyPolicyCodec().store(key, new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,
                ApiKeyPolicy.Effect.ALLOW, null, List.of()), 1));
        authorize(record);
        key.setKind("apiKey"); key.setData(Map.of()); binding.put("policyRevision", 0L); authorize(record);
        var noKey = new LaunchConfiguration("serviceindex.remove", "serviceIndex", "972", 5L, 0, Map.of());
        ProcessAuthorization.prepare(noKey, List.of()); hook.beforeExecution(noKey, ProcessAuthorization.metadata(noKey));
        when(schema.getResourceMethods()).thenReturn(List.of()); denied(record, "OwnerPermissionDenied");
    }

    @Test public void nativeIndexHandlerReleasesOnlyItsPoolAllocationWithOrWithoutAddress() throws Exception {
        var record = queued(); authorize(record);
        var pool = mock(ResourcePoolManager.class); var networkDao = mock(NetworkDao.class);
        var network = new NetworkRecord(); network.setId(4L); network.setAccountId(5L);
        var subnet = new SubnetRecord(); subnet.setId(8L); subnet.setNetworkId(4L);
        var networks = spy(new NetworkServiceImpl()); set(networks, "poolManager", pool); set(networks, "networkDao", networkDao);
        doReturn("managed").when(networks).getNetworkMode(any());
        doReturn(network).when(networks).resolveNetwork(5L, "managed");
        when(networkDao.getSubnets(network)).thenReturn(List.of(subnet));
        var sd = new ServiceDiscoveryServiceImpl(); set(sd, "networkService", networks);
        var handler = new ServiceSuffixRemove(); set(handler, "objectManager", objects); set(handler, "sdService", sd);
        var state = mock(ProcessState.class); when(state.getResource()).thenReturn(unused);
        handler.handle(state, null); verifyNoInteractions(pool); verifyNoInteractions(networks);
        unused.setAddress("10.42.0.99"); handler.handle(state, null);
        verify(pool).releaseResource(subnet, unused);
        verify(processes, times(1)).scheduleStandardProcessAsync(StandardProcess.REMOVE, unused, null);
        verifyNoMoreInteractions(processes);
    }
}
