package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.tables.records.CertificateRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** New certificate subset through the existing domain and durable plan owners. */
public class AiCertificateMetadataChangeTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void onlyMetadataIntentAndFieldsAreAccepted() {
        var descriptor = descriptor();
        assertEquals("PUT", descriptor.method()); assertNull(descriptor.action()); assertEquals("update", descriptor.keyOperation());
        Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("name", "new-name"); metadata.put("description", null);
        assertEquals(metadata, descriptor.body(descriptor.parameters(metadata)));
        for (String field : List.of("cert", "key", "certChain", "data", "accountId", "uuid", "state"))
            assertEquals("ResourceChangeInputInvalid", assertThrows(ClientVisibleException.class,
                    () -> descriptor.parameters(Map.of(field, "PRIVATE-CANARY"))).getCode());
        for (String operation : List.of("create", "remove", "start", "stop", "restore", "update_targets"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("certificate", operation));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of()));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of("name", " ")));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of("description", "x".repeat(1025))));
    }

    @Test public void nativePutUsesRealRevisionGuardAndRestoresContext() throws Exception {
        var f = fixture(); var manager = f.domain.managers.getResourceManagerByType("certificate");
        var target = certificate(); Map<String, Object> body = Map.of("name", "safe-name");
        when(manager.update(eq("certificate"), eq("1cert7"), any())).thenAnswer(call -> {
            ApiRequest actual = call.getArgument(2); assertEquals(body, actual.getRequestObject());
            assertEquals("PUT", actual.getMethod()); assertNull(actual.getAction());
            assertTrue(AiResourceChangeDomain.trustedDomain(actual));
            assertSame(actual, ApiContext.getContext().getApiRequest()); return target;
        });
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            assertEquals(body, f.domain.prepare(f.request, descriptor(), "1cert7", body));
            assertSame(target, f.domain.dispatch(f.request, descriptor(), "1cert7", body, "own-plan"));
            assertSame(f.request, ApiContext.getContext().getApiRequest()); assertSame(f.policy, ApiContext.getContext().getPolicy());
            when(f.http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("2");
            assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class,
                    () -> f.domain.dispatch(f.request, descriptor(), "1cert7", body, "own-plan")).getCode());
            verify(manager, times(1)).update(eq("certificate"), eq("1cert7"), any());
            assertSame(f.request, ApiContext.getContext().getApiRequest());
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void nativeMethodFieldAndCrossProjectCeilingsRejectBeforeMutation() throws Exception {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            var f = fixture(); var manager = f.domain.managers.getResourceManagerByType("certificate");
            when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
            assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1cert7", Map.of("name", "safe"))).getCode());
            when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT")); f.name.setUpdate(false);
            assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1cert7", Map.of("name", "safe"))).getCode());
            verify(f.validation, never()).generate(any()); f.name.setUpdate(true);
            var row = certificate(); when(manager.getById(eq("certificate"), eq("1cert7"), any())).thenReturn(row);
            row.setAccountId(88L);
            assertEquals("NotFound", assertThrows(ClientVisibleException.class,
                    () -> f.domain.visible(f.request, descriptor(), "1cert7")).getCode());
            row.setAccountId(77L); row.setKind("service");
            assertEquals("NotFound", assertThrows(ClientVisibleException.class,
                    () -> f.domain.visible(f.request, descriptor(), "1cert7")).getCode());
            row.setKind("certificate"); assertSame(row, f.domain.visible(f.request, descriptor(), "1cert7"));
            verify(manager, never()).update(anyString(), anyString(), any());
        
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void durableSnapshotAndSummaryExcludeMaterialAndObserveExactMetadata() {
        var f = fixture(); var row = certificate();
        for (String field : List.of("cert", "key", "certChain")) DataAccessor.fields(row).withKey(field).set("PRIVATE-CANARY");
        DataAccessor.fields(row).withKey("certFingerprint").set("public-fingerprint");
        var snapshot = f.domain.snapshot(row);
        assertFalse(snapshot.containsKey("data")); assertFalse(snapshot.toString().contains("PRIVATE-CANARY"));
        assertEquals("certificate-uuid", snapshot.get("uuid")); assertEquals("public-fingerprint", snapshot.get("certFingerprint"));
        assertFalse(f.domain.complete(descriptor(), row, Map.of("name", "after"), snapshot));
        row.setName("after"); assertTrue(f.domain.complete(descriptor(), row, Map.of("name", "after"), snapshot));
        row.setRemoved(new Date()); assertFalse(f.domain.complete(descriptor(), row, Map.of("name", "after"), snapshot));
        assertFalse(f.domain.changes(descriptor(), row, Map.of("name", "after")).toString().contains("PRIVATE-CANARY"));
    }

    @Test public void boundPlanRequiresHumanApprovalAndExactOnceObservedCompletion() {
        var f = preview(); assertEquals(0, f.dispatched);
        denied("AiKeyConfirmationForbidden", () -> f.service.action(f.id, f.request("confirm", true)));
        denied("ResourceChangeNeedsConfirmation", () -> f.apply(true));
        f.confirm(); denied("ResourceChangeBoundKeyRequired", () -> f.apply(false));
        assertEquals("accepted", f.apply(true).get("status")); assertEquals(1, f.dispatched); assertEquals(1, f.claims);
        assertEquals("running", f.service.get(f.id, f.request(null, true)).get("status"));
        f.complete = true; var observed = f.service.get(f.id, f.request(null, true));
        assertEquals("succeeded", observed.get("status")); assertEquals(true, observed.get("verifiedResult"));
        f.apply(true); assertEquals(1, f.dispatched); assertEquals(1, f.claims);
        assertFalse(observed.containsKey("snapshot")); assertFalse(f.audits.toString().contains("new private description"));
    }

    @Test public void casAndLiveWriteRevocationNeverClaimOrDispatch() {
        var f = preview(); f.target.setName("changed-by-ui");
        denied("ResourceChangePlanStale", f::confirm); assertEquals(0, f.claims);
        f.target.setName("probe"); f.writeAllowed = false;
        denied("PermissionDenied", f::confirm); assertEquals(0, f.dispatched);
        f.writeAllowed = true; f.confirm(); f.writeAllowed = false;
        denied("PermissionDenied", () -> f.apply(true)); assertEquals(0, f.claims); assertEquals(0, f.dispatched);
    }

    private static AiResourceChangeDescriptor descriptor() { return AiResourceChangeDescriptor.require("certificate", "update_metadata"); }

    private static CertificateRecord certificate() {
        var row = new CertificateRecord(); row.setId(7L); row.setAccountId(77L); row.setUuid("certificate-uuid");
        row.setKind("certificate"); row.setName("before"); row.setState("active"); return row;
    }

    private static AiResourceChangeDomainTest.Fixture fixture() {
        var f = new AiResourceChangeDomainTest.Fixture(); when(f.schemas.getSchema("certificate")).thenReturn(f.schema);
        when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
        FieldImpl description = new FieldImpl(); description.setUpdate(true);
        when(f.schema.getResourceFields()).thenReturn(Map.of("name", f.name, "description", description));
        f.domain.managers = mock(ResourceManagerLocator.class);
        when(f.domain.managers.getResourceManagerByType("certificate")).thenReturn(mock(ResourceManager.class)); return f;
    }

    private static AiResourceChangePlanServiceTest.Fixture preview() {
        var f = new AiResourceChangePlanServiceTest.Fixture(); f.target.setKind("certificate");
        ApiRequest request = f.request(null, true); request.setId(null);
        request.setRequestObject(Map.of("resourceType", "certificate", "operation", "update_metadata", "resourceId", "1cert7",
                "parameters", Map.of("description", "new private description")));
        var plan = f.service.preview(request); f.id = (String) plan.get("id"); assertEquals("pending", plan.get("confirmationState")); return f;
    }

    private static void denied(String code, Runnable action) { assertEquals(code, assertThrows(ClientVisibleException.class, action::run).getCode()); }
}
