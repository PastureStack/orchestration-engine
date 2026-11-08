package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.engine.manager.impl.DefaultProcessManager;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyOperations;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.After;
import org.junit.Test;

/** Bounded source/owning-method tests. No runtime, SDK or published-artifact assertion. */
public class AiStackUpgradeLifecycleChangeTest {
    private final JacksonJsonMapper json = new JacksonJsonMapper();
    private static final List<String> OPERATIONS = List.of("finish_upgrade", "rollback", "cancel_upgrade");

    @After public void cleanup() { ApiContext.remove(); }

    @Test public void threeNativeStackActionsHaveEmptyBodiesExactStatesAndFourSafeHumanRows() {
        for (String operation : OPERATIONS) {
            var descriptor = descriptor(operation);
            assertEquals("POST", descriptor.method()); assertEquals("upgrade", descriptor.keyOperation());
            assertEquals("upgrade", ApiKeyOperations.of("POST", descriptor.action(), null, "stack").id());
            assertEquals(Map.of(), descriptor.parameters(Map.of())); assertEquals(Map.of(), descriptor.body(Map.of()));
            denied(422, "ResourceChangeInputInvalid", () -> descriptor.parameters(Map.of("serviceIds", List.of("1s7"))));
            for (String state : List.of("active", "upgrading", "upgraded", "finishing-upgrade", "canceled-upgrade", "rolling-back")) {
                var stack = stack(); stack.setState(state);
                boolean allowed = switch (operation) {
                case "finish_upgrade" -> "upgraded".equals(state);
                case "rollback" -> Set.of("upgrading", "upgraded", "canceled-upgrade").contains(state);
                default -> Set.of("upgrading", "finishing-upgrade").contains(state);
                };
                if (allowed) AiStackUpgradeLifecycleChange.ready(descriptor, stack);
                else denied(409, "ResourceChangeTargetBusy", () -> AiStackUpgradeLifecycleChange.ready(descriptor, stack));
            }
            var changes = AiStackUpgradeLifecycleChange.changes(descriptor, stack(), 2);
            assertEquals(List.of("operation", "state", "affectedServiceCount", "checkpointOnly"),
                    changes.stream().map(row -> row.get("field")).toList());
            assertNull(changes.get(0).get("before")); assertEquals(operation, changes.get(0).get("after"));
            assertEquals(2, changes.get(2).get("before")); assertEquals(2, changes.get(2).get("after"));
            assertNull(changes.get(3).get("before")); assertEquals("cancel_upgrade".equals(operation), changes.get(3).get("after"));
            assertEquals("cancel_upgrade".equals(operation) ? "canceled-upgrade" : "active", changes.get(1).get("after"));
            assertFalse(changes.toString().contains("PRIVATE"));
        }
        denied(422, "ResourceChangeUnsupported", () -> descriptor("cancel_rollback"));
        var plugin = stack(); plugin.setKind("kubernetesStack");
        denied(404, "NotFound", () -> AiStackUpgradeLifecycleChange.ready(descriptor("rollback"), plugin));
    }

