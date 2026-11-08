package io.cattle.platform.iaas.api.auth.upgrade;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static io.cattle.platform.iaas.api.auth.upgrade.AiServiceUpgradePlanServiceTest.rejected;

import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiServiceUpgradeConfigurationTest {
    @After public void removeContext() { ApiContext.remove(); }
    private static AiServiceUpgradePlanServiceTest.Fixture fixture() {
        var f = new AiServiceUpgradePlanServiceTest.Fixture();
        f.target.setUuid("11111111-1111-1111-1111-111111111111"); return f;
    }
    private static Map<String, Object> primary(AiServiceUpgradePlanServiceTest.Fixture f) {
        return new LinkedHashMap<>(DataAccessor.fieldMap(f.target, "launchConfig"));
    }
    @Test public void configOnlyUsesRealParserAndPreservesImage() throws Exception {
        var f = fixture();
        var request = f.parsedPreview("{\"serviceId\":\"10\",\"ports\":[\"9090:90/tcp\"],\"healthCheck\":{\"port\":90,\"interval\":2000}}", Map.of());
        var result = f.service.preview(request); f.id = (String) result.get("id");
        assertEquals("docker:app:1", result.get("requestedImage"));
        assertEquals("docker:app:1", result.get("currentImage"));
        assertEquals(2, ((List<?>) result.get("configurationChanges")).size());
        assertFalse(result.containsKey("launchConfigChanges")); assertFalse(result.containsKey("snapshot"));
        verify(f.service.upgradeValidation).validate(eq(f.target), any());
        assertEquals(0, f.dispatched);
    }
    @Test public void explicitSameImageRequiresRealDeltaAndNoopDoesNotCreatePlan() {
        var f = fixture();
        assertEquals("UpgradeImageUnchanged", rejected(() -> f.preview(Map.of("serviceId", "10", "image", "app:1", "ports", List.of("8080:80/tcp")))));
        assertEquals("UpgradeImageUnchanged", rejected(() -> f.preview(Map.of("serviceId", "10", "ports", List.of("8080:80/tcp")))));
        assertEquals("UpgradeInputInvalid", rejected(() -> f.preview(Map.of("serviceId", "10"))));
        assertTrue(f.documents.isEmpty()); assertEquals(0, f.dispatched);
        assertEquals("preview", f.preview(Map.of("serviceId", "10", "image", "app:1", "ports", List.of("9090:90/tcp"))).get("status"));
    }
    @Test public void equivalentNativePortNotationCannotManufactureAnUpgrade() {
        var f = fixture();
        assertEquals("UpgradeImageUnchanged", rejected(() -> f.preview(Map.of("serviceId", "10", "ports", List.of("8080:80")))));
        assertTrue(f.documents.isEmpty());
    }
    @Test public void canonicalHealthNoopDoesNotManufactureUpgrade() {
        var f = fixture(); var lc = primary(f);
        lc.put("healthCheck", Map.of("type", "instanceHealthCheck", "port", 80, "strategy", "recreate"));
        DataAccessor.setField(f.target, "launchConfig", lc);
        assertEquals("UpgradeImageUnchanged", rejected(() -> f.preview(Map.of("serviceId", "10", "healthCheck", Map.of("port", 80)))));
        assertTrue(f.documents.isEmpty());
    }
    @Test public void preCoercionGuardRejectsMalformedUnknownAndForgedConfiguration() throws Exception {
        for (String fragment : List.of("\"ports\":\"80/tcp\"", "\"ports\":[80]", "\"ports\":null", "\"ports\":[\"99999:80/tcp\"]",
                "\"healthCheck\":{\"port\":\"80\"}", "\"healthCheck\":{\"port\":80,\"interval\":1.5}",
                "\"healthCheck\":{\"port\":80,\"headers\":{}}", "\"launchConfig\":{}", "\"configurationChanges\":[]", "\"launchConfigChanges\":{}")) {
            var f = fixture();
            when(f.schemas.getSchema("aiServiceUpgradePlan")).thenReturn(f.schema);
            when(f.schema.getId()).thenReturn("aiServiceUpgradePlan");
            String body = "{\"serviceId\":\"10\",\"image\":\"app:2\"," + fragment + "}";
            var request = new io.github.ibuildthecloud.gdapi.request.ApiRequest(null, f.schemas) {
                @Override public java.io.InputStream getInputStream() {
                    return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            };
            request.setMethod("POST"); request.setType("aiServiceUpgradePlan"); request.setRequestParams(Map.of());
            var mapper = new io.github.ibuildthecloud.gdapi.json.JacksonMapper(); mapper.init();
            var parser = new io.github.ibuildthecloud.gdapi.request.handler.BodyParserRequestHandler();
            parser.setJsonMarshaller(mapper); parser.init(); parser.handle(request);
            new AiServiceUpgradePlanInputHandler().handle(request);
            assertEquals(Boolean.TRUE, request.getAttribute(AiServiceUpgradePlanService.ORIGINAL_PREVIEW_REJECTED));
            assertEquals("UpgradeInputInvalid", rejected(() -> f.service.preview(request)));
            assertTrue(f.documents.isEmpty()); assertEquals(0, f.dispatched); verifyNoInteractions(f.service.upgradeValidation);
        }
    }
    @Test public void clearAndOmitHaveDistinctRealParserSemantics() throws Exception {
        var f = fixture(); var lc = primary(f); lc.put("healthCheck", Map.of("port", 80)); DataAccessor.setField(f.target, "launchConfig", lc);
        var request = f.parsedPreview("{\"serviceId\":\"10\",\"ports\":[],\"healthCheck\":null}", Map.of());
        var result = f.service.preview(request); f.id = (String) result.get("id"); f.action("confirm"); f.action("apply");
        Map<?, ?> strategy = (Map<?, ?>) ((Map<?, ?>) f.dispatchedRequest.getRequestObject()).get("inServiceStrategy");
        Map<?, ?> payload = (Map<?, ?>) strategy.get("launchConfig");
        assertEquals(List.of(), payload.get("ports")); assertTrue(payload.containsKey("healthCheck")); assertNull(payload.get("healthCheck"));
        assertEquals(lc.get("environment"), payload.get("environment"));
        assertEquals(lc.get("dataVolumes"), payload.get("dataVolumes"));
        var omitted = fixture(); var original = primary(omitted); original.put("healthCheck", Map.of("port", 80));
        DataAccessor.setField(omitted.target, "launchConfig", original);
        omitted.preview(Map.of("serviceId", "10", "ports", List.of("9090:90/tcp"))); omitted.action("confirm"); omitted.action("apply");
        Map<?, ?> unchanged = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) omitted.dispatchedRequest.getRequestObject()).get("inServiceStrategy")).get("launchConfig");
        assertEquals(original.get("healthCheck"), unchanged.get("healthCheck"));
    }
    @Test public void exactFullPrimarySecondaryPayloadAndOneDispatchArePreserved() {
        var f = fixture(); var before = primary(f);
        before.put("privileged", false); before.put("capAdd", List.of("NET_ADMIN")); before.put("cpuSet", "0");
        before.put("dataVolumesFrom", List.of("99")); before.put("devices", List.of("/dev/x:/dev/x:rw")); before.put("links", Map.of("db", "db"));
        DataAccessor.setField(f.target, "launchConfig", before);
        Object sides = DataAccessor.field(f.target, "secondaryLaunchConfigs", Object.class);
        f.preview(Map.of("serviceId", "10", "ports", List.of("9090:90/tcp"), "healthCheck", Map.of("port", 90)));
        f.action("confirm"); f.action("apply"); f.action("apply"); assertEquals(1, f.dispatched);
        Map<?, ?> strategy = (Map<?, ?>) ((Map<?, ?>) f.dispatchedRequest.getRequestObject()).get("inServiceStrategy");
        var expected = new LinkedHashMap<>(before); expected.put("ports", List.of("9090:90/tcp"));
        expected.put("healthCheck", Map.of("port", 90, "strategy", "recreate"));
        assertEquals(expected, strategy.get("launchConfig")); assertNull(strategy.get("secondaryLaunchConfigs"));
        assertEquals(sides, ((Map<?, ?>) f.document().get("snapshot")).get("secondaryLaunchConfigs"));
        var captor = org.mockito.ArgumentCaptor.forClass(io.cattle.platform.core.addon.InServiceUpgradeStrategy.class);
        verify(f.service.upgradeValidation, atLeastOnce()).validate(eq(f.target), captor.capture());
        assertEquals(sides, captor.getValue().getSecondaryLaunchConfigs());
        assertEquals("upgrade", f.dispatchedRequest.getAction()); assertEquals("POST", f.dispatchedRequest.getMethod());
        assertNotNull(f.dispatchedRequest.getAttribute(AiServiceUpgradePlanService.SNAPSHOT_ATTRIBUTE));
    }
    @Test public void healthPatchPreservesOmittedSupportedFieldsIncludingNoneStrategy() throws Exception {
        var f = fixture(); var lc = primary(f);
        lc.put("healthCheck", Map.of("port", 80, "strategy", "none", "responseTimeout", 3000, "healthyThreshold", 2));
        DataAccessor.setField(f.target, "launchConfig", lc);
        var request = f.parsedPreview("{\"serviceId\":\"10\",\"healthCheck\":{\"interval\":2500}}", Map.of());
        var result = f.service.preview(request); f.id = (String) result.get("id");
        Map<?, ?> resolved = (Map<?, ?>) ((Map<?, ?>) f.document().get("launchConfigChanges")).get("healthCheck");
        assertEquals(Map.of("port", 80, "strategy", "none", "responseTimeout", 3000, "healthyThreshold", 2, "interval", 2500), resolved);
        f.action("confirm"); f.action("apply");
        Map<?, ?> payload = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) f.dispatchedRequest.getRequestObject()).get("inServiceStrategy")).get("launchConfig");
        assertEquals(resolved, payload.get("healthCheck"));
        var fresh = fixture();
        assertEquals("UpgradeInputInvalid", rejected(() -> fresh.preview(Map.of("serviceId", "10", "healthCheck", Map.of("interval", 2500)))));
    }
    @Test public void unsupportedHealthIsPreservedWhenOmittedButCannotBeEditedOrClearedWithoutCompleteReview() {
        for (boolean clear : List.of(false, true)) {
            var f = fixture(); var lc = primary(f);
            var health = Map.of("port", 80, "strategy", "recreate", "recreateOnQuorumStrategyConfig", Map.of("quorum", 1));
            lc.put("healthCheck", health); DataAccessor.setField(f.target, "launchConfig", lc);
            var input = new LinkedHashMap<String, Object>(); input.put("serviceId", "10"); input.put("healthCheck", clear ? null : Map.of("port", 90));
            assertEquals("UpgradeConfigurationUnsupported", rejected(() -> f.preview(input)));
            assertTrue(f.documents.isEmpty()); assertEquals(0, f.dispatched);
            f.preview(Map.of("serviceId", "10", "ports", List.of("90/tcp"))); f.action("confirm"); f.action("apply");
            Map<?, ?> payload = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) f.dispatchedRequest.getRequestObject()).get("inServiceStrategy")).get("launchConfig");
            assertEquals(health, payload.get("healthCheck"));
        }
    }
    @Test public void targetDriftAndApprovedOperandDriftCannotClaimOrDispatch() {
        for (boolean operand : List.of(false, true)) {
            var f = fixture(); f.preview(Map.of("serviceId", "10", "ports", List.of("9090:90/tcp"))); f.action("confirm");
            if (operand) f.document().put("launchConfigChanges", Map.of("ports", List.of("9191:91/tcp")));
            else { var lc = primary(f); lc.put("memory", 1024); DataAccessor.setField(f.target, "launchConfig", lc); }
            assertEquals("UpgradePlanStale", rejected(() -> f.action("apply"))); assertEquals(0, f.dispatched);
            assertNull(f.document().get("operationId")); assertFalse(f.deniedReasons.isEmpty());
        }
    }
    @Test public void terminalReadbackRequiresRequestedConfigAndEveryOtherSetting() {
        var f = fixture(); f.preview(Map.of("serviceId", "10", "ports", List.of("9090:90/tcp"), "healthCheck", Map.of("port", 90)));
        assertFalse(f.service.settingsPreserved(f.document(), f.target));
        var lc = primary(f); lc.put("ports", List.of("9090:90/tcp"));
        lc.put("healthCheck", Map.of("type", "instanceHealthCheck", "port", 90, "strategy", "recreate"));
        DataAccessor.setField(f.target, "launchConfig", lc); assertTrue(f.service.settingsPreserved(f.document(), f.target));
        lc.put("memory", 1024); DataAccessor.setField(f.target, "launchConfig", lc); assertFalse(f.service.settingsPreserved(f.document(), f.target));
    }
    @Test public void configurationPlanAlsoFencesExactTargetUuid() {
        var f = fixture(); f.preview(Map.of("serviceId", "10", "ports", List.of("90/tcp")));
        assertEquals(f.target.getUuid(), ((Map<?, ?>) f.document().get("snapshot")).get("targetUuid"));
        f.target.setUuid("22222222-2222-2222-2222-222222222222");
        assertEquals("UpgradePlanStale", rejected(() -> f.action("confirm"))); assertEquals(0, f.dispatched);
    }
    @Test public void configOnlyReceiptRequiresNativeFinishExactReadbackAndReadiness() {
        var f = fixture(); f.preview(Map.of("serviceId", "10", "ports", List.of("90/tcp"))); f.action("confirm"); f.action("apply");
        f.observed.add(Map.of("name", "service.upgrade", "ended", true, "result", "SUCCESS"));
        assertEquals("running", f.service.get(f.id, f.request(Map.of())).get("status"));
        f.observed.add(Map.of("name", "service.finishupgrade", "ended", true, "result", "SUCCESS"));
        assertEquals("UpgradeResultMismatch", f.service.get(f.id, f.request(Map.of())).get("messageCode"));
        DataAccessor.fieldMap(f.target, "launchConfig").put("ports", List.of("90/tcp")); f.ready = false;
        assertEquals("running", f.service.get(f.id, f.request(Map.of())).get("status"));
        f.ready = true; var result = f.service.get(f.id, f.request(Map.of()));
        assertEquals("succeeded", result.get("status")); assertEquals(Boolean.TRUE, result.get("verifiedResult"));
        assertFalse(f.document().containsKey("snapshot")); assertEquals(1, f.dispatched);
    }
    @Test public void approvalCannotOverrideConfigurationAndCancelKeepsNativeUntouched() {
        var f = fixture(); f.preview(Map.of("serviceId", "10", "ports", List.of("9090:90/tcp")));
        assertEquals("UpgradeInputInvalid", rejected(() -> f.service.action(f.id, f.request("confirm", Map.of("ports", List.of())))));
        assertEquals("cancelled", f.action("cancel").get("status")); assertEquals(0, f.dispatched);
    }
    @Test public void legacyImageOnlyPlanKeepsItsOriginalDefaults() {
        new AiServiceUpgradePlanServiceTest().legacySnapshotWithoutStrategyOrFenceStillConfirmsAndAppliesDefaults();
    }
    @Test public void originalStrategyFenceStillRejectsDrift() {
        new AiServiceUpgradePlanServiceTest().strategyAndSnapshotFenceDriftCannotClaimOrDispatch();
    }
    @Test public void originalHumanAndKeyBoundaryStillHolds() {
        new AiServiceUpgradePlanServiceTest().keyPlansAreBoundAndOnlyTheHumanCanConfirm();
    }
    @Test public void originalConfirmationRechecksTheKey() {
        new AiServiceUpgradePlanServiceTest().humanConfirmationRechecksTheBoundKeyAndCannotApproveAfterRevocation();
    }
    @Test public void originalRevokedRoleStillDeniesAndAudits() {
        new AiServiceUpgradePlanServiceTest().revokedUpgradeRoleIsCheckedAgainAndDeniedAudited();
    }
    @Test public void configOnlyRechecksExactBoundKeyAndRoleWithoutAnAlternateWriter() {
        var f = fixture(); ApiContext.newContext().setPolicy(f.policy);
        f.key = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, null);
        f.preview(Map.of("serviceId", "10", "ports", List.of("90/tcp")));
        assertEquals("AiKeyConfirmationForbidden", rejected(() -> f.action("confirm")));
        f.key = null; f.action("confirm");
        assertEquals("UpgradeBoundKeyRequired", rejected(() -> f.action("apply")));
        f.key = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 4, null);
        assertEquals("NotFound", rejected(() -> f.action("apply")));
        f.key = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, null);
        doThrow(new io.github.ibuildthecloud.gdapi.exception.ClientVisibleException(403, "ApiKeyRevoked")).when(f.service.keyAuthorization)
                .authorizeBound(anyMap(), any(), anyString(), any());
        assertEquals("ApiKeyRevoked", rejected(() -> f.action("apply"))); assertEquals(0, f.dispatched);
    }

    @Test public void startupArgumentsUseRealParserAndPreserveTheWholeApprovedLaunchConfiguration() throws Exception {
        var f = fixture(); var before = primary(f);
        Object sides = DataAccessor.field(f.target, "secondaryLaunchConfigs", Object.class);
        var request = f.parsedPreview("{\"serviceId\":\"10\",\"command\":[\"serve\",\"  exact  \",\"\",\"繁體中文\",\"|\"],"
                + "\"entryPoint\":[\"/bin/app\"]}", Map.of());
        assertEquals(Boolean.FALSE, request.getAttribute(AiServiceUpgradePlanService.ORIGINAL_PREVIEW_REJECTED));
        var result = f.service.preview(request); f.id = (String) result.get("id");
        assertEquals(result.get("currentImage"), result.get("requestedImage"));
        assertEquals(2, ((List<?>) result.get("configurationChanges")).size()); assertEquals(0, f.dispatched);
        assertEquals("UpgradeNeedsConfirmation", rejected(() -> f.action("apply"))); assertEquals(0, f.dispatched);
        f.action("confirm"); f.action("apply"); f.action("apply");
        Map<?, ?> strategy = (Map<?, ?>) ((Map<?, ?>) f.dispatchedRequest.getRequestObject()).get("inServiceStrategy");
        var expected = new LinkedHashMap<>(before);
        expected.put("command", List.of("serve", "  exact  ", "", "繁體中文", "|")); expected.put("entryPoint", List.of("/bin/app"));
        assertEquals(expected, strategy.get("launchConfig")); assertEquals(1, f.dispatched);
        assertEquals(sides, ((Map<?, ?>) f.document().get("snapshot")).get("secondaryLaunchConfigs"));
        assertEquals("upgrade", f.dispatchedRequest.getAction()); assertEquals("POST", f.dispatchedRequest.getMethod());
    }

    @Test public void startupArgumentsKeepNativeNullEmptyOmitAndNoopDistinct() throws Exception {
        var f = fixture(); var before = primary(f);
        before.put("command", List.of("old")); before.put("entryPoint", List.of("/old")); DataAccessor.setField(f.target, "launchConfig", before);
        var request = f.parsedPreview("{\"serviceId\":\"10\",\"command\":null,\"entryPoint\":[]}", Map.of());
        var preview = f.service.preview(request); f.id = (String) preview.get("id"); f.action("confirm"); f.action("apply");
        Map<?, ?> payload = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) f.dispatchedRequest.getRequestObject()).get("inServiceStrategy")).get("launchConfig");
        assertTrue(payload.containsKey("command")); assertNull(payload.get("command")); assertEquals(List.of(), payload.get("entryPoint"));
        var omitted = fixture(); var original = primary(omitted);
        original.put("command", List.of("keep")); original.put("entryPoint", List.of("/keep")); DataAccessor.setField(omitted.target, "launchConfig", original);
        omitted.preview(Map.of("serviceId", "10", "ports", List.of("90/tcp"))); omitted.action("confirm"); omitted.action("apply");
        Map<?, ?> unchanged = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) omitted.dispatchedRequest.getRequestObject()).get("inServiceStrategy")).get("launchConfig");
        assertEquals(original.get("command"), unchanged.get("command")); assertEquals(original.get("entryPoint"), unchanged.get("entryPoint"));
        var noop = fixture(); var lc = primary(noop); lc.put("command", List.of()); DataAccessor.setField(noop.target, "launchConfig", lc);
        assertEquals("UpgradeImageUnchanged", rejected(() -> noop.preview(Map.of("serviceId", "10", "command", List.of()))));
        var clearing = new LinkedHashMap<String, Object>(); clearing.put("serviceId", "10"); clearing.put("command", null);
        assertEquals("preview", noop.preview(clearing).get("status"));
    }

    @Test public void startupArgumentsRejectRawCoercionAndEveryClosedArrayLimitBeforeNativeValidation() throws Exception {
        var json = new io.github.ibuildthecloud.gdapi.json.JacksonMapper(); json.init();
        for (Object value : List.of("serve --flag", Map.of(), List.of(1), java.util.Collections.singletonList(null),
                List.of("bad\0arg"), java.util.Collections.nCopies(65, ""),
                List.of("x".repeat(4097)), java.util.Collections.nCopies(5, "x".repeat(4096)))) {
            for (String field : List.of("command", "entryPoint")) {
                var f = fixture(); when(f.schemas.getSchema("aiServiceUpgradePlan")).thenReturn(f.schema);
                when(f.schema.getId()).thenReturn("aiServiceUpgradePlan");
                String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("serviceId", "10", field, value));
                var request = new io.github.ibuildthecloud.gdapi.request.ApiRequest(null, f.schemas) {
                    @Override public java.io.InputStream getInputStream() {
                        return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                };
                request.setMethod("POST"); request.setType("aiServiceUpgradePlan"); request.setRequestParams(Map.of());
                var parser = new io.github.ibuildthecloud.gdapi.request.handler.BodyParserRequestHandler();
                parser.setJsonMarshaller(json); parser.init(); parser.handle(request); new AiServiceUpgradePlanInputHandler().handle(request);
                assertEquals(Boolean.TRUE, request.getAttribute(AiServiceUpgradePlanService.ORIGINAL_PREVIEW_REJECTED));
                assertEquals("UpgradeInputInvalid", rejected(() -> f.service.preview(request)));
                assertTrue(f.documents.isEmpty()); assertEquals(0, f.dispatched); verifyNoInteractions(f.service.upgradeValidation);
            }
        }
        for (List<String> value : List.of(java.util.Collections.nCopies(64, ""), java.util.Collections.nCopies(4, "x".repeat(4096)))) {
            var f = fixture();
            var request = f.parsedPreview(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("serviceId", "10", "command", value)), Map.of());
            assertEquals(Boolean.FALSE, request.getAttribute(AiServiceUpgradePlanService.ORIGINAL_PREVIEW_REJECTED));
            assertEquals("preview", f.service.preview(request).get("status")); assertEquals(0, f.dispatched);
        }
    }

    @Test public void startupArgumentsRequireFreshBoundApprovalExactNativeReadbackAndReadiness() {
        for (boolean operand : List.of(false, true)) {
            var f = fixture(); f.preview(Map.of("serviceId", "10", "command", List.of("serve"))); f.action("confirm");
            if (operand) f.document().put("launchConfigChanges", Map.of("command", List.of("changed")));
            else { var lc = primary(f); lc.put("entryPoint", List.of("drift")); DataAccessor.setField(f.target, "launchConfig", lc); }
            assertEquals("UpgradePlanStale", rejected(() -> f.action("apply"))); assertEquals(0, f.dispatched);
        }
        var key = fixture(); ApiContext.newContext().setPolicy(key.policy);
        key.key = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, null);
        key.preview(Map.of("serviceId", "10", "entryPoint", List.of("/app")));
        assertEquals("AiKeyConfirmationForbidden", rejected(() -> key.action("confirm")));
        key.key = null; key.action("confirm"); assertEquals("UpgradeBoundKeyRequired", rejected(() -> key.action("apply")));
        var foreign = fixture(); foreign.preview(Map.of("serviceId", "10", "command", List.of("serve")));
        foreign.document().put("actorId", 999L);
        assertEquals("NotFound", rejected(() -> foreign.action("confirm"))); assertEquals(0, foreign.dispatched);
        var cancelled = fixture(); cancelled.preview(Map.of("serviceId", "10", "command", List.of("serve")));
        assertEquals("cancelled", cancelled.action("cancel").get("status")); assertEquals(0, cancelled.dispatched);
        var f = fixture(); f.preview(Map.of("serviceId", "10", "command", List.of("serve"))); f.action("confirm"); f.action("apply");
        f.observed.add(Map.of("name", "service.upgrade", "ended", true, "result", "SUCCESS"));
        f.observed.add(Map.of("name", "service.finishupgrade", "ended", true, "result", "SUCCESS"));
        assertEquals("UpgradeResultMismatch", f.service.get(f.id, f.request(Map.of())).get("messageCode"));
        DataAccessor.fieldMap(f.target, "launchConfig").put("command", List.of("serve")); f.ready = false;
        assertEquals("running", f.service.get(f.id, f.request(Map.of())).get("status")); f.ready = true;
        assertEquals(Boolean.TRUE, f.service.get(f.id, f.request(Map.of())).get("verifiedResult")); assertEquals(1, f.dispatched);
    }
}
