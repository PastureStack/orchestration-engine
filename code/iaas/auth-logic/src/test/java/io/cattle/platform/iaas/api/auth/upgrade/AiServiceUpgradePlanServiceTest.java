package io.cattle.platform.iaas.api.auth.upgrade;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.BodyParserRequestHandler;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class AiServiceUpgradePlanServiceTest {
    @Test public void snapshotMapKeepsNestedValuesDetachedAndNullProducesMutableEmptyMap() {
        Fixture f = new Fixture();
        Map<String, Object> environment = new LinkedHashMap<>(Map.of("TEXT", "\u4fdd\u7559", "EMPTY", ""));
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("environment", environment); original.put("ports", new ArrayList<>(List.of("8080:80/tcp"))); original.put("optional", null);
        Map<String, Object> snapshot = f.service.map(original);
        assertEquals(original, snapshot);
        assertNotSame(environment, snapshot.get("environment")); assertNotSame(original.get("ports"), snapshot.get("ports"));
        environment.put("TEXT", "changed");
        assertEquals("\u4fdd\u7559", ((Map<?, ?>) snapshot.get("environment")).get("TEXT"));
        ((List<?>) snapshot.get("ports")).clear();
        assertEquals(List.of("8080:80/tcp"), original.get("ports"));
        Map<String, Object> empty = f.service.map(null); empty.put("optional", null);
        assertTrue(empty.containsKey("optional"));
        assertThrows(IllegalStateException.class, () -> f.service.map("not an object"));
    }
    @Test public void actualBodyAndActionValidationPipelineRetainsOnlyMatchingRoutingAction() throws Exception {
        Fixture f = new Fixture(); f.preview();
        ApiContext.newContext().setIdFormatter(new IdentityFormatter());
        try {
            ApiRequest unapproved = f.parsedAction("apply", "{}");
            assertEquals(Map.of("action", "apply"), unapproved.getRequestObject());
            assertEquals("UpgradeNeedsConfirmation", rejected(() -> f.service.action(f.id, unapproved)));
            assertEquals(0, f.dispatched);
            assertEquals("approved", f.service.action(f.id, f.parsedAction("confirm", "{}")).get("confirmationState"));
            assertEquals("accepted", f.service.action(f.id, f.parsedAction("apply", "{}")).get("status"));
            for (String body : List.of("{\"confirmed\":true}", "{\"image\":\"evil:1\"}", "{\"serviceId\":99}", "{\"action\":\"confirm\"}")) {
                ApiRequest forged = f.parsedAction("apply", body);
                assertEquals("UpgradeInputInvalid", rejected(() -> f.service.action(f.id, forged)));
            }
            assertEquals(1, f.dispatched);
        } finally { ApiContext.remove(); }
    }
    @Test public void planPathDistinguishesSameNamedServicesAndUsesOnlyAuthorizedAncestry() {
        Fixture first = new Fixture();
        assertEquals("QA / frontend / web", first.preview().get("displayPath"));
        assertEquals("QA / frontend / web", first.service.get(first.id, first.request(Map.of())).get("displayPath"));
        Fixture second = new Fixture(); second.target.setId(11L); second.stack.setName("backend");
        assertEquals("QA / backend / web", second.preview().get("displayPath"));
        first.stack.setAccountId(88L);
        assertEquals("NotFound", rejected(() -> first.service.get(first.id, first.request(Map.of()))));
        assertEquals(0, first.dispatched); assertEquals(0, second.dispatched);
    }
    @Test public void cookieActorRetainsNormalPolicyAndKeyDoesNotBecomeCookieDelegation() {
        Policy policy = mock(Policy.class); when(policy.getAuthenticatedAsAccountId()).thenReturn(42L);
        AiServiceUpgradePlanService service = new AiServiceUpgradePlanService(); service.objects = mock(ObjectManager.class);
        AccountRecord account = new AccountRecord(); account.setId(42L); account.setState("active");
        when(service.objects.loadResource(Account.class, 42L)).thenReturn(account);
        ApiContext.newContext().setPolicy(policy); ApiContext.getContext().setIdFormatter(new IdentityFormatter());
        try {
            ApiRequest request = new ApiRequest(null, null);
            assertSame(policy, service.actor(request));
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1L, 42L, "apiKey", 1L, null));
            assertEquals("KeyAuthorizationUnavailable", rejected(() -> service.actor(request)));
        } finally { ApiContext.remove(); }
    }
    @Test public void keyPlansAreBoundAndOnlyTheHumanCanConfirm() {
        Fixture f = new Fixture();
        ApiContext.newContext().setPolicy(f.policy);
        try {
            f.key = new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, null);
            f.preview();
            assertEquals(7L, ((Number) f.document().get("credentialId")).longValue());
            assertEquals(3L, ((Number) f.document().get("credentialRevision")).longValue());
            assertEquals("UpgradeNeedsConfirmation", rejected(() -> f.action("apply")));
            assertEquals(0, f.dispatched);
            assertEquals("AiKeyConfirmationForbidden", rejected(() -> f.action("confirm")));
            f.key = new ApiKeyCredentialContext(8, 42, "apiKeyRestricted", 3, null);
            assertEquals("NotFound", rejected(() -> f.service.get(f.id, f.request(Map.of()))));
            f.key = new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 4, null);
            assertEquals("NotFound", rejected(() -> f.action("apply")));
            f.key = null;
            f.action("confirm");
            verify(f.service.keyAuthorization).authorizeBound(anyMap(), eq(f.target), eq("upgrade"), any());
            assertEquals("UpgradeBoundKeyRequired", rejected(() -> f.action("apply")));
            f.key = new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, null);
            assertEquals("accepted", f.action("apply").get("status"));
            assertEquals(1, f.dispatched);
        } finally { ApiContext.remove(); }
    }

    @Test public void humanConfirmationRechecksTheBoundKeyAndCannotApproveAfterRevocation() {
        Fixture f = new Fixture();
        ApiContext.newContext().setPolicy(f.policy);
        try {
            f.key = new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, null); f.preview(); f.key = null;
            doThrow(new ClientVisibleException(403, "ApiKeyRevoked")).when(f.service.keyAuthorization)
                    .authorizeBound(anyMap(), any(), anyString(), any());
            assertEquals("ApiKeyRevoked", rejected(() -> f.action("confirm")));
            assertEquals("pending", f.document().get("confirmationState"));
            assertEquals(0, f.dispatched);
        } finally { ApiContext.remove(); }
    }

    @Test public void humanInboxIsBoundedPrivateAndKeyCannotList() {
        Fixture f = new Fixture();
        f.preview();
        String own = f.id;
        Map<String,Object> foreign = new HashMap<>(f.document()); foreign.put("id", "aiplan-foreign"); foreign.put("accountId", 88);
                f.documents.put("aiplan-foreign", foreign);
        Map<String,Object> approved = new HashMap<>(f.document()); approved.put("id", "aiplan-approved"); approved.put("confirmationState",
                "approved"); f.documents.put("aiplan-approved", approved);
        List<Map<String,Object>> pending = f.service.pending(f.request(Map.of()));
        assertEquals(2, pending.size());
        assertEquals(Set.of(own, "aiplan-approved"), pending.stream().map(plan -> plan.get("id")).collect(java.util.stream.Collectors.toSet()));
        assertFalse(pending.getFirst().containsKey("snapshot")); assertFalse(pending.getFirst().containsKey("actorId"));
        ApiContext.newContext().setPolicy(f.policy);
        try {
            f.key = new ApiKeyCredentialContext(7,42,"apiKeyRestricted",3,null);
            assertEquals("UpgradeHumanSessionRequired",rejected(()->f.service.pending(f.request(Map.of()))));
        } finally { ApiContext.remove(); }
    }

    @Test public void approvedUndispatchedPlansStayCancellableButAcceptedOperationsDoNot() {
        Fixture f = new Fixture(); f.preview(); f.action("confirm");
        List<Map<String, Object>> inbox = f.service.pending(f.request(Map.of()));
        assertEquals(1, inbox.size()); assertEquals("approved", inbox.getFirst().get("confirmationState"));
        assertEquals("cancelled", f.action("cancel").get("status"));
        assertTrue(f.service.pending(f.request(Map.of())).isEmpty()); assertEquals(0, f.dispatched);
        Fixture accepted = new Fixture(); accepted.preview(); accepted.action("confirm"); accepted.action("apply");
        assertTrue(accepted.service.pending(accepted.request(Map.of())).isEmpty());
        assertEquals("UpgradeAlreadyAccepted", rejected(() -> accepted.action("cancel"))); assertEquals(1, accepted.dispatched);
    }

    @Test public void previewIsPrivateImmutableAndDoesNotUpgrade() {
        Fixture f = new Fixture();
        Map<String, Object> preview = f.preview();
        assertEquals("pending", preview.get("confirmationState"));
        assertEquals("preview", preview.get("status"));
        assertFalse(preview.containsKey("snapshot")); assertFalse(preview.containsKey("actorId"));
        assertEquals("docker:app:1", DataAccessor.fieldMap(f.target, "launchConfig").get("imageUuid"));
        verify(f.manager, never()).resourceAction(anyString(), any());
        DataAccessor.fieldMap(f.target, "launchConfig").put("environment", Map.of("CHANGED", "yes"));
        assertEquals(Map.of("KEEP", "yes"), ((Map<?, ?>) f.document().get("snapshot")).get("launchConfig") instanceof Map<?, ?> primary ?
                primary.get("environment") : null);
    }

    @Test public void cannotSupplyApprovalOrChangeTheApprovedPayload() {
        Fixture f = new Fixture();
        assertEquals("UpgradeInputInvalid", rejected(() -> f.service.preview(f.request(Map.of("serviceId", "10", "image", "app:2", "confirmed", true)))));
        f.preview();
        assertEquals("UpgradeNeedsConfirmation", rejected(() -> f.action("apply")));
        assertEquals("UpgradeInputInvalid", rejected(() -> f.service.action(f.id, f.request("confirm", Map.of("confirmed", true)))));
        assertEquals(0, f.dispatched);
    }

    @Test public void approvedApplyDispatchesExactlyOnceAndPreservesPrimarySettings() {
        Fixture f = new Fixture(); f.preview(); f.action("confirm");
        Map<String, Object> accepted = f.action("apply");
        assertEquals("accepted", accepted.get("status")); assertEquals(f.id, accepted.get("operationId"));
        assertEquals(Boolean.FALSE, accepted.get("verifiedResult"));
        f.action("apply"); assertEquals(1, f.dispatched);
        Map<?, ?> strategy = (Map<?, ?>) ((Map<?, ?>) f.dispatchedRequest.getRequestObject()).get("inServiceStrategy");
        Map<?, ?> primary = (Map<?, ?>) strategy.get("launchConfig");
        assertEquals("docker:app:2", primary.get("imageUuid"));
        assertEquals(Map.of("KEEP", "yes"), primary.get("environment"));
        assertEquals(List.of("8080:80/tcp"), primary.get("ports"));
        assertEquals(List.of("named:/data"), primary.get("dataVolumes"));
        assertEquals(512, primary.get("memory"));
        assertNull(strategy.get("secondaryLaunchConfigs"));
        assertEquals("docker:app:1", DataAccessor.fieldMap(f.target, "launchConfig").get("imageUuid"));
        assertEquals(f.id, f.dispatchedRequest.getAttribute(AiServiceUpgradePlanService.PLAN_ATTRIBUTE));
        assertEquals("10", f.dispatchedRequest.getId());
        assertEquals("1s10", accepted.get("serviceId"));
        verify(f.service.objects, never()).loadResource(Service.class, "1s10");
    }

    @Test public void actorMismatchAndCrossProjectCannotReadOrApply() {
        Fixture f = new Fixture(); f.preview();
        when(f.policy.getAuthenticatedAsAccountId()).thenReturn(43L);
        assertEquals("NotFound", rejected(() -> f.service.get(f.id, f.request(Map.of()))));
        assertEquals("NotFound", rejected(() -> f.action("confirm")));
        when(f.policy.getAuthenticatedAsAccountId()).thenReturn(42L);
        when(f.policy.getAccountId()).thenReturn(88L);
        assertEquals("NotFound", rejected(() -> f.service.get(f.id, f.request(Map.of()))));
        assertEquals(0, f.dispatched);
    }

    @Test public void revokedUpgradeRoleIsCheckedAgainAndDeniedAudited() {
        Fixture f = new Fixture(); f.preview(); f.action("confirm");
        when(f.schema.getResourceActions()).thenReturn(Map.of());
        assertEquals("PermissionDenied", rejected(() -> f.action("apply")));
        when(f.schema.getResourceActions()).thenReturn(Map.of("upgrade", new Action()));
        assertEquals("PermissionDenied", rejected(() -> f.action("apply")));
        assertTrue(f.denied > 0); assertEquals(0, f.dispatched);
    }

    @Test public void inaccessibleServiceAndForeignIdAreRejected() {
        Fixture f = new Fixture();
        f.target.setAccountId(99L);
        assertEquals("NotFound", rejected(f::preview));
        f.target.setAccountId(77L); when(f.manager.getById(anyString(), anyString(), any())).thenReturn(null);
        assertEquals("NotFound", rejected(f::preview));
        assertTrue(f.denied >= 2);
    }

    @Test public void expiredAndChangedPlansCannotBeApprovedOrDispatched() {
        Fixture f = new Fixture(); f.preview();
        f.service.clock = Clock.fixed(Instant.parse("2026-10-29T00:06:00Z"), ZoneOffset.UTC);
        assertEquals("UpgradePlanExpired", rejected(() -> f.action("confirm")));
        f.service.clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
        f.action("confirm"); DataAccessor.setField(f.target, "scale", 2);
        assertEquals("UpgradePlanStale", rejected(() -> f.action("apply")));
        assertEquals(0, f.dispatched);
    }

    @Test public void cancellationIsOnlyBeforeAcceptance() {
        Fixture f = new Fixture(); f.preview();
        assertEquals("cancelled", f.action("cancel").get("status"));
        assertEquals("UpgradeCancelled", rejected(() -> f.action("apply")));
        assertEquals(0, f.dispatched);
    }

    @Test public void uncertainDispatchNeverRetriesEvenWhenItThrew() {
        Fixture f = new Fixture(); f.preview(); f.action("confirm"); f.failDispatch = true;
        assertEquals("unknown", f.action("apply").get("status"));
        f.action("apply"); assertEquals(1, f.dispatched);
        assertEquals("java.lang.IllegalStateException", f.document().get("dispatchFailureClass"));
        assertFalse(f.document().toString().contains("connection lost"));
        assertFalse(f.service.get(f.id, f.request(Map.of())).containsKey("dispatchFailureClass"));
    }

    @Test public void finishProcessAndReadbackAreRequiredForSuccess() {
        Fixture f = new Fixture(); f.preview(); f.action("confirm"); f.action("apply");
        f.observed.add(Map.of("name", "service.upgrade", "ended", true, "result", "SUCCESS"));
        assertEquals("running", f.service.get(f.id, f.request(Map.of())).get("status"));
        f.observed.add(Map.of("name", "service.finishupgrade", "ended", true, "result", "SUCCESS"));
        assertEquals("unknown", f.service.get(f.id, f.request(Map.of())).get("status"));
        DataAccessor.fieldMap(f.target, "launchConfig").put("imageUuid", "docker:app:2"); f.ready = false;
        assertEquals("running", f.service.get(f.id, f.request(Map.of())).get("status"));
        f.ready = true;
        Map<String, Object> success = f.service.get(f.id, f.request(Map.of()));
        assertEquals("succeeded", success.get("status")); assertEquals(Boolean.TRUE, success.get("verifiedResult"));
        assertFalse(f.document().containsKey("snapshot"));
    }
    @Test public void completedUpgradeWaitsForOldInstanceAndPortCleanupButRetainsUnchangedSidekick() {
        Fixture f = new Fixture(); f.preview(); f.action("confirm");
        InstanceRecord old = new InstanceRecord(); old.setId(101L); old.setAccountId(77L); old.setVersion("v1"); old.setState("running");
        InstanceRecord side = new InstanceRecord(); side.setId(102L); side.setAccountId(77L); side.setVersion("v1-side"); side.setState("running");
        ServiceExposeMapRecord primaryMap = new ServiceExposeMapRecord(); primaryMap.setInstanceId(101L); primaryMap.setManaged(true);
        ServiceExposeMapRecord sideMap = new ServiceExposeMapRecord(); sideMap.setInstanceId(102L); sideMap.setManaged(true); sideMap.setDnsPrefix("side");
        when(f.service.objects.find(ServiceExposeMap.class, "serviceId", 10L, "removed", null)).thenReturn(List.of(primaryMap, sideMap));
        when(f.service.objects.loadResource(Instance.class, 101L)).thenReturn(old);
        when(f.service.objects.loadResource(Instance.class, 102L)).thenReturn(side);
        f.action("apply");
        assertEquals(2, ((List<?>) f.document().get("priorInstances")).size());
        DataAccessor.fieldMap(f.target, "launchConfig").put("imageUuid", "docker:app:2");
        DataAccessor.fieldMap(f.target, "launchConfig").put("version", "v2");
        f.observed.add(Map.of("name", "service.finishupgrade", "ended", true, "result", "SUCCESS"));
        old.setState("removing");
        assertEquals("UpgradeCleanupPending", f.service.get(f.id, f.request(Map.of())).get("messageCode"));
        old.setState("removed"); old.setRemoved(new java.util.Date(1));
        when(f.service.objects.find(Port.class, "instanceId", 101L, "removed", null)).thenReturn(List.of(mock(Port.class)));
        assertEquals("running", f.service.get(f.id, f.request(Map.of())).get("status"));
        when(f.service.objects.find(Port.class, "instanceId", 101L, "removed", null)).thenReturn(List.of());
        when(f.service.objects.find(io.cattle.platform.core.model.Nic.class, "instanceId", 101L, "removed", null))
                .thenReturn(List.of(mock(io.cattle.platform.core.model.Nic.class)));
        assertEquals("UpgradeCleanupPending", f.service.get(f.id, f.request(Map.of())).get("messageCode"));
        when(f.service.objects.find(io.cattle.platform.core.model.Nic.class, "instanceId", 101L, "removed", null)).thenReturn(List.of());
        var mount = new io.cattle.platform.core.model.tables.records.MountRecord(); mount.setState("deactivating");
        when(f.service.objects.find(io.cattle.platform.core.model.Mount.class, "instanceId", 101L, "removed", null)).thenReturn(List.of(mount));
        assertEquals("UpgradeCleanupPending", f.service.get(f.id, f.request(Map.of())).get("messageCode"));
        mount.setState("inactive");
        assertEquals("succeeded", f.service.get(f.id, f.request(Map.of())).get("status"));
        verify(f.service.objects, times(1)).loadResource(Instance.class, 102L);
    }

    @Test public void canceledBusinessProcessIsFailureNotSuccess() {
        Fixture f = new Fixture(); f.preview(); f.action("confirm"); f.action("apply");
        f.observed.add(Map.of("name", "service.upgrade", "ended", true, "result", "CANCELED"));
        assertEquals("failed", f.service.get(f.id, f.request(Map.of())).get("status"));
        assertEquals(Boolean.FALSE, f.service.get(f.id, f.request(Map.of())).get("verifiedResult"));
    }

    @Test public void completedProcessWithChangedSettingsRemainsUnknown() {
        Fixture f = new Fixture(); f.preview(); f.action("confirm"); f.action("apply");
        DataAccessor.fieldMap(f.target, "launchConfig").put("imageUuid", "docker:app:2");
        DataAccessor.fieldMap(f.target, "launchConfig").put("environment", Map.of("CHANGED", "yes"));
        f.observed.add(Map.of("name", "service.finishupgrade", "ended", true, "result", "SUCCESS"));
        assertEquals("unknown", f.service.get(f.id, f.request(Map.of())).get("status"));
    }

    @Test public void acceptedWithoutDurableProcessEvidenceBecomesUnknownWithoutRetry() {
        Fixture f = new Fixture(); f.preview(); f.action("confirm"); f.action("apply");
        f.service.clock = Clock.fixed(Instant.parse("2026-10-29T00:02:00Z"), ZoneOffset.UTC);
        assertEquals("unknown", f.service.get(f.id, f.request(Map.of())).get("status"));
        f.action("apply"); assertEquals(1, f.dispatched);
    }

    @Test public void pendingPreviewsAreBoundedAndExpiredSnapshotsArePruned() {
        Fixture f = new Fixture();
        for (int i = 0; i < 20; i++) f.preview();
        assertEquals("UpgradePendingPlanLimit", rejected(f::preview));
        assertEquals(20, f.documents.size()); assertEquals(0, f.dispatched);
        f.service.clock = Clock.fixed(Instant.parse("2026-10-29T00:06:00Z"), ZoneOffset.UTC);
        f.preview(); assertEquals(1, f.documents.size());
    }
    @Test public void instanceReadbackRequiresEverySidekickHealthyAtItsCurrentVersion() {
        Fixture f = new Fixture();
        AiServiceUpgradePlanService live = new AiServiceUpgradePlanService(); live.json = new JacksonJsonMapper(); live.objects = mock(ObjectManager.class);
        ServiceExposeMapRecord primary = new ServiceExposeMapRecord(); primary.setInstanceId(101L); primary.setManaged(true); primary.setUpgrade(false);
        ServiceExposeMapRecord side = new ServiceExposeMapRecord(); side.setInstanceId(102L); side.setDnsPrefix("side"); side.setManaged(true);
                side.setUpgrade(false);
        InstanceRecord primaryInstance = new InstanceRecord(); primaryInstance.setState("running"); primaryInstance.setVersion("v1");
                primaryInstance.setHealthState("healthy");
        InstanceRecord sideInstance = new InstanceRecord(); sideInstance.setState("running"); sideInstance.setVersion("v1-side");
                sideInstance.setHealthState("healthy");
        when(live.objects.find(eq(ServiceExposeMap.class), eq("serviceId"), eq(10L), eq("removed"), isNull())).thenReturn(List.of(primary));
        when(live.objects.loadResource(Instance.class, 101L)).thenReturn(primaryInstance);
        when(live.objects.loadResource(Instance.class, 102L)).thenReturn(sideInstance);
        assertFalse(live.instancesReady(f.target));
        when(live.objects.find(eq(ServiceExposeMap.class), eq("serviceId"), eq(10L), eq("removed"), isNull())).thenReturn(List.of(primary, side));
        assertTrue(live.instancesReady(f.target));
        sideInstance.setVersion("stale"); assertFalse(live.instancesReady(f.target));
        sideInstance.setVersion("v1-side"); sideInstance.setHealthState("unhealthy"); assertFalse(live.instancesReady(f.target));
    }

    static String rejected(Runnable task) {
        try { task.run(); fail("Expected rejection"); return null; }
        catch (ClientVisibleException denied) { return denied.getCode(); }
    }
    static class Fixture {
        final Policy policy = mock(Policy.class);
        final Schema schema = mock(Schema.class);
        final SchemaFactory schemas = mock(SchemaFactory.class);
        final ResourceManager manager = mock(ResourceManager.class);
        final ServiceRecord target = new ServiceRecord();
        final AccountRecord project = new AccountRecord();
        final StackRecord stack = new StackRecord();
        final List<Map<String, Object>> observed = new ArrayList<>();
        final Map<String, Map<String, Object>> documents = new HashMap<>();
        final AiServiceUpgradePlanService service;
        String id; int dispatched; int denied; boolean ready = true; boolean failDispatch;
        ApiRequest dispatchedRequest;
        ApiKeyCredentialContext key;
        Fixture() {
            when(policy.getAccountId()).thenReturn(77L); when(policy.getAuthenticatedAsAccountId()).thenReturn(42L);
            target.setId(10L); target.setAccountId(77L); target.setKind("service"); target.setName("web"); target.setState("active");
            target.setData(new HashMap<>()); target.setStackId(3L);
            project.setId(77L); project.setName("QA"); project.setState("active");
            stack.setId(3L); stack.setAccountId(77L); stack.setName("frontend");
            Map<String, Object> primary = new LinkedHashMap<>(); primary.put("imageUuid", "docker:app:1");
            primary.put("environment", Map.of("KEEP", "yes")); primary.put("ports", List.of("8080:80/tcp"));
            primary.put("dataVolumes", List.of("named:/data")); primary.put("memory", 512); primary.put("version", "v1");
            DataAccessor.setField(target, "launchConfig", primary); DataAccessor.setField(target, "scale", 1);
            DataAccessor.setField(target, "secondaryLaunchConfigs", List.of(Map.of("name", "side", "imageUuid", "docker:side:1", "version",
                    "v1-side", "environment", Map.of("SIDE", "kept"))));
            when(schemas.getSchema("service")).thenReturn(schema); when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
            when(schema.getResourceActions()).thenReturn(Map.of("upgrade", new Action(), "finishupgrade", new Action()));
            when(manager.getById(anyString(), anyString(), any())).thenReturn(target);
            when(manager.resourceAction(anyString(), any())).thenReturn(target);
            service = new AiServiceUpgradePlanService() {
                @Override protected Policy actor(ApiRequest request) { return policy; }
                @Override protected void requireEnabled() { }
                @Override protected String formattedServiceId(Service target) { return "1s" + target.getId(); }
                @Override protected String normalizeImage(String image) { return "docker:" + image; }
                @Override protected long processBaseline(long id) { return 0; }
                @Override protected List<Map<String, Object>> processes(Map<String, Object> plan) { return observed; }
                @Override protected boolean instancesReady(Service target) { return ready; }
                @Override protected void dispatch(Map<String, Object> plan, Service target, ApiRequest request) {
                    dispatched++; if (failDispatch) throw new IllegalStateException("connection lost");
                    super.dispatch(plan, target, request);
                }
                @Override protected void audit(ApiRequest request, String action, String id, Long target, boolean allowed, String reason) { if
                        (!allowed) denied++; }
            };
            service.keyAuthorization = mock(AiKeyAuthorization.class);
            service.json = new JacksonJsonMapper(); service.clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
            service.objects = mock(ObjectManager.class); when(service.objects.loadResource(eq(Service.class), anyString())).thenReturn(target);
            when(service.objects.loadResource(Account.class, 77L)).thenReturn(project);
            when(service.objects.loadResource(Stack.class, 3L)).thenReturn(stack);
            service.managers = mock(ResourceManagerLocator.class); when(service.managers.getResourceManagerByType("service")).thenReturn(manager);
            doAnswer(call -> { dispatchedRequest = call.getArgument(1); return target; }).when(manager).resourceAction(anyString(), any());
            service.locks = mock(LockManager.class);
            when(service.locks.lock(any(), any())).thenAnswer(call -> ((LockCallback<?>) call.getArgument(1)).doWithLock());
            service.store = new AiServiceUpgradePlanStore() {
                @Override public void pruneExpiredPreviews(long now) {
                    documents.values().removeIf(plan -> List.of("preview", "cancelled").contains(plan.get("status"))
                            && !Instant.parse((String) plan.get("expiresAt")).isAfter(Instant.ofEpochMilli(now)));
                }
                @Override public List<Map<String,Object>> pending(long actor, long now) {
                    return documents.values().stream().filter(plan -> "preview".equals(plan.get("status"))
                        && ((Number) plan.get("actorId")).longValue() == actor
                        && Instant.parse((String) plan.get("expiresAt")).toEpochMilli() > now).limit(20).map(service::map).toList();
                }
                @Override public int pendingCount(long actor) { return (int) documents.values().stream().filter(plan ->
                        "preview".equals(plan.get("status")) && ((Number) plan.get("actorId")).longValue() == actor).count(); }
                @Override public void insert(Map<String, Object> plan) { save(plan); }
                @Override public Map<String, Object> load(String id) { return documents.get(id) == null ? null : service.map(documents.get(id)); }
                @Override public void save(Map<String, Object> plan) { documents.put((String) plan.get("id"), service.map(plan)); }
                @Override public boolean claim(Map<String, Object> plan) {
                    if (!"preview".equals(documents.get(plan.get("id")).get("status"))) return false;
                    save(plan); return true;
                }
            };
        }
        ApiRequest request(Map<String, Object> body) { ApiRequest r = new ApiRequest(null, schemas); r.setRequestObject(new HashMap<>(body)); if
                (key != null) { ApiKeyCredentialContext.attach(r, key); r.setAttribute("apiKey.audit.decision", "ALLOW"); } return r; }
        ApiRequest parsedAction(String action, String body) throws Exception {
            Schema planSchema = mock(Schema.class);
            when(planSchema.getResourceActions()).thenReturn(Map.of("apply", new Action(), "confirm", new Action(), "cancel", new Action()));
            when(schemas.getSchema("aiServiceUpgradePlan")).thenReturn(planSchema);
            ApiRequest request = new ApiRequest(null, schemas) {
                @Override public java.io.InputStream getInputStream() {
                    return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            };
            request.setId(id); request.setType("aiServiceUpgradePlan"); request.setMethod("POST"); request.setAction(action);
            request.setRequestParams(Map.of("action", new String[] { action }));
            io.github.ibuildthecloud.gdapi.json.JacksonMapper json = new io.github.ibuildthecloud.gdapi.json.JacksonMapper(); json.init();
            BodyParserRequestHandler parser = new BodyParserRequestHandler(); parser.setJsonMarshaller(json); parser.init(); parser.handle(request);
            new ValidationHandler().generate(request);
            return request;
        }
        ApiRequest request(String action, Map<String, Object> body) { ApiRequest r = request(body); r.setAction(action); return r; }
        Map<String, Object> preview() { Map<String, Object> result = service.preview(request(Map.of("serviceId", target.getId().toString(),
                "image", "app:2"))); id = (String) result.get("id"); return result; }
        Map<String, Object> action(String name) { return service.action(id, request(name, Map.of())); }
        Map<String, Object> document() { return documents.get(id); }
    }
}
