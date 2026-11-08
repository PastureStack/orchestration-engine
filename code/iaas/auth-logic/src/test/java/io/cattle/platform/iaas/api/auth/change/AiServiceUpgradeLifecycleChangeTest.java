package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.MountRecord;
import io.cattle.platform.core.model.tables.records.ProcessInstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
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
import java.util.Date;
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

/** Existing native actions, through the approval/CAS/manager owners, without launching a runtime. */
public class AiServiceUpgradeLifecycleChangeTest {
    private final JacksonJsonMapper json = new JacksonJsonMapper();

    @After public void cleanup() { ApiContext.remove(); }

    @Test public void nativeSchemaAdmitsTheSameThreeActionsWithoutAddingCancelRollback() throws Exception {
        var root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        var relative = java.nio.file.Path.of("resources/content/schema/base/aiResourceChangePlan.json");
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve(relative))) root = root.getParent();
        assertNotNull("Canonical native source schema is required", root);
        Map<?, ?> schema = json.readValue(java.nio.file.Files.readString(root.resolve(relative)));
        Map<?, ?> field = (Map<?, ?>) ((Map<?, ?>) schema.get("resourceFields")).get("operation");
        var operations = (List<?>) field.get("options");
        for (String operation : List.of("finish_upgrade", "rollback", "cancel_upgrade")) assertTrue(operations.contains(operation));
        assertFalse(operations.contains("cancel_rollback"));
        assertEquals(Set.of("start", "stop", "restart", "remove", "create", "update_metadata", "update_targets", "update_links",
                "scale", "archive", "restore", "finish_upgrade", "rollback", "cancel_upgrade", "update_public_port", "update_target"),
                Set.copyOf(operations));
        assertEquals(16, operations.size());
    }

    @Test public void threeNativeCapabilitiesHaveEmptyParametersAndCancelRollbackRemainsUnsupported() {
        for (var item : Map.of("finish_upgrade", "finishupgrade", "rollback", "rollback", "cancel_upgrade", "cancelupgrade").entrySet()) {
            var descriptor = descriptor(item.getKey());
            assertEquals("POST", descriptor.method()); assertEquals(item.getValue(), descriptor.action());
            assertEquals("upgrade", descriptor.keyOperation()); assertEquals("upgrade", ApiKeyOperations.of("POST", descriptor.action(), null, "service").id());
            assertEquals(Map.of(), descriptor.parameters(Map.of())); assertEquals(Map.of(), descriptor.body(Map.of()));
            denied(422, "ResourceChangeInputInvalid", () -> descriptor.parameters(Map.of("strategy", Map.of())));
            denied(422, "ResourceChangeUnsupported", () -> AiResourceChangeDescriptor.require("loadBalancerService", item.getKey()));
        }
        denied(422, "ResourceChangeUnsupported", () -> descriptor("cancel_rollback"));
        for (String operation : List.of("finish_upgrade", "rollback", "cancel_upgrade")) {
            for (String state : List.of("active", "upgraded", "upgrading", "finishing-upgrade", "canceled-upgrade", "rolling-back")) {
                ServiceRecord service = service(); service.setState(state);
                boolean allowed = switch (operation) {
                case "finish_upgrade" -> "upgraded".equals(state);
                case "rollback" -> Set.of("upgrading", "upgraded", "canceled-upgrade").contains(state);
                default -> Set.of("upgrading", "finishing-upgrade").contains(state);
                };
                if (allowed) AiServiceUpgradeLifecycleChange.ready(descriptor(operation), service, json);
                else denied(409, "ResourceChangeTargetBusy", () -> AiServiceUpgradeLifecycleChange.ready(descriptor(operation), service, json));
            }
        }
        var wrong = service(); wrong.setKind("loadBalancerService");
        denied(404, "NotFound", () -> AiServiceUpgradeLifecycleChange.ready(descriptor("finish_upgrade"), wrong, json));
    }

    @Test public void privateGenerationCasKeepsLegacyAndSecondaryConfigurationsButExcludesWorkerProgress() {
        var target = service(); var before = AiServiceUpgradeLifecycleChange.snapshot(target, json);
        DataAccessor.fields(target).withKey("scaleStatus").set("updating");
        DataAccessor.fields(target).withKey("startCount").set(99);
        assertEquals(before, AiServiceUpgradeLifecycleChange.snapshot(target, json));
        var legacy = service(); Map<String, Object> prior = new LinkedHashMap<>(prior()); prior.remove("version");
        var strategy = new LinkedHashMap<>(DataAccessor.fieldMap(legacy, "upgrade"));
        var inService = new LinkedHashMap<>((Map<String, Object>) strategy.get("inServiceStrategy"));
        inService.put("previousLaunchConfig", prior); strategy.put("inServiceStrategy", inService);
        DataAccessor.fields(legacy).withKey("upgrade").set(strategy);
        AiServiceUpgradeLifecycleChange.ready(descriptor("rollback"), legacy, json);
        assertNotEquals(before, AiServiceUpgradeLifecycleChange.snapshot(legacy, json));
        for (String mutation : List.of("phase", "generation", "launch", "stack", "uuid")) {
            var changed = service();
            switch (mutation) {
            case "phase" -> changed.setState("upgrading");
            case "generation" -> DataAccessor.fields(changed).withKey("upgrade")
                    .set(Map.of("inServiceStrategy", Map.of("launchConfig", Map.of("version", "new-generation"))));
            case "launch" -> DataAccessor.fields(changed).withKey("launchConfig").set(Map.of("imageUuid", "docker:different"));
            case "stack" -> changed.setStackId(4L);
            default -> changed.setUuid("replacement-service");
            }
            assertNotEquals(mutation, before, AiServiceUpgradeLifecycleChange.snapshot(changed, json));
        }
        var changes = AiServiceUpgradeLifecycleChange.changes(descriptor("finish_upgrade"), target);
        assertEquals(List.of("operation", "state"), changes.stream().map(value -> value.get("field")).toList());
        assertEquals("upgraded", changes.get(1).get("before")); assertEquals("active", changes.get(1).get("after"));
        assertFalse(changes.toString().contains("PRIVATE-ENV")); assertFalse(changes.toString().contains("generation"));
    }

    @Test public void nativeRollbackReadsPreviousConfigurationsAndCancelOnlyProvesACheckpoint() throws Exception {
        for (String operation : List.of("finish_upgrade", "rollback", "cancel_upgrade")) {
            var target = service(); if ("cancel_upgrade".equals(operation)) target.setState("upgrading");
            var before = AiServiceUpgradeLifecycleChange.snapshot(target, json);
            target.setState(AiServiceUpgradeLifecycleChange.terminal(operation));
            if ("rollback".equals(operation)) {
                assertFalse(AiServiceUpgradeLifecycleChange.configurationComplete(descriptor(operation), target, before, json));
                var restore = io.cattle.platform.servicediscovery.api.filter.ServiceRollbackValidationFilter.class
                        .getDeclaredMethod("restorePreviousLaunchConfigs", Service.class, io.cattle.platform.json.JsonMapper.class);
                restore.setAccessible(true); assertEquals(true, restore.invoke(null, target, json));
            }
            assertTrue(operation, AiServiceUpgradeLifecycleChange.configurationComplete(descriptor(operation), target, before, json));
            var domain = new AiResourceChangeDomain(); domain.json = json;
            if ("cancel_upgrade".equals(operation)) {
                assertTrue(domain.complete(descriptor(operation), target, Map.of(), before));
                target.setState("active"); assertFalse(domain.complete(descriptor(operation), target, Map.of(), before));
            }
            target.setName("changed-after-approval");
            assertFalse(AiServiceUpgradeLifecycleChange.configurationComplete(descriptor(operation), target, before, json));
        }
    }

    @Test public void nativeFullLegacyAndCustomUpgradeUseTheNormalActionAndFreshStackScope() throws Exception {
        for (ApiKeyPolicy policy : java.util.Arrays.asList(null,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()),
                custom("1st3", Set.of("read", "upgrade")))) {
            var f = domainFixture(); nativeKey(f, policy);
            for (String operation : List.of("finish_upgrade", "rollback", "cancel_upgrade")) {
                var descriptor = descriptor(operation); var manager = f.domain.managers.getResourceManagerByType("service");
                clearInvocations(manager, f.domain.objects);
                assertEquals(Map.of(), f.domain.prepare(f.request, descriptor, "1s7", Map.of()));
                verify(manager, never()).resourceAction(anyString(), any()); verify(f.domain.objects, never()).persist(any(Service.class));
                when(manager.resourceAction(eq("service"), any())).thenAnswer(call -> {
                    ApiRequest actual = call.getArgument(1);
                    assertEquals(descriptor.action(), actual.getAction()); assertEquals(Map.of(), actual.getRequestObject());
                    assertTrue(AiResourceChangeDomain.trustedDomain(actual)); return service();
                });
                f.domain.dispatch(f.request, descriptor, "1s7", Map.of(), "aichange-owned");
                verify(manager, times(1)).resourceAction(eq("service"), any());
            }
        }
        var f = domainFixture(); var manager = f.domain.managers.getResourceManagerByType("service");
        for (ApiKeyPolicy policy : List.of(custom("1st3", Set.of("read")), custom("1st4", Set.of("read", "upgrade")))) {
            nativeKey(f, policy); denied(403, "KeyScopeDenied", () -> f.domain.prepare(f.request, descriptor("rollback"), "1s7", Map.of()));
        }
        nativeKey(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        when(f.schema.getResourceActions()).thenReturn(Map.of());
        denied(403, "PermissionDenied", () -> f.domain.prepare(f.request, descriptor("rollback"), "1s7", Map.of()));
        when(f.schema.getResourceActions()).thenReturn(actions());
        var foreign = service(); foreign.setAccountId(88L); when(manager.getById(eq("service"), eq("1s7"), any())).thenReturn(foreign);
        denied(404, "NotFound", () -> f.domain.prepare(f.request, descriptor("rollback"), "1s7", Map.of()));
        verify(manager, never()).resourceAction(anyString(), any());
        assertSame(f.request, ApiContext.getContext().getApiRequest());
    }

    @Test public void approvalAndActualProcessAreRequiredAndCapturedHumanDiffNeverChanges() {
        for (String operation : List.of("finish_upgrade", "rollback", "cancel_upgrade")) {
            var f = planFixture(operation); var preview = preview(f, operation);
            for (String field : List.of("snapshot", "referenceSnapshot", "lifecycleIdentity", "retirementSnapshot", "processBaseline"))
                assertFalse(preview.containsKey(field));
            verify(f.domain, never()).upgradeLifecycleProcessBaseline(any()); assertEquals(0, f.dispatched);
            verify(f.domain, never()).upgradeLifecycleRetirementSnapshot(any());
            denied(409, "ResourceChangeNeedsConfirmation", () -> f.apply(true)); assertEquals(0, f.claims);
            f.confirm(); verify(f.domain, never()).upgradeLifecycleProcessBaseline(any());
            assertEquals("accepted", f.apply(true).get("status")); assertEquals(1, f.claims); assertEquals(1, f.dispatched);
            verify(f.domain, times("cancel_upgrade".equals(operation) ? 0 : 1)).upgradeLifecycleRetirementSnapshot(any());
            var captured = preview.get("changes"); f.complete = true;
            when(f.domain.upgradeLifecycleProcessOutcome(anyMap(), any())).thenReturn("running");
            assertEquals("running", f.service.get(f.id, f.request(null, true)).get("status"));
            when(f.domain.upgradeLifecycleProcessOutcome(anyMap(), any())).thenReturn("succeeded");
            var receipt = f.service.get(f.id, f.request(null, true));
            assertEquals("succeeded", receipt.get("status")); assertEquals(true, receipt.get("verifiedResult"));
            assertEquals(captured, receipt.get("changes")); f.apply(true); assertEquals(1, f.dispatched);
            assertFalse(f.documents.get(f.id).containsKey("retirementSnapshot"));
        }
        for (String outcome : List.of("failed", "unknown")) {
            var f = planFixture("finish_upgrade"); preview(f, "finish_upgrade"); f.confirm(); f.apply(true); f.complete = true;
            when(f.domain.upgradeLifecycleProcessOutcome(anyMap(), any())).thenReturn(outcome);
            var receipt = f.service.get(f.id, f.request(null, true)); assertEquals(outcome, receipt.get("status"));
            assertEquals(false, receipt.get("verifiedResult")); f.apply(true); assertEquals(1, f.dispatched);
        }
    }

    @Test public void phaseGenerationStackAndLateAuthorityDriftNeverClaimAnOperation() {
        for (String mutation : List.of("phase", "generation", "stack", "read-view", "write-authority")) {
            var f = planFixture("rollback"); preview(f, "rollback");
            switch (mutation) {
            case "phase" -> f.target.setState("active");
            case "generation" -> DataAccessor.fields(f.target).withKey("upgrade")
                    .set(Map.of("inServiceStrategy", Map.of("launchConfig", Map.of("version", "different"))));
            case "stack" -> when(f.domain.prepare(any(), any(), nullable(String.class), anyMap(), anyMap()))
                    .thenThrow(new ClientVisibleException(409, "ResourceChangePlanStale"));
            case "read-view" -> { var stale = service(); stale.setState("upgrading"); when(f.domain.visible(any(), any(), anyString())).thenReturn(stale); }
            default -> f.writeAllowed = false;
            }
            denied("write-authority".equals(mutation) ? 403 : 409,
                    "write-authority".equals(mutation) ? "PermissionDenied" : "ResourceChangePlanStale", f::confirm);
            assertEquals(0, f.claims); assertEquals(0, f.dispatched);
        }
    }

    @Test public void successfulFinishStillWaitsForExactOldUpgradeInstanceAndAttachmentRetirement() {
        var target = service(); var domain = new AiResourceChangeDomain(); domain.json = json;
        domain.objects = mock(io.cattle.platform.object.ObjectManager.class);
        domain.deploymentReadiness = mock(io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness.class);
        when(domain.deploymentReadiness.isReady(target)).thenReturn(true);
        var current = exposure(10, true, false); var old = exposure(11, false, true);
        var outside = exposure(12, false, false); var original = new InstanceRecord();
        original.setId(11L); original.setUuid("old-instance"); original.setAccountId(77L); original.setState("stopped");
        var retained = new InstanceRecord(); retained.setId(10L); retained.setUuid("new-instance"); retained.setAccountId(77L);
        when(domain.objects.loadResource(Instance.class, 10L)).thenReturn(retained);
        when(domain.objects.loadResource(Instance.class, 11L)).thenReturn(original);
        when(domain.objects.loadResource(ServiceExposeMap.class, 10L)).thenReturn(current);
        when(domain.objects.loadResource(ServiceExposeMap.class, 11L)).thenReturn(old);
        when(domain.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(current, old, outside));
        var retirement = domain.upgradeLifecycleRetirementSnapshot(target);
        assertEquals(2, ((List<?>) retirement.get("instances")).size());
        verify(domain.objects, never()).loadResource(Instance.class, 12L);
        var before = AiServiceUpgradeLifecycleChange.snapshot(target, json); before.put("retirementSnapshot", retirement);
        target.setState("active"); assertFalse(domain.complete(descriptor("finish_upgrade"), target, Map.of(), before));
        old.setRemoved(new Date(1)); old.setState("removed");
        when(domain.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(current));
        assertFalse(domain.complete(descriptor("finish_upgrade"), target, Map.of(), before));
        original.setRemoved(new Date(1)); original.setState("removed");
        var mount = new MountRecord(); mount.setState("deactivating");
        when(domain.objects.find(Mount.class, "instanceId", 11L, "removed", null)).thenReturn(List.of(mount));
        assertFalse(domain.complete(descriptor("finish_upgrade"), target, Map.of(), before));
        mount.setState("inactive"); assertTrue(domain.complete(descriptor("finish_upgrade"), target, Map.of(), before));
        original.setUuid("replaced-old-instance"); assertFalse(domain.complete(descriptor("finish_upgrade"), target, Map.of(), before));
        original.setUuid("old-instance"); old.setUuid("replaced-old-map");
        assertFalse(domain.complete(descriptor("finish_upgrade"), target, Map.of(), before)); old.setUuid("map-11");
        var late = exposure(13, false, true);
        when(domain.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(current, late));
        assertFalse(domain.complete(descriptor("finish_upgrade"), target, Map.of(), before));
        when(domain.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(old));
        current.setRemoved(new Date(1)); current.setState("removed"); retained.setRemoved(new Date(1)); retained.setState("removed");
        old.setRemoved(null); old.setState("active"); old.setManaged(true); old.setUpgrade(false); original.setRemoved(null); original.setState("running");
        DataAccessor.fields(target).withKey("launchConfig").set(prior());
        DataAccessor.fields(target).withKey("secondaryLaunchConfigs").set(List.of(secondary("old-sidekick")));
        assertTrue(domain.complete(descriptor("rollback"), target, Map.of(), before));
    }

    @Test public void processObservationUsesAnExactBoundedRootAndRejectsMissingOrConflictingExecutionEvidence() {
        var target = service(); var process = new ProcessInstanceRecord(); process.setId(11L); process.setAccountId(77L);
        process.setResourceType("service"); process.setResourceId("7"); process.setProcessName("service.finishupgrade");
        process.setPhase("DONE"); process.setResult("SUCCESS"); process.setExitReason("DONE"); process.setEndTime(new Date(1));
        List<ProcessInstanceRecord> rows = new ArrayList<>(); var domain = new AiResourceChangeDomain(); domain.json = json;
        domain.targets = mock(ApiKeyTargetResolver.class);
        when(domain.targets.parseScopeId("credential", "1c7")).thenReturn(7L);
        var connection = new MockConnection(context -> {
            assertTrue(context.sql().contains("process_name")); assertTrue(context.sql().toLowerCase().contains("fetch next"));
            assertTrue(List.of(context.bindings()).contains("service.finishupgrade"));
            var result = DSL.using(SQLDialect.H2).newResult(PROCESS_INSTANCE); result.addAll(rows);
            return new MockResult[] { new MockResult(rows.size(), result) };
        });
        domain.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
        Map<String, Object> plan = new LinkedHashMap<>(Map.of("id", "aichange-owned", "operation", "finish_upgrade", "processBaseline", 10));
        assertEquals("running", domain.upgradeLifecycleProcessOutcome(plan, target)); rows.add(process);
        assertEquals("succeeded", domain.upgradeLifecycleProcessOutcome(plan, target));
        process.setEndTime(null); assertEquals("running", domain.upgradeLifecycleProcessOutcome(plan, target)); process.setEndTime(new Date(1));
        process.setResult("CANCELED"); assertEquals("failed", domain.upgradeLifecycleProcessOutcome(plan, target)); process.setResult("SUCCESS");
        rows.add(process); assertEquals("unknown", domain.upgradeLifecycleProcessOutcome(plan, target)); rows.removeLast();
        plan.putAll(Map.of("credentialId", 7, "actorId", 42, "credentialRevision", 3));
        assertEquals("unknown", domain.upgradeLifecycleProcessOutcome(plan, target));
        Map<String, Object> metadata = new LinkedHashMap<>(Map.ofEntries(Map.entry("requestId", "aichange-owned"), Map.entry("operation", "upgrade"),
                Map.entry("targetType", "service"), Map.entry("targetId", "7"), Map.entry("requestMethod", "POST"), Map.entry("requestAction", "finishupgrade"),
                Map.entry("requestCollection", false), Map.entry("preview", false), Map.entry("accountId", 77), Map.entry("principalAccountId", 42),
                Map.entry("policyRevision", 3), Map.entry("keyId", "1c7")));
        process.setData(Map.of(ProcessAuthorization.DATA_KEY, metadata)); assertEquals("succeeded", domain.upgradeLifecycleProcessOutcome(plan, target));
        for (String field : List.of("requestId", "policyRevision", "targetId", "requestAction", "preview")) {
            var changed = new LinkedHashMap<>(metadata); changed.put(field, "wrong"); process.setData(Map.of(ProcessAuthorization.DATA_KEY, changed));
            assertEquals(field, "unknown", domain.upgradeLifecycleProcessOutcome(plan, target));
        }
    }

    private static AiResourceChangeDescriptor descriptor(String operation) { return AiResourceChangeDescriptor.require("service", operation); }
    private static Map<String, Object> prior() {
        return Map.of("imageUuid", "docker:old", "version", "old-generation", "environment", Map.of("PRIVATE-ENV", "private"));
    }
    private static Map<String, Object> secondary(String version) { return Map.of("name", "sidekick", "imageUuid", "docker:sidekick", "version", version); }
    private static ServiceRecord service() {
        var service = new ServiceRecord(); service.setId(7L); service.setUuid("ordinary-service"); service.setAccountId(77L);
        service.setStackId(3L); service.setKind("service"); service.setName("app"); service.setState("upgraded");
        var next = Map.of("imageUuid", "docker:new", "version", "new-generation", "environment", Map.of("PRIVATE-ENV", "private"));
        DataAccessor.fields(service).withKey("launchConfig").set(next);
        DataAccessor.fields(service).withKey("secondaryLaunchConfigs").set(List.of(secondary("new-sidekick")));
        DataAccessor.fields(service).withKey("scale").set(1);
        DataAccessor.fields(service).withKey("upgrade").set(Map.of("inServiceStrategy", Map.of("launchConfig", next,
                "previousLaunchConfig", prior(), "secondaryLaunchConfigs", List.of(secondary("new-sidekick")),
                "previousSecondaryLaunchConfigs", List.of(secondary("old-sidekick")))));
        return service;
    }
    private static ServiceExposeMapRecord exposure(long id, boolean managed, boolean upgrade) {
        var map = new ServiceExposeMapRecord(); map.setId(id); map.setUuid("map-" + id); map.setInstanceId(id);
        map.setServiceId(7L); map.setAccountId(77L); map.setManaged(managed); map.setUpgrade(upgrade); map.setState("active"); return map;
    }
    private static Map<String, Action> actions() {
        return Map.of("finishupgrade", mock(Action.class), "rollback", mock(Action.class), "cancelupgrade", mock(Action.class));
    }
    private static ApiKeyPolicy custom(String stack, Set<String> operations) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("stack", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack(stack), operations)));
    }
    private static AiResourceChangeDomainTest.Fixture domainFixture() {
        var f = new AiResourceChangeDomainTest.Fixture(); when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
        when(f.schema.getResourceActions()).thenReturn(actions()); when(f.schemas.getSchema("stack")).thenReturn(f.schema);
        f.domain.targets = mock(ApiKeyTargetResolver.class);
        when(f.domain.targets.parseScopeId(anyString(), anyString()))
                .thenAnswer(call -> Long.parseLong(call.<String>getArgument(1).replaceFirst("^1(?:st|s)", "")));
        var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(f.schemas);
        formatter.setTypeMappings(Map.of("service", "s", "stack", "st")); ApiContext.getContext().setIdFormatter(formatter);
        var target = service(); var stack = new StackRecord(); stack.setId(3L); stack.setUuid("stack-three"); stack.setAccountId(77L);
        stack.setKind("stack"); stack.setName("Blue"); stack.setState("active");
        when(f.domain.objects.loadResource(Service.class, 7L)).thenReturn(target); when(f.domain.objects.loadResource(Stack.class, 3L)).thenReturn(stack);
        f.domain.managers = mock(ResourceManagerLocator.class); var manager = mock(ResourceManager.class);
        when(f.domain.managers.getResourceManagerByType(anyString())).thenReturn(manager);
        when(manager.getById(eq("service"), eq("1s7"), any())).thenReturn(target);
        when(manager.getById(eq("stack"), eq("1st3"), any())).thenReturn(stack); return f;
    }
    private static void nativeKey(AiResourceChangeDomainTest.Fixture f, ApiKeyPolicy policy) throws Exception {
        var access = new ApiKeyAuthorizationService(); var targets = mock(ApiKeyTargetResolver.class);
        when(targets.resolve(any(), any())).thenAnswer(call -> {
            ApiRequest request = call.getArgument(0);
            return List.of(ApiKeyPolicyEvaluator.Target.stackResource(request.getType(), request.getId(), "1a77", "1st3"));
        });
        for (var item : Map.of("targets", targets, "auditSinks", List.of(mock(ApiKeyAuditSink.class))).entrySet()) {
            var field = ApiKeyAuthorizationService.class.getDeclaredField(item.getKey()); field.setAccessible(true); field.set(access, item.getValue());
        }
        f.domain.keyAccess = access;
        ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(7, 42, policy == null ? "apiKey" : "apiKeyRestricted", 3, policy));
    }
    private AiResourceChangePlanServiceTest.Fixture planFixture(String operation) {
        var f = new AiResourceChangePlanServiceTest.Fixture(); var row = service();
        if ("cancel_upgrade".equals(operation)) row.setState("upgrading");
        f.target.from(row);
        when(f.domain.upgradeLifecycleSnapshot(any())).thenAnswer(call -> AiServiceUpgradeLifecycleChange.snapshot(call.getArgument(0), json));
        when(f.domain.upgradeLifecycleContext(any(), any(), anyString())).thenReturn(Map.of("uuid", "stack-three"));
        when(f.domain.changes(any(), any(), anyMap())).thenAnswer(call -> AiServiceUpgradeLifecycleChange.changes(call.getArgument(0), call.getArgument(1)));
        when(f.domain.snapshot(any())).thenAnswer(call -> call.getArgument(0) == null ? Map.of() : Map.of("managedInstances", List.of()));
        when(f.domain.upgradeLifecycleRetirementSnapshot(any())).thenReturn(Map.of("instances", List.of()));
        when(f.domain.upgradeLifecycleProcessOutcome(anyMap(), any())).thenReturn("running"); return f;
    }
    private static Map<String, Object> preview(AiResourceChangePlanServiceTest.Fixture f, String operation) {
        var request = f.request(null, true); request.setId(null);
        request.setRequestObject(Map.of("resourceType", "service", "operation", operation, "resourceId", "1s7", "parameters", Map.of()));
        var result = f.service.preview(request); f.id = (String) result.get("id"); return result;
    }
    private static void denied(int status, String code, Runnable action) {
        var error = assertThrows(ClientVisibleException.class, action::run); assertEquals(status, error.getStatus()); assertEquals(code, error.getCode());
    }
}
