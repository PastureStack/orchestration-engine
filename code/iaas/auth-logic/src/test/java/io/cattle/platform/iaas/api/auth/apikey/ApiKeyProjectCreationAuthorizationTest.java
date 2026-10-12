package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.NetworkRecord;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.core.model.tables.records.ProjectMemberRecord;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.iaas.api.auth.projects.ProjectCreationScope;
import io.cattle.platform.iaas.api.auth.projects.ProjectMemberResourceManager;
import io.cattle.platform.iaas.api.auth.projects.ProjectResourceManager;
import io.cattle.platform.iaas.api.object.postinit.AccountFieldPostInitHandler;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.cattle.platform.object.postinit.ObjectPostInstantiationHandler;
import io.cattle.platform.object.postinit.SetPropertiesPostInstantiationHandler;
import io.cattle.platform.object.util.ObjectLaunchConfigurationUtils;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.AbstractSchemaPostProcessor;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Creation authority is bound by the real manager; ordinary ownership is not bypassed. */
public class ApiKeyProjectCreationAuthorizationTest {
    private final AccountRecord principal = account(7, "user", "active");
    private final AccountRecord project = account(9, "project", "registering");
    private final ProjectMemberRecord member = new ProjectMemberRecord();
    private final AtomicBoolean ownerMembership = new AtomicBoolean();
    private final AtomicInteger sideEffects = new AtomicInteger();
    private final AuthDao authDao = mock(AuthDao.class);
    private final AccountDao accountDao = mock(AccountDao.class);
    private final ObjectManager objects = mock(ObjectManager.class);
    private final ObjectProcessManager processes = mock(ObjectProcessManager.class);
    private final ProjectMemberResourceManager members = mock(ProjectMemberResourceManager.class);
    private final CredentialRecord credential = new CredentialRecord();
    private final ApiKeyProcessAuthorization worker = new ApiKeyProcessAuthorization();
    private final ExposedProjectManager manager = new ExposedProjectManager();
    private final List<ProcessRecord> jobs = new ArrayList<>();
    private SchemaFactoryImpl schemas;
    private LocalAuthenticator authenticator;
    private ProjectAccessService projectAccess;
    private DefaultIdFormatter formatter;
    private String rootProcess = "account.create";
    private boolean failDuringSchedule;
    private boolean executeDuringCapture;