    @Test public void catalogSystemAllNativeServiceKindsAndCompletePrivateGenerationArePreserved() {
        var stack = stack(); stack.setSystem(true); stack.setExternalId("catalog://PRIVATE/catalog:version");
        List<ServiceRecord> children = new ArrayList<>();
        for (String kind : List.of("service", "externalService", "dnsService", "loadBalancerService", "networkDriverService", "storageDriverService")) {
            children.add(child(7 + children.size(), kind, "upgraded"));
        }
        var before = snapshot(stack, children);
        assertEquals(6, AiStackUpgradeLifecycleChange.children(before).size());
        AiStackUpgradeLifecycleChange.ready(descriptor("finish_upgrade"), stack);
        for (String mutation : List.of("uuid", "account", "projectUuid", "configuration", "childSet", "childKind", "previousGeneration")) {
            var root = stack(); root.setSystem(true); root.setExternalId(stack.getExternalId());
            var rows = new ArrayList<>(children);
            switch (mutation) {
            case "uuid" -> root.setUuid("other-stack");
            case "account" -> { root.setAccountId(88L); for (ServiceRecord row : rows) row.setAccountId(88L); }
            case "configuration" -> DataAccessor.fields(rows.getFirst()).withKey("launchConfig").set(Map.of("imageUuid", "docker:different"));
            case "childSet" -> rows.removeLast();
            case "childKind" -> rows.getFirst().setKind("dnsService");
            case "previousGeneration" -> DataAccessor.fields(root).withKey("previousEnvironment").set(Map.of("secret", "different"));
            default -> { }
            }
            var current = AiStackUpgradeLifecycleChange.snapshot(root, rows, mutation.equals("projectUuid") ? "replacement-project" : "adminProject", json);
            assertNotEquals(mutation, before, current);
            children.clear();
            for (String kind : List.of("service", "externalService", "dnsService", "loadBalancerService", "networkDriverService", "storageDriverService"))
                children.add(child(7 + children.size(), kind, "upgraded"));
        }
        var foreign = child(7, "service", "upgraded"); foreign.setStackId(99L);
        denied(409, "ResourceChangePlanStale", () -> snapshot(stack, List.of(foreign)));
        foreign.setStackId(3L); foreign.setAccountId(88L);
        denied(409, "ResourceChangePlanStale", () -> snapshot(stack, List.of(foreign)));
    }

    @Test public void finishRollbackAndCancelRespectNativeReplyConfigurationAndCheckpointSemantics() throws Exception {
        for (String operation : OPERATIONS) {
            var stack = stack(); if (operation.equals("cancel_upgrade")) stack.setState("upgrading");
            var upgraded = child(7, "service", operation.equals("cancel_upgrade") ? "upgrading" : "upgraded");
            var stopped = child(8, "externalService", "inactive");
            // Active history and a newly introduced service are native no-ops, not rollback targets.
            var finished = child(9, "service", "active"); var introduced = child(10, "service", "active");
            DataAccessor.fields(introduced).withKey("upgrade").set(null);
            var children = List.of(upgraded, stopped, finished, introduced);
            var before = snapshot(stack, children);
            settle(stack, children, operation);
            var current = snapshot(stack, children);
            assertEquals("PRIVATE-NEW", DataAccessor.fieldMap(finished, "launchConfig").get("version"));
            assertEquals("PRIVATE-NEW", DataAccessor.fieldMap(introduced, "launchConfig").get("version"));
            assertEquals(4, AiStackUpgradeLifecycleChange.children(current).size());
            assertTrue(operation, AiStackUpgradeLifecycleChange.configurationComplete(descriptor(operation), before, current));
            if (operation.equals("cancel_upgrade")) {
                assertEquals("upgrading", upgraded.getState());
                assertEquals("PRIVATE-NEW", DataAccessor.fieldMap(upgraded, "launchConfig").get("version"));
            }
            for (String mutation : List.of("childUuid", "childSet", "childConfiguration", "rootGeneration")) {
                var changed = copy(current);
                if (mutation.equals("childSet")) ((List<?>) changed.get("children")).removeLast();
                else if (mutation.equals("rootGeneration")) changed.put("environment", Map.of("secret", "other"));
                else ((Map<String, Object>) ((List<?>) changed.get("children")).getFirst()).put(
                        mutation.equals("childUuid") ? "uuid" : "launchConfig",
                                mutation.equals("childUuid") ? "other-child" : Map.of("imageUuid", "docker:other"));
                assertFalse(mutation, AiStackUpgradeLifecycleChange.configurationComplete(descriptor(operation), before, changed));
            }
        }
        var stack = stack(); stack.setState("canceled-upgrade"); var service = child(7, "service", "canceled-upgrade");
        var before = snapshot(stack, List.of(service));
        restore(service); settleRoot(stack, "rollback"); // Existing broken native worker skipped this real state.
        assertFalse(AiStackUpgradeLifecycleChange.configurationComplete(descriptor("rollback"), before, snapshot(stack, List.of(service))));
        service.setState("active");
        assertTrue(AiStackUpgradeLifecycleChange.configurationComplete(descriptor("rollback"), before, snapshot(stack, List.of(service))));
    }

