package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiResourceChangePlanServiceTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void previewIsAReadAndPublicOutputNeverContainsPrivateState() {
        Fixture f = new Fixture(); Map<String, Object> result = f.preview(false);
        assertEquals("preview", result.get("status")); assertEquals("pending", result.get("confirmationState"));
        assertFalse((boolean) result.get("verifiedResult")); assertEquals(0, f.dispatched);
        for (String field : List.of("snapshot", "parentSnapshot", "body", "parameters", "actorId", "credentialId")) assertFalse(result.containsKey(field));
        assertFalse(f.audits.toString().contains("new private description"));
    }

    @Test public void applyWithoutHumanConfirmationDoesNotClaimOrDispatch() {
        Fixture f = new Fixture(); f.preview(false);
        assertCode(409, "ResourceChangeNeedsConfirmation", () -> f.apply(false));
        assertEquals(0, f.claims); assertEquals(0, f.dispatched);
    }

    @Test public void sameActorAndProjectAreCheckedForEveryReadAndApply() {
        Fixture f = new Fixture(); f.preview(false);
        when(f.policy.getAccountId()).thenReturn(88L);
        assertCode(404, "NotFound", () -> f.service.get(f.id, f.request(null, false)));
        assertCode(404, "NotFound", () -> f.apply(false));
        when(f.policy.getAccountId()).thenReturn(77L); when(f.policy.getAuthenticatedAsAccountId()).thenReturn(99L);
        assertCode(404, "NotFound", () -> f.confirm()); assertEquals(0, f.dispatched);
    }

    @Test public void keyCannotConfirmCancelOrReadAnotherKeysReceipt() {
        Fixture f = new Fixture(); f.preview(true);
        assertCode(403, "AiKeyConfirmationForbidden", () -> f.service.action(f.id, f.request("confirm", true)));
        assertCode(403, "AiKeyConfirmationForbidden", () -> f.service.action(f.id, f.request("cancel", true)));
        ApiRequest other = f.request(null, true);
        ApiKeyCredentialContext.attach(other, new ApiKeyCredentialContext(8, 42, "apiKeyRestricted", 3, null));
        assertCode(404, "NotFound", () -> f.service.get(f.id, other)); assertEquals(0, f.dispatched);
    }

    @Test public void humanInboxIsProjectScopedAndCannotBeReadWithAKey() {
        Fixture f = new Fixture(); f.preview(false);
        assertEquals(1, f.service.pending(f.request(null, false)).size());
        assertCode(403, "AiKeyConfirmationForbidden", () -> f.service.pending(f.request(null, true)));
        when(f.policy.getAccountId()).thenReturn(88L);
        assertTrue(f.service.pending(f.request(null, false)).isEmpty());
    }

    @Test public void approvedUndispatchedPlansStayCancellableButAcceptedOperationsDoNot() {
        Fixture f = new Fixture(); f.preview(false); f.confirm();
        var inbox = f.service.pending(f.request(null, false));
        assertEquals(1, inbox.size()); assertEquals("approved", inbox.getFirst().get("confirmationState"));
        assertEquals("cancelled", f.service.action(f.id, f.request("cancel", false)).get("status"));
        assertTrue(f.service.pending(f.request(null, false)).isEmpty()); assertEquals(0, f.dispatched);
        Fixture accepted = new Fixture(); accepted.preview(false); accepted.confirm(); accepted.apply(false);
        assertTrue(accepted.service.pending(accepted.request(null, false)).isEmpty());
        assertCode(409, "ResourceChangeCancelled", () -> accepted.service.action(accepted.id, accepted.request("cancel", false)));
        assertEquals(1, accepted.dispatched);
    }

    @Test public void cookieConfirmationNeverLendsItsAuthorityToABoundKey() {
        Fixture f = new Fixture(); f.preview(true); f.confirm();
        assertCode(403, "ResourceChangeBoundKeyRequired", () -> f.apply(false));
        assertEquals(0, f.dispatched); f.writeAllowed = false;
        assertCode(403, "PermissionDenied", () -> f.apply(true)); assertEquals(0, f.claims);
    }

    @Test public void compareAndSwapLocksBeforeOwnerLookupAndRejectsConcurrentUiEdits() {
        Fixture f = new Fixture(); f.preview(false); clearInvocations(f.domain);
        f.target.setDescription("concurrent UI edit");
        assertCode(409, "ResourceChangePlanStale", () -> f.confirm());
        var order = inOrder(f.domain); order.verify(f.domain).lock("service", 7L); order.verify(f.domain).actor(any());
        assertEquals("concurrent UI edit", f.target.getDescription()); assertEquals(0, f.dispatched);
    }
    @Test public void childAuthorizationDriftRejectsConfirmationAndApplyWithoutClaimOrPartialDispatch() {
        Fixture f = new Fixture(); ApiRequest preview = f.request(null, true); preview.setId(null);
        preview.setRequestObject(Map.of("resourceType", "stack", "operation", "start", "resourceId", "1st7", "parameters", Map.of()));
        f.id = (String) f.service.preview(preview).get("id");
        when(f.domain.prepare(any(), any(), anyString(), anyMap(), anyMap())).thenThrow(new ClientVisibleException(403, "KeyPolicyDenied"));
        assertCode(403, "KeyPolicyDenied", f::confirm);
        assertEquals("pending", f.documents.get(f.id).get("confirmationState"));
        doAnswer(call -> f.prepare(call.getArgument(3))).when(f.domain).prepare(any(), any(), anyString(), anyMap(), anyMap());
        f.confirm();
        when(f.domain.prepare(any(), any(), anyString(), anyMap(), anyMap())).thenThrow(new ClientVisibleException(403, "OwnerPermissionDenied"));
        assertCode(403, "OwnerPermissionDenied", () -> f.apply(true));
        assertEquals(0, f.claims); assertEquals(0, f.dispatched);
    }

    @Test public void expiredAndRevokedCapabilitiesFailBeforeClaim() {
        Fixture f = new Fixture(); f.preview(false);
        f.service.clock = Clock.fixed(Instant.parse("2026-10-29T00:06:00Z"), ZoneOffset.UTC);
        assertCode(409, "ResourceChangePlanExpired", () -> f.confirm());
        f.service.clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC); f.writeAllowed = false;
        assertCode(403, "PermissionDenied", () -> f.confirm()); assertEquals(0, f.claims); assertEquals(0, f.dispatched);
    }

    @Test public void acceptedOperationIsDurableAndRepeatApplyNeverDispatchesAgain() {
        Fixture f = new Fixture(); f.preview(true); f.confirm();
        Map<String, Object> accepted = f.apply(true);
        assertEquals("accepted", accepted.get("status")); assertEquals(f.id, accepted.get("operationId"));
        Map<String, Object> retried = f.apply(true);
        assertEquals(f.id, retried.get("operationId")); assertEquals(1, f.claims); assertEquals(1, f.dispatched);
        f.complete = true;
        Map<String, Object> done = f.service.get(f.id, f.request(null, true));
        assertEquals("succeeded", done.get("status")); assertEquals(true, done.get("verifiedResult"));
        assertFalse(f.documents.get(f.id).containsKey("snapshot"));
        f.apply(true); assertEquals(1, f.dispatched);
    }

    @Test public void lostDispatchResponseLeavesOneUnknownOperationWithoutReplay() {
        Fixture f = new Fixture(); f.preview(false); f.confirm(); f.failDispatch = true;
        Map<String, Object> uncertain = f.apply(false);
        assertEquals("unknown", uncertain.get("status")); assertEquals(f.id, uncertain.get("operationId"));
        assertEquals("unknown", f.apply(false).get("status")); assertEquals(1, f.dispatched); assertEquals(1, f.claims);
        f.complete = true;
        assertEquals("unknown", f.service.get(f.id, f.request(null, false)).get("status"));
        assertFalse(uncertain.toString().contains("secret dispatch detail"));
    }

    @Test public void auditFailureCannotPersistApproveOrDispatch() {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("private audit failure")).when(f.audit).recordResourceChangeDecision(any(), any(), any(), any(), any(),
                any(), anyBoolean(), any());
        assertCode(503, "ResourceChangeAuditUnavailable", () -> f.preview(false));
        assertTrue(f.documents.isEmpty()); assertEquals(0, f.dispatched);
    }

    @Test public void creatingAResourceRecordsItsReturnedIdentityBeforeObservation() {
        Fixture f = new Fixture();
        Map<String, Object> body = new LinkedHashMap<>(Map.of("resourceType", "container", "operation", "create",
                "parameters", Map.of("name", "probe", "image", "busybox", "command", List.of("sleep", "300"))));
        ApiRequest preview = f.request(null, false); preview.setRequestObject(body);
        f.id = (String) f.service.preview(preview).get("id"); f.confirm();
        f.create = true; Map<String, Object> accepted = f.apply(false);
        assertEquals("1i91", accepted.get("resourceId"));
        assertEquals(91L, ((Number) f.documents.get(f.id).get("internalResourceId")).longValue());
        f.complete = true; assertEquals(true, f.service.get(f.id, f.request(null, false)).get("verifiedResult"));
    }

    @Test public void featureDisabledIsRejectedBeforeAnyPersistenceOrDomainRead() {
        Object previous = io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().getProperty("api.ai.resource.change.enabled");
        try {
            io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.resource.change.enabled", false);
            var service = new AiResourceChangePlanService(); service.auditSinks = List.of(mock(ApiKeyAuditSink.class));
            ApiContext.newContext().setPolicy(mock(Policy.class));
            service.domain = mock(AiResourceChangeDomain.class); service.store = mock(AiResourceChangePlanStore.class);
            assertCode(503, "ResourceChangeFeatureDisabled", () -> service.preview(new ApiRequest(null, null)));
            verifyNoInteractions(service.domain, service.store);
        } finally {
            if (previous == null) io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().clearProperty("api.ai.resource.change.enabled");
            else io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.resource.change.enabled", previous);
        }
    }

    private static void assertCode(int status, String code, Runnable action) {
        var error = assertThrows(ClientVisibleException.class, action::run); assertEquals(status, error.getStatus()); assertEquals(code, error.getCode());
    }

    static class Fixture {
        final Policy policy = mock(Policy.class);
        final AiResourceChangeDomain domain = mock(AiResourceChangeDomain.class);
        final ApiKeyAuditSink audit = mock(ApiKeyAuditSink.class);
        final ServiceRecord target = new ServiceRecord();
        final Map<String, Map<String, Object>> documents = new LinkedHashMap<>();
        final List<String> audits = new ArrayList<>(); final JacksonJsonMapper json = new JacksonJsonMapper();
        final AiResourceChangePlanService service = new AiResourceChangePlanService() { @Override protected void requireEnabled() { } };
        String id; int dispatched, claims; boolean complete, failDispatch, writeAllowed = true, create;
        Fixture() {
            when(policy.getAuthenticatedAsAccountId()).thenReturn(42L); when(policy.getAccountId()).thenReturn(77L);
            target.setId(7L); target.setAccountId(77L); target.setKind("service"); target.setName("probe"); target.setDescription("old");
                    target.setState("active");
            when(domain.actor(any())).thenReturn(policy); when(domain.internalId(anyString(), anyString())).thenReturn(7L);
            when(domain.lock(anyString(), anyLong())).thenReturn(target); when(domain.visible(any(), any(), anyString())).thenReturn(target);
            when(domain.receiptTarget(any(), any(), anyMap())).thenReturn(target);
            when(domain.snapshot(any())).thenAnswer(call -> {
                if (call.getArgument(0) == null) return Map.of();
                Map<String, Object> snapshot = new LinkedHashMap<>(); snapshot.put("name", target.getName()); snapshot.put("description",
                        target.getDescription());
                snapshot.put("state", target.getState()); return snapshot;
            });
            when(domain.prepare(any(), any(), nullable(String.class), anyMap())).thenAnswer(call -> prepare(call.getArgument(3)));
            when(domain.prepare(any(), any(), nullable(String.class), anyMap(), anyMap())).thenAnswer(call -> prepare(call.getArgument(3)));
            when(domain.targetPath(any(), any(), any(), anyMap())).thenReturn("Default / probe");
            when(domain.changes(any(), any(), anyMap())).thenReturn(List.of(Map.of("field", "description", "before", "old", "after",
                    "new private description")));
            when(domain.publicId(anyString(), any())).thenReturn("1i91");
            when(domain.complete(any(), any(), anyMap(), anyMap())).thenAnswer(call -> complete);
            when(domain.dispatch(any(), any(), nullable(String.class), anyMap(), anyString())).thenAnswer(call -> {
                dispatched++; if (failDispatch) throw new IllegalStateException("secret dispatch detail");
                if (create) { InstanceRecord result = new InstanceRecord(); result.setId(91L); result.setKind("container"); return result; }
                target.setDescription("new private description"); return target;
            });
            service.domain = domain; service.auditSinks = List.of(audit); service.clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
            service.locks = mock(LockManager.class); when(service.locks.lock(any(), any())).thenAnswer(call -> ((LockCallback<?>)
                    call.getArgument(1)).doWithLock());
            service.store = new AiResourceChangePlanStore() {
                @Override public void prune(long now) { }
                @Override public int pendingCount(long actor) { return documents.size(); }
                @Override public void insert(Map<String, Object> plan) { documents.put((String) plan.get("id"), copy(plan)); }
                @Override public Map<String, Object> load(String id) { return documents.containsKey(id) ? copy(documents.get(id)) : null; }
                @Override public Map<String, Object> loadForUpdate(String id) { return load(id); }
                @Override public List<Map<String, Object>> pending(long actor, long now) { return
                        documents.values().stream().map(Fixture.this::copy).toList(); }
                @Override public void save(Map<String, Object> plan) { documents.put((String) plan.get("id"), copy(plan)); }
                @Override public boolean claim(Map<String, Object> plan) {
                    if (!"preview".equals(documents.get(plan.get("id")).get("status"))) return false;
                    claims++; save(plan); return true;
                }
            };
            doAnswer(call -> { audits.add(call.getArgument(2) + ":" + call.getArgument(4) + ":" + call.getArgument(6) + ":" + call.getArgument(7));
                    return null; })
                    .when(audit).recordResourceChangeDecision(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
            ApiContext.newContext().setPolicy(policy);
        }
        Map<String, Object> prepare(Map<String, Object> parameters) {
            if (!writeAllowed) throw new ClientVisibleException(403, "PermissionDenied"); return new LinkedHashMap<>(parameters);
        }
        Map<String, Object> copy(Map<String, Object> input) {
            try { return json.readValue(json.writeValueAsString(input)); } catch (Exception error) { throw new AssertionError(error); }
        }
        ApiRequest request(String action, boolean key) {
            ApiRequest request = new ApiRequest(null, null); request.setType("aiResourceChangePlan"); request.setMethod("POST");
            request.setId(id); request.setAction(action); request.setRequestObject(new LinkedHashMap<>());
            request.setAttribute(AccountConstants.AUTH_TYPE, "TokenAuth");
            if (key) ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, null)); return request;
        }
        Map<String, Object> preview(boolean key) {
            ApiRequest request = request(null, key); request.setId(null);
            request.setRequestObject(new LinkedHashMap<>(Map.of("resourceType", "service", "operation", "update_metadata", "resourceId", "1s7",
                    "parameters", Map.of("description", "new private description"))));
            Map<String, Object> result = service.preview(request); id = (String) result.get("id"); return result;
        }
        void confirm() { service.action(id, request("confirm", false)); }
        Map<String, Object> apply(boolean key) { return service.action(id, request("apply", key)); }
    }
}
