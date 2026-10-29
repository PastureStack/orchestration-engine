package io.cattle.platform.iaas.api.auth.ai;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ClientToolAuditEvent;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiToolCallEventServiceTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void readonlyActorGetsDurableStartAndResultAcksForEveryCommand() {
        Fixture f = new Fixture();
        for (String command : ClientToolAuditEvent.COMMANDS) {
            Map<String, Object> begin = f.service.report(f.request(command, "start", "pending", "NONE", null));
            assertEquals(true, begin.get("accepted")); assertEquals("client_reported", begin.get("evidenceSource"));
            assertTrue(ClientToolAuditEvent.validId((String) begin.get("requestId")));
            f.service.report(f.request(command, "result", "rejected", "INVALID_INPUT", (String) begin.get("requestId")));
        }
        assertEquals(ClientToolAuditEvent.COMMANDS.size() * 2, f.events.size());
        assertEquals("readonly", f.policy.getRoles().iterator().next());
        verify(f.objects, never()).persist(any());
    }

    @Test public void rejectsPayloadsReferencesSpoofedActorAndUnboundedStrings() {
        Fixture f = new Fixture();
        for (String field : List.of("args", "serviceRef", "password", "actorId", "accountId", "requestId")) {
            Map<String, Object> body = new HashMap<>(Map.of("command", "find_services", "phase", "start", "status", "pending", "reason", "NONE"));
            body.put(field, "private-do-not-store");
            ApiRequest request = new ApiRequest(null, null); request.setRequestObject(body);
            assertEquals("AiToolEventInvalid", rejected(() -> f.service.report(request)));
        }
        for (ApiRequest invalid : List.of(
                f.request("unexpected-command", "start", "pending", "NONE", null),
                f.request("get_service", "unknown", "pending", "NONE", null),
                f.request("get_service", "result", "succeeded", "password-value", id()),
                f.request("get_service", "result", "x".repeat(20000), "NONE", id()),
                f.request("get_service", "result", "succeeded", "NONE", "foreign-ref"),
                f.request("get_service", "start", "succeeded", "NONE", null))) {
            assertEquals("AiToolEventInvalid", rejected(() -> f.service.report(invalid)));
        }
        assertTrue(f.events.isEmpty());
    }

    @Test public void unavailableOutboxNeverAcknowledgesAndResourceHasNoExecution() {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("unavailable")).when(f.sink).recordClientToolEvent(any(), any(), any());
        assertEquals("AiToolAuditUnavailable", rejected(() -> f.service.report(f.request("get_service", "start", "pending", "NONE", null))));
        AiToolCallEventResourceManager manager = new AiToolCallEventResourceManager(); manager.service = f.service;
        assertEquals("AiToolAuditUnavailable", rejected(() -> manager.create("aiToolCallEvent", f.request("get_service", "start", "pending", "NONE", null))));
    }

    @Test public void resultCorrelationIsNeverIdentityOrAuthorization() {
        Fixture f = new Fixture();
        String first = (String) f.service.report(f.request("get_service", "start", "pending", "NONE", null)).get("requestId");
        AccountRecord other = new AccountRecord(); other.setId(43L); other.setState("active");
        when(f.policy.getAuthenticatedAsAccountId()).thenReturn(43L); when(f.objects.loadResource(Account.class, 43L)).thenReturn(other);
        f.service.report(f.request("get_service", "result", "succeeded", "NONE", first));
        assertEquals(43L, f.recordedActors.getLast().longValue());
        assertEquals(first, f.events.getLast().correlationId());
    }

    @Test public void requiresActiveCookieActorAndActiveSelectedProject() {
        Fixture f = new Fixture(); ApiRequest request = f.request("get_service", "start", "pending", "NONE", null);
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 42, "apiKey", 0, null));
        assertEquals(true, f.service.report(request).get("accepted"));
        verify(f.service.keyAuthorization).actor(request);
        f.events.clear();
        f.actor.setState("inactive");
        assertEquals("PermissionDenied", rejected(() -> f.service.report(f.request("get_service", "start", "pending", "NONE", null))));
        f.actor.setState("active"); f.project.setState("inactive");
        assertEquals("PermissionDenied", rejected(() -> f.service.report(f.request("get_service", "start", "pending", "NONE", null))));
        assertTrue(f.events.isEmpty());
    }

    static String id() { return "aitool-11111111-1111-1111-1111-111111111111"; }
    static String rejected(Runnable run) { try { run.run(); fail("must reject"); return null; } catch (ClientVisibleException error) { return error.getCode(); } }
    static class Fixture {
        final AiToolCallEventService service = new AiToolCallEventService();
        final Policy policy = mock(Policy.class); final ObjectManager objects = mock(ObjectManager.class);
        final ApiKeyAuditSink sink = mock(ApiKeyAuditSink.class);
        final AccountRecord actor = new AccountRecord(), project = new AccountRecord();
        final List<ClientToolAuditEvent> events = new ArrayList<>(); final List<Long> recordedActors = new ArrayList<>();
        Fixture() {
            when(policy.getAuthenticatedAsAccountId()).thenReturn(42L); when(policy.getAccountId()).thenReturn(77L); when(policy.getRoles()).thenReturn(java.util.Set.of("readonly"));
            actor.setId(42L); actor.setState("active"); project.setId(77L); project.setKind("project"); project.setState("active");
            when(objects.loadResource(Account.class, 42L)).thenReturn(actor); when(objects.loadResource(Account.class, 77L)).thenReturn(project);
            doAnswer(call -> { recordedActors.add(((Policy) call.getArgument(1)).getAuthenticatedAsAccountId()); events.add(call.getArgument(2)); return null; }).when(sink).recordClientToolEvent(any(), any(), any());
            service.keyAuthorization = mock(AiKeyAuthorization.class); when(service.keyAuthorization.actor(any())).thenReturn(policy);
            service.objects = objects; service.auditSinks = List.of(sink);
            ApiContext.newContext().setPolicy(policy); ApiContext.getContext().setIdFormatter(new IdentityFormatter());
        }
        ApiRequest request(String command, String phase, String status, String reason, String correlation) {
            Map<String, Object> body = new HashMap<>(Map.of("command", command, "phase", phase, "status", status, "reason", reason));
            if (correlation != null) body.put("correlationId", correlation);
            ApiRequest request = new ApiRequest(null, null); request.setRequestObject(body); return request;
        }
    }
}