    @Test public void nativeEligibilityKeepsFinishedAndNewChildrenAndDoesNotRebuildOldMembership() throws Exception {
        for (String state : List.of("upgrading", "upgraded", "canceled-upgrade", "active", "inactive", "finishing-upgrade", "rolling-back")) {
            var root = stack(); var service = child(7, "service", state);
            var before = snapshot(root, List.of(service));
            AiStackUpgradeLifecycleChange.validate(descriptor("rollback"), before);
            settle(root, List.of(service), "rollback");
            assertTrue(state, AiStackUpgradeLifecycleChange.configurationComplete(descriptor("rollback"), before, snapshot(root, List.of(service))));
            assertEquals(Set.of("upgrading", "upgraded", "canceled-upgrade").contains(state) ? "PRIVATE-OLD" : "PRIVATE-NEW",
                    DataAccessor.fieldMap(service, "launchConfig").get("version"));
        }
        for (String operation : List.of("finish_upgrade", "rollback")) {
            var root = stack(); var active = child(8, "service", "active");
            var before = snapshot(root, List.of(active)); settle(root, List.of(active), operation);
            assertTrue(AiStackUpgradeLifecycleChange.configurationComplete(descriptor(operation), before, snapshot(root, List.of(active))));
            assertFalse(AiStackUpgradeLifecycleChange.configurationComplete(descriptor(operation), before, snapshot(root, List.of())));
            var replacement = child(8, "service", "active"); replacement.setUuid("replaced-child");
            assertFalse(AiStackUpgradeLifecycleChange.configurationComplete(descriptor(operation), before, snapshot(root, List.of(replacement))));
            var newChild = child(9, "service", "active");
            assertFalse(AiStackUpgradeLifecycleChange.configurationComplete(descriptor(operation), before, snapshot(root, List.of(active, newChild))));
        }
        var root = stack(); var migrating = child(7, "service", "upgraded");
        DataAccessor.fields(migrating).withKey("upgrade").set(Map.of("toServiceStrategy", Map.of("toServiceId", "1s8")));
        denied(422, "ResourceChangeUnsupported", () -> AiStackUpgradeLifecycleChange.validate(descriptor("rollback"), snapshot(root, List.of(migrating))));
        migrating.setState("active");
        AiStackUpgradeLifecycleChange.validate(descriptor("rollback"), snapshot(root, List.of(migrating)));
        migrating.setState("removing");
        denied(409, "ResourceChangeTargetBusy", () -> AiStackUpgradeLifecycleChange.validate(descriptor("rollback"), snapshot(root, List.of(migrating))));
    }

    @Test public void originalStackActionKeepsFullLegacyScopeUpgradeAndRoleCeilingsWithoutChildWrites() throws Exception {
        for (ApiKeyPolicy policy : java.util.Arrays.asList(null,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()), policy("1st3", Set.of("read", "upgrade")))) {
            var f = nativeFixture(); nativeKey(f, policy);
            var manager = f.domain.managers.getResourceManagerByType("stack");
            for (String operation : OPERATIONS) {
                clearInvocations(manager, f.domain.objects); var descriptor = descriptor(operation);
                assertEquals(Map.of(), f.domain.prepare(f.request, descriptor, "1st3", Map.of()));
                verify(manager, never()).resourceAction(anyString(), any());
                when(manager.resourceAction(eq("stack"), any())).thenAnswer(call -> {
                    ApiRequest request = call.getArgument(1); assertEquals(descriptor.action(), request.getAction());
                    assertEquals(Map.of(), request.getRequestObject()); assertTrue(AiResourceChangeDomain.trustedDomain(request)); return stack();
                });
                f.domain.dispatch(f.request, descriptor, "1st3", Map.of(), "aichange-owned");
                verify(manager, times(1)).resourceAction(eq("stack"), any()); verify(f.domain.objects, never()).persist(any(Service.class));
            }
        }
        var f = nativeFixture(); var manager = f.domain.managers.getResourceManagerByType("stack");
        for (var policy : List.of(policy("1st3", Set.of("read")), policy("1st4", Set.of("read", "upgrade")))) {
            nativeKey(f, policy); denied(403, "KeyScopeDenied", () -> f.domain.prepare(f.request, descriptor("rollback"), "1st3", Map.of()));
        }
        nativeKey(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        when(f.schema.getResourceActions()).thenReturn(Map.of());
        denied(403, "PermissionDenied", () -> f.domain.prepare(f.request, descriptor("rollback"), "1st3", Map.of()));
        verify(manager, never()).resourceAction(anyString(), any());
    }