    @Before public void setup() throws Exception {
        schemas = new SchemaFactoryImpl();
        schemas.setId("local-user");
        schemas.setPostProcessors(List.of(new AbstractSchemaPostProcessor() {
            @Override public SchemaImpl postProcessRegister(SchemaImpl schema, SchemaFactory parent) {
                if (schema.getId().endsWith("Record")) schema.setId(schema.getId().substring(0, schema.getId().length() - 6));
                return schema;
            }
        }));
        schemas.setTypes(List.of(AccountRecord.class, CredentialRecord.class, ProjectMemberRecord.class, NetworkRecord.class));
        schemas.setTypeNames(List.of("project,parent=account", "apiKey,parent=credential"));
        schemas.init();
        ((SchemaImpl) schemas.getSchema("project")).setCollectionMethods(List.of("GET", "POST"));
        ((SchemaImpl) schemas.getSchema("project")).setResourceMethods(List.of("GET", "PUT", "DELETE"));
        formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
        when(authDao.getAccountById(7L)).thenReturn(principal);
        when(authDao.getAccountById(9L)).thenReturn(project);
        when(accountDao.isActiveAccount(principal)).thenReturn(true);
        when(accountDao.isActiveAccount(project)).thenReturn(false);
        when(authDao.hasAccessToProject(eq(9L), eq(7L), eq(false), anySet())).thenAnswer(call -> ownerMembership.get());
        when(authDao.isProjectOwner(eq(9L), eq(7L), anyBoolean(), anySet())).thenAnswer(call ->
                Boolean.TRUE.equals(call.getArgument(2)) || ownerMembership.get());
        ownerMembership.set(true); // Detached positive work must have live ownership.
        when(authDao.canSetProjectMembers(eq(9L), eq(7L), eq(false), anySet())).thenAnswer(call -> ownerMembership.get());
        authenticator = new LocalAuthenticator(authDao, accountDao, schemas);
        projectAccess = new ProjectAccessService(authDao, accountDao);
        credential.setId(1L); credential.setAccountId(7L); credential.setKind("apiKey");
        credential.setState("active"); credential.setData(new HashMap<>());
        member.setId(3L); member.setProjectId(9L); member.setAccountId(7L);
        member.setState("registering"); member.setRole("owner");
        member.setExternalId("7"); member.setExternalIdType("rancher_id");
        when(objects.getSchemaFactory()).thenReturn(schemas);
        when(objects.loadResource(Credential.class, 1L)).thenReturn(credential);
        when(objects.loadResource("project", 9L)).thenReturn(project);
        when(objects.loadResource("account", "9")).thenReturn(project);
        when(objects.loadResource("projectMember", "3")).thenReturn(member);
        when(objects.reload(project)).thenReturn(project);
        when(objects.convertToPropertiesFor(any(), anyMap())).thenAnswer(call -> new HashMap<>(call.getArgument(1)));
        when(objects.create(eq(AccountRecord.class), anyMap())).thenAnswer(call -> {
            assertNull("No input ID may bind the scope before the actual create returns", ProjectCreationScope.currentProjectId());
            assertEquals("project", ((Map<?, ?>) call.getArgument(1)).get("kind"));
            return project;
        });
        doAnswer(call -> {
            assertEquals(Long.valueOf(9), ProjectCreationScope.currentProjectId());
            captureJob(rootProcess, project);
            if (failDuringSchedule) throw new IllegalStateException("test schedule failure");
            return null;
        }).when(processes).scheduleStandardProcess(eq(StandardProcess.CREATE), same(project), anyMap());
        when(members.setMembers(same(project), nullable(List.class), eq(true))).thenAnswer(call -> {
            assertEquals("The same manager scope must cover initial membership", Long.valueOf(9), ProjectCreationScope.currentProjectId());
            captureJob("projectmember.create", member);
            return List.of(member);
        });
        manager.setObjectManager(objects); manager.setObjectProcessManager(processes);
        inject(ProjectResourceManager.class, manager, "objectManager", objects);
        inject(ProjectResourceManager.class, manager, "authDao", authDao);
        inject(ProjectResourceManager.class, manager, "projectMemberResourceManager", members);
        worker.objectManager = objects; worker.authenticator = authenticator;
        worker.targets = new ApiKeyTargetResolver(); worker.targets.idFormatter = formatter;
        worker.targets.objectManager = objects; worker.targets.projectAccess = projectAccess;
        worker.clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC);
    }

    @After public void cleanup() {
        ApiContext.remove();
        assertNull("Manager must restore the creation scope", ProjectCreationScope.currentProjectId());
    }

    @Test public void legacyCreationAuthorityAllowsRootAndInitialMemberBeforeMembershipExists() {
        ownerMembership.set(false); executeDuringCapture = true;
        capture(false);
        assertNull(projectAccess.authorize(project, livePersonalPolicy()));
        assertEquals(2, sideEffects.get());
    }

    @Test public void fullCreationAuthorityAllowsRootAndInitialMemberBeforeMembershipExists() {
        ownerMembership.set(false); executeDuringCapture = true;
        capture(true);
        assertNull(projectAccess.authorize(project, livePersonalPolicy()));
        assertEquals(2, sideEffects.get());
    }

    @Test public void personalAccountStampedInitialMemberRetainsFullCreationAuthority() {
        realInitialMemberCreation(true, 7L);
    }

    @Test public void personalAccountStampedInitialMemberRetainsLegacyCreationAuthority() {
        realInitialMemberCreation(false, 7L);
    }

    @Test public void allAccountCallerPreservesExplicitProjectStampButCannotSubstitutePersonalStamp() {
        authenticator.allAccounts = true;
        ProjectMemberRecord created = realInitialMemberCreation(true, 9L);
        created.setAccountId(7L);
        assertDenied(jobs.get(1), "OwnerPermissionDenied");
    }

    @Test public void personalCallerCannotSubstituteThePrivilegedProjectAccountStamp() {
        capture(true); member.setAccountId(9L);
        assertDenied(jobs.get(1), "OwnerPermissionDenied");
    }

    private ProjectMemberRecord realInitialMemberCreation(boolean full, long expectedAccount) {
        executeDuringCapture = true;
        java.util.concurrent.atomic.AtomicReference<ProjectMemberRecord> result = new java.util.concurrent.atomic.AtomicReference<>();
        when(members.setMembers(same(project), nullable(List.class), eq(true))).thenAnswer(call -> {
            assertEquals(Long.valueOf(9), ProjectCreationScope.currentProjectId());
            // AuthDao supplies both parent fields, then the real API post-init
            // stamp replaces accountId with the current personal namespace.
            Map<String, Object> properties = Map.of("projectId", 9L, "accountId", 9L,
                    "externalId", "7", "externalIdType", "rancher_id", "role", "owner");
            ProjectMemberRecord created = new ProjectMemberRecord();
            for (ObjectPostInstantiationHandler handler : CollectionUtils.orderList(ObjectPostInstantiationHandler.class,
                    List.of(new SetPropertiesPostInstantiationHandler(), new AccountFieldPostInitHandler()))) {
                created = handler.postProcess(created, ProjectMemberRecord.class, properties);
            }
            assertNull("The model has no ID until database insertion", created.getId());
            assertEquals(Long.valueOf(9), created.getProjectId());
            assertEquals(Long.valueOf(expectedAccount), created.getAccountId());
            created.setId(3L); created.setState("registering"); // Database IO boundary only.
            result.set(created);
            when(objects.loadResource("projectMember", "3")).thenReturn(created);
            captureJob("projectmember.create", created);
            return List.of(created);
        });
        capture(full);
        assertEquals("The initial owner-member job must retain the captured creation authority", 2, sideEffects.get());
        return result.get();
    }

    @Test public void existingMembershipDoesNotTurnPersonalPolicyIntoProjectPolicy() {
        ownerMembership.set(true); capture(true);
        Policy policy = livePersonalPolicy();
        assertFalse(policy.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS));
        assertNull(policy.authorizeObject(project));
        assertSame(project, projectAccess.authorize(project, policy));
        assertNull("No request-local grant may substitute for live worker authorization", policy.authorizeObject(project));
        runAll();
    }

    @Test public void realAccountConfigurationKeepsExplicitNullThroughJsonPersistence() throws Exception {
        capture(true);
        ProcessRecord root = jobs.get(0);
        assertEquals("account", root.getResourceType()); assertNull(root.getAccountId());
        JacksonJsonMapper mapper = new JacksonJsonMapper();
        root.setData(mapper.readValue(mapper.writeValueAsString(root.getData()), Map.class));
        Map<?, ?> proof = (Map<?, ?>) root.getData().get(ProcessAuthorization.CAPTURED_EXECUTION_KEY);
        assertTrue(proof.containsKey("accountId")); assertNull(proof.get("accountId"));
        assertTrue(ProcessAuthorization.executionBound(root)); run(root);
    }

    @Test public void payloadCannotChooseTheCreationRootOrSupplyTheMarker() {
        capture(true, request -> request.setRequestObject(Map.of("id", "10", "projectCreation", true,
                ProcessAuthorization.DATA_KEY, Map.of("projectCreation", true, "targetId", "10"))));
        for (ProcessRecord job : jobs) {
            assertEquals("9", ProcessAuthorization.metadata(job).get("targetId"));
            assertEquals(Boolean.TRUE, ProcessAuthorization.metadata(job).get("projectCreation"));
        }
        runAll();
    }

    @Test public void managerRestoresScopeWhenSchedulingThrows() {
        failDuringSchedule = true;
        assertThrows(IllegalStateException.class, () -> capture(true));
        assertNull(ProjectCreationScope.currentProjectId()); assertEquals(1, jobs.size());
        assertEquals(0, sideEffects.get()); verify(members, never()).setMembers(any(), any(), anyBoolean());
    }

    @Test public void existingOwnerWithoutManagerScopeStillCannotRunNewProjectJob() {
        ownerMembership.set(true);
        for (boolean full : List.of(false, true)) {
            beginRequest(full, request -> {});
            ProcessRecord job;
            try { job = captureJob("account.create", project); } finally { ApiContext.remove(); }
            assertFalse(ProcessAuthorization.metadata(job).containsKey("projectCreation"));
            assertDenied(job, "OwnerPermissionDenied");
        }
    }

    @Test public void rawLaunchConfigurationCannotBorrowCapturedMarkerAndBinding() {
        capture(true);
        LaunchConfiguration forged = new LaunchConfiguration(jobs.get(0));
        assertFalse(ProcessAuthorization.executionBound(forged)); assertDenied(forged, "KeyScopeDenied");
    }

    @Test public void nonCollectionMethodActionLinkOrTypeCannotAcquireCreationMarker() {
        List<Consumer<ApiRequest>> requests = List.of(
                request -> request.setId("9"), request -> request.setMethod("PUT"),
                request -> request.setAction("activate"), request -> request.setLink("certificate"),
                request -> request.setType("account"));
        for (Consumer<ApiRequest> change : requests) {
            capture(true, change);
            assertFalse(ProcessAuthorization.metadata(jobs.get(0)).containsKey("projectCreation"));
            assertDenied(jobs.get(0), null);
        }
    }

    @Test public void initialMemberMustBelongToTheSameProjectInBothForeignKeys() {
        capture(true); member.setProjectId(10L); assertDenied(jobs.get(1), "OwnerPermissionDenied");
        member.setProjectId(9L); member.setAccountId(10L); assertDenied(jobs.get(1), "OwnerPermissionDenied");
    }

    @Test public void removedMemberAndChangedMemberIdentityAreNotCreationDependencies() {
        capture(true); member.setRemoved(new Date()); assertDenied(jobs.get(1), "OwnerPermissionDenied");
        member.setRemoved(null); member.setId(4L); assertDenied(jobs.get(1), "OwnerPermissionDenied");
    }

    @Test public void removedWrongKindOrChangedRootIdentityIsDenied() {
        capture(true); project.setRemoved(new Date()); assertDenied(jobs.get(0), "OwnerPermissionDenied");
        project.setRemoved(null); project.setKind("user"); assertDenied(jobs.get(0), "OwnerPermissionDenied");
        project.setKind("project"); project.setId(10L); assertDenied(jobs.get(0), "OwnerPermissionDenied");
    }

    @Test public void otherProcessCannotBorrowCreationAuthority() {
        rootProcess = "account.remove"; capture(true);
        assertTrue(ProcessAuthorization.executionBound(jobs.get(0))); assertDenied(jobs.get(0), "OwnerPermissionDenied");
    }

    @Test public void changedJobRootCannotBorrowTheOriginalBinding() {
        capture(true); jobs.get(0).setResourceId("10");
        assertFalse(ProcessAuthorization.executionBound(jobs.get(0))); assertDenied(jobs.get(0), "OwnerPermissionDenied");
    }

    @Test public void livePrincipalAndCredentialRevocationRemainMandatory() {
        capture(true); when(accountDao.isActiveAccount(principal)).thenReturn(false);
        assertDenied(jobs.get(0), "OwnerPermissionDenied");
        when(accountDao.isActiveAccount(principal)).thenReturn(true); credential.setState("removed");
        assertDenied(jobs.get(0), "ApiKeyRevoked");
    }

    @Test public void liveKeyOwnerAndRevisionRemainMandatory() {
        capture(true); credential.setAccountId(8L); assertDenied(jobs.get(0), "OwnerPermissionDenied");
        credential.setAccountId(7L); credential.setData(new ApiKeyPolicyCodec().store(credential, fullPolicy(null), 2));
        assertDenied(jobs.get(0), "ApiKeyPolicyChanged");
    }

    @Test public void fullKeyExpiryIsRecheckedForRootAndMember() {
        capture(true);
        credential.setData(new ApiKeyPolicyCodec().store(credential, fullPolicy(Instant.parse("2026-10-12T00:00:00Z")), 1));
        for (ProcessRecord job : jobs) assertDenied(job, "ApiKeyExpired");
    }

    @Test public void liveSchemaMustStillAllowPersonalProjectCollectionPost() {
        capture(true); ((SchemaImpl) schemas.getSchema("project")).setCollectionMethods(List.of("GET"));
        for (ProcessRecord job : jobs) assertDenied(job, "OwnerPermissionDenied");
    }

    @Test public void customPolicyCannotUseTheLegacyFullCreationException() {
        capture(true);
        for (ApiKeyPolicy.Effect effect : List.of(ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Effect.DENY)) {
            ApiKeyPolicy custom = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, effect, null, List.of());
            credential.setData(new ApiKeyPolicyCodec().store(credential, custom, 1));
            for (ProcessRecord job : jobs) assertDenied(job, "OwnerPermissionDenied");
        }
    }

    @Test public void changingMetadataAccountToInactiveProjectIsNotAWorkaround() {
        ClientVisibleException error = assertThrows(ClientVisibleException.class,
                () -> authenticator.currentAuthorization(7L, 9L, new ApiRequest(null, schemas)));
        assertEquals("OwnerPermissionDenied", error.getCode());
    }

    @Test public void closedManagerScopeCannotBootstrapAgainAfterOwnershipWithdrawal() {
        capture(true); ownerMembership.set(false);
        assertNull(ProjectCreationScope.currentProjectId());
        for (ProcessRecord job : jobs) assertDenied(job, "OwnerPermissionDenied");
        member.setRemoved(new Date());
        for (ProcessRecord job : jobs) assertDenied(job, "OwnerPermissionDenied");
        assertEquals(0, sideEffects.get());
    }

    @Test public void detachedDefaultNetworksRequireLiveOwnershipAndExactAccountParent() {
        for (boolean full : List.of(false, true)) {
            capture(full);
            for (String kind : List.of("dockerHost", "dockerNone", "dockerContainer", "dockerBridge")) {
                NetworkRecord network = defaultNetwork(kind);
                ProcessRecord job = captureNetwork(network, "account", "9", true);
                assertNotNull(ProcessAuthorization.verifiedChild(job));
                assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
                run(job);
                ownerMembership.set(false); assertDenied(job, "OwnerPermissionDenied");
                ownerMembership.set(true);
            }
        }
    }

    @Test public void defaultNetworkWithoutGenuineParentOrWithChangedLiveRowIsDenied() {
        capture(true);
        for (String parent : List.of("account:8", "project:9", "volume:9")) {
            String[] parts = parent.split(":");
            ProcessRecord job = captureNetwork(defaultNetwork("dockerHost"), parts[0], parts[1], true);
            assertDenied(job, "OwnerPermissionDenied");
        }
        assertDenied(captureNetwork(defaultNetwork("dockerHost"), "account", "9", false), "OwnerPermissionDenied");
        for (String change : List.of("account", "removed", "driver", "subnets", "kind")) {
            NetworkRecord network = defaultNetwork("dockerHost");
            ProcessRecord job = captureNetwork(network, "account", "9", true);
            switch (change) {
                case "account" -> network.setAccountId(10L);
                case "removed" -> network.setRemoved(new Date());
                case "driver" -> network.setNetworkDriverId(1L);
                case "subnets" -> io.cattle.platform.object.util.DataAccessor.fields(network).withKey("subnets").set(List.of("10.1.0.0/24"));
                case "kind" -> network.setKind("custom");
            }
            assertDenied(job, "OwnerPermissionDenied");
        }
    }

    @Test public void idNullNetworkNeedsLiveExactAccountParentAndCannotWidenVolumeBindings() {
        capture(true);
        ProcessRecord persisted = captureNetwork(defaultNetwork("dockerHost"), "account", "9", true);
        ProcessRecord child = new ProcessRecord(persisted, null, "synchronous-test");
        assertFalse(ProcessAuthorization.executionBound(child));
        assertNull(ProcessAuthorization.verifiedChild(child));
        var engine = EngineContext.getEngineContext();
        var parent = engine.pushVerifiedExecution("account", "9", ProcessAuthorization.metadata(child));
        try {
            assertTrue(ProcessAuthorization.executionBound(child));
            assertNotNull(ProcessAuthorization.verifiedChild(child));
        } finally { engine.popVerifiedExecution(parent); }
        parent = engine.pushVerifiedExecution("account", "8", ProcessAuthorization.metadata(child));
        try { assertNull(ProcessAuthorization.verifiedChild(child)); }
        finally { engine.popVerifiedExecution(parent); }
        var volume = new HashMap<>((Map<String, Object>) persisted.getData().get(ProcessAuthorization.VERIFIED_CHILD_KEY));
        volume.put("parentType", "volume"); child.getData().put(ProcessAuthorization.VERIFIED_CHILD_KEY, volume);
        parent = engine.pushVerifiedExecution("volume", "9", ProcessAuthorization.metadata(child));
        try { assertNull(ProcessAuthorization.verifiedChild(child)); }
        finally { engine.popVerifiedExecution(parent); }
    }

    @Test public void callerReceiptAndNonBooleanCreationMarkersAreNotCapturedAuthority() {
        for (Object forged : List.of("true", 1L)) {
            assertFalse(ProcessAuthorization.sanitize(Map.of("projectCreation", forged)).containsKey("projectCreation"));
        }
        var raw = new LaunchConfiguration("account.create", "account", "9", null, 0,
                Map.of(ProcessAuthorization.DATA_KEY, Map.of("projectCreation", true),
                        ProcessAuthorization.CAPTURED_EXECUTION_KEY, Map.of("resourceId", "9")));
        ApiContext.remove(); ProcessAuthorization.prepare(raw, List.of());
        assertTrue(ProcessAuthorization.metadata(raw).isEmpty());
        assertFalse(raw.getData().containsKey(ProcessAuthorization.CAPTURED_EXECUTION_KEY));
        assertFalse(ProcessAuthorization.executionBound(new ProcessRecord(raw, 500L, "forged")));
    }

    private NetworkRecord defaultNetwork(String kind) {
        NetworkRecord network = new NetworkRecord(); network.setId(20L); network.setAccountId(9L);
        network.setKind(kind); network.setState("requested"); network.setData(new HashMap<>());
        when(objects.loadResource("network", "20")).thenReturn(network); return network;
    }

    private ProcessRecord captureNetwork(NetworkRecord network, String parentType, String parentId, boolean verified) {
        Map<String, Object> metadata = ProcessAuthorization.metadata(jobs.get(0));
        var engine = EngineContext.getEngineContext(); engine.pushAuthorization(metadata);
        var parent = verified ? engine.pushVerifiedExecution(parentType, parentId, metadata) : null;
        LaunchConfiguration config = new LaunchConfiguration("network.create", "network", "20", 9L, 0, Map.of());
        try { ProcessAuthorization.prepare(config, List.of()); }
        finally { if (parent != null) engine.popVerifiedExecution(parent); engine.popAuthorization(); }
        return new ProcessRecord(config, 400L, "default-network-test");
    }

    private void capture(boolean full) { capture(full, request -> {}); }

    private void capture(boolean full, Consumer<ApiRequest> change) {
        jobs.clear(); ApiRequest request = beginRequest(full, change);
        try { assertSame(project, manager.create(request)); } finally { ApiContext.remove(); }
        assertNull(ProjectCreationScope.currentProjectId()); assertEquals(2, jobs.size());
        for (ProcessRecord job : jobs) {
            Map<String, Object> metadata = ProcessAuthorization.metadata(job);
            assertEquals(7L, metadata.get("accountId"));
            if ("project".equals(request.getType()) && request.getId() == null && "POST".equals(request.getMethod())
                    && request.getAction() == null && request.getLink() == null) {
                assertEquals("project", metadata.get("targetType")); assertEquals("9", metadata.get("targetId"));
                assertEquals(Boolean.TRUE, metadata.get("projectCreation"));
            }
        }
    }

    private ApiRequest beginRequest(boolean full, Consumer<ApiRequest> change) {
        ApiKeyPolicy policy = full ? fullPolicy(null) : null;
        long revision = full ? 1 : 0;
        credential.setData(full ? new ApiKeyPolicyCodec().store(credential, policy, revision) : new HashMap<>());
        ApiRequest request = new ApiRequest(null, schemas);
        request.setType("project"); request.setMethod("POST"); request.setRequestObject(Map.of("name", "new project"));
        change.accept(request);
        ApiContext context = ApiContext.newContext();
        context.setApiRequest(request); context.setIdFormatter(formatter); context.setPolicy(livePersonalPolicy());
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 7, "apiKey", revision, policy));
        // The real central gate already admitted this personal-namespace create.
        request.setAttribute("apiKey.audit.decision", "ALLOW");
        request.setAttribute("apiKey.audit.requestId", "6bdd23b6-cf9e-4a89-b674-e0378e18a99f");
        return request;
    }

    private ProcessRecord captureJob(String process, Object resource) {
        LaunchConfiguration config = ObjectLaunchConfigurationUtils.createConfig(schemas, process, resource, Map.of());
        if (resource == project) assertNull("Platform Account has no accountId", config.getAccountId());
        ProcessAuthorization.prepare(config, List.of(worker));
        ProcessRecord job = new ProcessRecord(config, 100L + jobs.size(), "creation-test");
        assertEquals(Boolean.TRUE.equals(ProcessAuthorization.metadata(job).get("projectCreation")), ProcessAuthorization.executionBound(job));
        jobs.add(job);
        if (executeDuringCapture) run(job);
        return job;
    }

    private Policy livePersonalPolicy() {
        return authenticator.currentAuthorization(7L, 7L, new ApiRequest(null, schemas)).policy();
    }

    private void runAll() { for (ProcessRecord job : jobs) run(job); }

    private void run(LaunchConfiguration job) {
        worker.beforeExecution(job, ProcessAuthorization.metadata(job));
        sideEffects.incrementAndGet(); // A denied hook must not reach the simulated handler.
    }

    private void assertDenied(LaunchConfiguration job, String code) {
        int before = sideEffects.get();
        ProcessAuthorizationDeniedException error = assertThrows(ProcessAuthorizationDeniedException.class, () -> run(job));
        if (code != null) assertEquals(code, error.getCode());
        assertEquals(before, sideEffects.get());
    }

    private static ApiKeyPolicy fullPolicy(Instant expiresAt) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, expiresAt, List.of());
    }

    private static AccountRecord account(long id, String kind, String state) {
        AccountRecord row = new AccountRecord(); row.setId(id); row.setKind(kind); row.setState(state); return row;
    }

    private static void inject(Class<?> declaringClass, Object target, String name, Object value) throws Exception {
        Field field = declaringClass.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    /** Only exposes the production entry point; creation/scheduling/scope logic is not overridden. */
    private static final class ExposedProjectManager extends ProjectResourceManager {
        Object create(ApiRequest request) { return super.createInternal("project", request); }
    }

    /** Same live algorithm; only provider identity/configuration and database IO are local. */
    private static final class LocalAuthenticator extends ApiAuthenticator {
        private final SchemaFactory factory;
        private boolean allAccounts;
        LocalAuthenticator(AuthDao authDao, AccountDao accountDao, SchemaFactory factory) throws Exception {
            setAuthDao(authDao); inject(ApiAuthenticator.class, this, "accountDao", accountDao); this.factory = factory;
        }
        @Override protected Set<Identity> getIdentities(Account account, boolean principalBound) {
            return Set.of(new Identity("rancher_id", String.valueOf(account.getId())));
        }
        @Override protected Policy getPolicy(Account account, Account principal, Set<Identity> identities, ApiRequest request) {
            return new AccountPolicy(account, principal, identities, new NoPolicyOptions() {
                @Override public boolean isOption(String option) {
                    return allAccounts && Policy.AUTHORIZED_FOR_ALL_ACCOUNTS.equals(option);
                }
            });
        }
        @Override protected SchemaFactory getSchemaFactory(Account account, Policy policy, ApiRequest request) { return factory; }
    }
}