    @Test public void normalHumanPlanRequiresApprovalUnchangedGenerationAndOwnRootBeforeOriginalCompletion() {
        for (String operation : OPERATIONS) for (boolean key : List.of(false, true)) {
            var f = planFixture(operation); var request = f.request(null, key); request.setId(null);
            request.setRequestObject(Map.of("resourceType", "stack", "resourceId", "1st3", "operation", operation, "parameters", Map.of()));
            var preview = f.service.preview(request); f.id = (String) preview.get("id");
            for (String field : List.of("snapshot", "lifecycleIdentity", "retirementSnapshot", "processBaseline")) assertFalse(preview.containsKey(field));
            denied(409, "ResourceChangeNeedsConfirmation", () -> f.apply(key)); assertEquals(0, f.claims);
            f.confirm(); var accepted = f.apply(key); assertEquals("accepted", accepted.get("status")); assertEquals(1, f.dispatched);
            verify(f.domain, times(operation.equals("cancel_upgrade") ? 0 : 1)).stackUpgradeRetirementSnapshot(any(), any());
            f.complete = true; assertEquals("running", f.service.get(f.id, f.request(null, key)).get("status"));
            when(f.domain.stackUpgradeLifecycleProcessOutcome(anyMap(), any())).thenReturn("succeeded");
            var result = f.service.get(f.id, f.request(null, key)); assertEquals("succeeded", result.get("status")); assertEquals(true,
                    result.get("verifiedResult"));
            assertEquals(preview.get("changes"), result.get("changes")); f.apply(key); assertEquals(1, f.dispatched);
            assertFalse(f.documents.get(f.id).containsKey("retirementSnapshot"));
        }
        var f = planFixture("finish_upgrade"); preview(f, "finish_upgrade"); f.confirm();
        doAnswer(call -> {
            var changed = snapshot(stack(), List.of()); changed.put("projectUuid", "changed-project"); return changed;
        }).when(f.domain).stackUpgradeLifecycleSnapshot(any(), any(), anyBoolean());
        denied(409, "ResourceChangePlanStale", () -> f.apply(true)); assertEquals(0, f.dispatched); assertEquals(0, f.claims);
    }

    @Test public void cookieStackOwnRootIsDurableAndInitialAttestationFailureHasRealSqlRollbackAndZeroQueue() throws Exception {
        for (String operation : OPERATIONS) {
            try (var f = new AiSessionLifecycleWitnessTest.Fixture("stack", operation)) {
                assertEquals("unknown", f.outcome());
                var other = new ProcessRecord(f.launch(), 99L, "another-cookie"); f.insert(other); f.success(99L);
                assertEquals("unknown", f.outcome()); var record = f.dispatch();
                assertEquals(2, f.count()); assertEquals(record.getId().longValue(), ((Number) f.store.execution(f.id).get("rootProcessId")).longValue());
                assertEquals("running", f.outcome()); f.success(record.getId()); f.terminal(); assertEquals("succeeded", f.outcome());
                assertEquals(Map.of(), ProcessAuthorization.metadata(record));
                assertNull(f.request.getAttribute(AiResourceChangeProcessObserver.ATTRIBUTE));
                f.plan.put("operationId", "other-plan"); assertEquals("unknown", f.outcome());
            }
            try (var f = new AiSessionLifecycleWitnessTest.Fixture("stack", operation)) {
                var definition = mock(io.cattle.platform.engine.process.ProcessDefinition.class);
                when(definition.constructProcessState(any())).thenReturn(mock(io.cattle.platform.engine.process.ProcessState.class));
                var field = DefaultProcessManager.class.getDeclaredField("definitions"); field.setAccessible(true);
                field.set(f.manager, Map.of("stack." + f.request.getAction(), definition));
                f.observer.store = spy(f.store); doReturn(false).when(f.observer.store).attestSessionProcess(anyString(), anyMap(), anyMap());
                f.observer.dispatch(f.request, f.id, () -> {
                    LaunchConfiguration config = f.launch(); ProcessAuthorization.prepare(config, List.of(f.observer));
                    assertThrows(IllegalStateException.class, () -> f.manager.create(new ProcessRecord(config, null, null))); return null;
                });
                assertEquals(0, f.count()); assertEquals(0, f.manager.queued); assertNull(f.store.execution(f.id).get("rootProcessId"));
            }
        }
        for (String mutation : List.of("action", "resource", "uuid", "owner", "kind", "payload")) {
            try (var f = new AiSessionLifecycleWitnessTest.Fixture("stack", "finish_upgrade")) {
                LaunchConfiguration config = f.launch();
                switch (mutation) {
                case "action" -> config.setProcessName("stack.rollback");
                case "resource" -> config.setResourceId("4");
                case "uuid" -> f.stack.setUuid("replaced-stack");
                case "owner" -> f.stack.setAccountId(6L);
                case "kind" -> f.stack.setKind("kubernetesStack");
                default -> config.setData(Map.of("force", true));
                }
                assertThrows(IllegalStateException.class, () -> f.observer.dispatch(f.request, f.id, () -> {
                    ProcessAuthorization.prepare(config, List.of(f.observer)); return f.manager.insert(new ProcessRecord(config, null, null));
                }));
                assertEquals(0, f.count()); assertEquals(0, f.manager.queued); assertNull(f.request.getAttribute(AiResourceChangeProcessObserver.ATTRIBUTE));
            }
        }
    }

    @Test public void completeStillAndsEveryChildReadinessAndOriginalOldInstanceRetirement() throws Exception {
        var stack = stack(); var child = child(7, "service", "upgraded");
        var active = child(8, "service", "active"); var added = child(9, "service", "inactive");
        DataAccessor.fields(added).withKey("upgrade").set(null);
        List<ServiceRecord> children = new ArrayList<>(List.of(child, active, added));
        var before = snapshot(stack, children); settle(stack, children, "finish_upgrade");
        var domain = new AiResourceChangeDomain(); domain.json = json;
        domain.objects = mock(io.cattle.platform.object.ObjectManager.class);
        var project = new io.cattle.platform.core.model.tables.records.AccountRecord(); project.setId(77L); project.setKind("project");
                project.setUuid("adminProject");
        when(domain.objects.loadResource(io.cattle.platform.core.model.Account.class, 77L)).thenReturn(project);
        when(domain.objects.find(eq(ServiceExposeMap.class), eq("serviceId"), eq(7L), eq("removed"), isNull())).thenReturn(List.of());
        domain.deploymentReadiness = mock(io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness.class);
        when(domain.deploymentReadiness.isReady(any())).thenReturn(true);
        var connection = new MockConnection(context -> {
            assertTrue(context.sql().contains("service")); var result = DSL.using(SQLDialect.H2).newResult(SERVICE); result.addAll(children);
            return new MockResult[] {new MockResult(children.size(), result)};
        });
        domain.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
        assertFalse(domain.complete(descriptor("finish_upgrade"), stack, Map.of(), before));
        before.put("retirementSnapshot", Map.of("children", List.of(Map.of("id", 7, "uuid", child.getUuid(), "snapshot", Map.of("instances", List.of())))));
        assertTrue(domain.complete(descriptor("finish_upgrade"), stack, Map.of(), before));
        verify(domain.deploymentReadiness, never()).isReady(active);
        verify(domain.objects, never()).find(eq(ServiceExposeMap.class), eq("serviceId"), eq(8L), eq("removed"), isNull());
        verify(domain.objects, never()).find(eq(ServiceExposeMap.class), eq("serviceId"), eq(9L), eq("removed"), isNull());
        var captured = domain.stackUpgradeRetirementSnapshot(descriptor("finish_upgrade"), stack);
        assertTrue(((List<?>) captured.get("children")).isEmpty()); // Settled active services are no longer action targets.
        child.setState("upgraded");
        captured = domain.stackUpgradeRetirementSnapshot(descriptor("finish_upgrade"), stack);
        assertEquals(1, ((List<?>) captured.get("children")).size());
        child.setState("active");
        when(domain.deploymentReadiness.isReady(any())).thenReturn(false);
        assertFalse(domain.complete(descriptor("finish_upgrade"), stack, Map.of(), before));
        when(domain.deploymentReadiness.isReady(any())).thenReturn(true);
        var upgrade = new io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord(); upgrade.setUpgrade(true);
        when(domain.objects.find(eq(ServiceExposeMap.class), eq("serviceId"), eq(7L), eq("removed"), isNull())).thenReturn(List.of(upgrade));
        assertFalse(domain.complete(descriptor("finish_upgrade"), stack, Map.of(), before));
        children.add(child(10, "dnsService", "inactive")); assertFalse(domain.complete(descriptor("finish_upgrade"), stack, Map.of(), before));
    }

    private static AiResourceChangeDescriptor descriptor(String operation) { return AiResourceChangeDescriptor.require("stack", operation); }
    private static StackRecord stack() {
        var stack = new StackRecord(); stack.setId(3L); stack.setUuid("stack-three"); stack.setAccountId(77L); stack.setKind("stack");
        stack.setName("Blue"); stack.setState("upgraded"); stack.setExternalId("catalog://PRIVATE/new"); stack.setSystem(false);
        stack.setData(Map.of("fields", new LinkedHashMap<>(Map.of("environment", Map.of("secret", "PRIVATE-NEW"),
                "previousExternalId", "catalog://PRIVATE/old", "previousEnvironment", Map.of("secret", "PRIVATE-OLD"), "dockerCompose", "PRIVATE-COMPOSE"))));
        return stack;
    }
    private static ServiceRecord child(long id, String kind, String state) {
        var service = new ServiceRecord(); service.setId(id); service.setUuid("child-" + id); service.setAccountId(77L); service.setStackId(3L);
        service.setKind(kind); service.setName("child-" + id); service.setState(state);
        var current = Map.of("imageUuid", "docker:new", "version", "PRIVATE-NEW"); var prior = Map.of("imageUuid", "docker:old", "version", "PRIVATE-OLD");
        var strategy = Map.of("launchConfig", current, "previousLaunchConfig", prior, "secondaryLaunchConfigs", List.of(),
                "previousSecondaryLaunchConfigs", List.of());
        service.setData(Map.of("fields", new LinkedHashMap<>(Map.of("launchConfig", current, "secondaryLaunchConfigs", List.of(), "upgrade",
                Map.of("inServiceStrategy", strategy)))));
        return service;
    }
    private Map<String, Object> snapshot(StackRecord stack,
            List<? extends Service> children) { return AiStackUpgradeLifecycleChange.snapshot(stack, children, "adminProject", json); }
    private Map<String, Object> copy(Map<String, Object> value) {
        try { return json.readValue(json.writeValueAsBytes(value)); } catch (Exception error) { throw new AssertionError(error); }
    }
    private void restore(Service service) throws Exception {
        var restore = io.cattle.platform.servicediscovery.api.filter.ServiceRollbackValidationFilter.class
                .getDeclaredMethod("restorePreviousLaunchConfigs", Service.class, io.cattle.platform.json.JsonMapper.class);
        restore.setAccessible(true); assertTrue((Boolean) restore.invoke(null, service, json));
    }
    private void settle(StackRecord stack, List<ServiceRecord> services, String operation) throws Exception {
        if (!operation.equals("cancel_upgrade")) for (ServiceRecord child : services) {
            if (AiStackUpgradeLifecycleChange.restores(operation, child.getState())) restore(child);
            if (AiStackUpgradeLifecycleChange.settles(operation, child.getState())) child.setState("active");
        }
        settleRoot(stack, operation);
    }
    private static void settleRoot(StackRecord stack, String operation) {
        if (!operation.equals("cancel_upgrade")) {
            if (operation.equals("rollback")) {
                stack.setExternalId((String) DataAccessor.field(stack, "previousExternalId", Object.class));
                DataAccessor.fields(stack).withKey("environment").set(DataAccessor.field(stack, "previousEnvironment", Object.class));
            }
            DataAccessor.fields(stack).withKey("previousExternalId").set(null); DataAccessor.fields(stack).withKey("previousEnvironment").set(null);
        }
        stack.setState(AiStackUpgradeLifecycleChange.terminal(operation));
    }
    private static ApiKeyPolicy policy(String stack, Set<String> operations) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("stack", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack(stack), operations)));
    }
    private static AiResourceChangeDomainTest.Fixture nativeFixture() {
        var f = new AiResourceChangeDomainTest.Fixture(); when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
        when(f.schema.getResourceActions()).thenReturn(Map.of("finishupgrade", mock(Action.class), "rollback", mock(Action.class),
                "cancelupgrade", mock(Action.class)));
        when(f.schemas.getSchema("stack")).thenReturn(f.schema); f.domain.targets = mock(ApiKeyTargetResolver.class);
        when(f.domain.targets.parseScopeId(anyString(), anyString())).thenAnswer(call -> Long.parseLong(call.<String>getArgument(1).replaceFirst("^1st", "")));
        var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(f.schemas);
                formatter.setTypeMappings(Map.of("stack", "st"));
        ApiContext.getContext().setIdFormatter(formatter); f.domain.managers = mock(ResourceManagerLocator.class); var manager = mock(ResourceManager.class);
        when(f.domain.managers.getResourceManagerByType("stack")).thenReturn(manager); when(manager.getById(eq("stack"), eq("1st3"),
                any())).thenReturn(stack()); return f;
    }
    private static void nativeKey(AiResourceChangeDomainTest.Fixture f, ApiKeyPolicy policy) throws Exception {
        var access = new ApiKeyAuthorizationService(); var targets = mock(ApiKeyTargetResolver.class);
        when(targets.resolve(any(), any())).thenReturn(List.of(ApiKeyPolicyEvaluator.Target.stackResource("stack", "1st3", "1a77", "1st3")));
        for (var item : Map.of("targets", targets, "auditSinks", List.of(mock(ApiKeyAuditSink.class))).entrySet()) {
            var field = ApiKeyAuthorizationService.class.getDeclaredField(item.getKey()); field.setAccessible(true); field.set(access, item.getValue());
        }
        f.domain.keyAccess = access;
        ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(7, 42, policy == null ? "apiKey" : "apiKeyRestricted",
                policy == null ? 0 : 3, policy));
    }
    private AiResourceChangePlanServiceTest.Fixture planFixture(String operation) {
        var f = new AiResourceChangePlanServiceTest.Fixture(); var target = stack(); if (operation.equals("cancel_upgrade")) target.setState("upgrading");
        when(f.domain.lock(eq("stack"), anyLong())).thenReturn(target); when(f.domain.visible(any(), any(), anyString())).thenReturn(target);
        when(f.domain.receiptTarget(any(), any(), anyMap())).thenReturn(target);
        when(f.domain.stackUpgradeLifecycleSnapshot(any(), any(), anyBoolean())).thenAnswer(call -> snapshot(call.getArgument(1), List.of()));
        when(f.domain.stackUpgradeRetirementSnapshot(any(), any())).thenReturn(Map.of("children", List.of()));
        when(f.domain.stackUpgradeLifecycleProcessOutcome(anyMap(), any())).thenReturn("running");
        when(f.domain.changes(any(), any(), anyMap())).thenAnswer(call -> AiStackUpgradeLifecycleChange.changes(call.getArgument(0),
                call.getArgument(1), 0)); return f;
    }
    private static void preview(AiResourceChangePlanServiceTest.Fixture f, String operation) {
        var request = f.request(null, true); request.setId(null); request.setRequestObject(Map.of("resourceType", "stack", "resourceId", "1st3",
                "operation", operation, "parameters", Map.of()));
        f.id = (String) f.service.preview(request).get("id");
    }
    private static void denied(int status, String code, Runnable action) {
        var error = assertThrows(ClientVisibleException.class, action::run); assertEquals(status, error.getStatus()); assertEquals(code, error.getCode());
    }
}
