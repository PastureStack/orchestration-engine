package io.cattle.platform.iaas.api.auth.ai.audit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiAuditPayload;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

public class AiAuditQueryServiceTest {
    @After public void clean() { ApiContext.remove(); }

    @Test public void interruptedOwnerCancelsAndRecordsErrorWithoutPartialResult() {
        Fixture f = new Fixture(); f.use(input -> { throw new InterruptedException("PRIVATE"); });
        try {
            assertEquals("AuditQueryUnavailable", assertThrows(ClientVisibleException.class, () -> f.service.read(f.request)).getCode());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(f.service.audit).recordAuditQueryOutcome(eq(f.request), eq(f.actor), anyString(), eq("ERROR"), eq("AuditQueryUnavailable"), eq(0));
            assertEquals(2, AiAuditQueryRunner.SLOTS.availablePermits());
        } finally { Thread.interrupted(); }
    }

    @Test public void queryCapacityFailsBeforeExecutorWithoutSpawningAnotherOwner() throws Exception {
        Fixture f = new Fixture(); var executed = new java.util.concurrent.atomic.AtomicBoolean();
        f.use(input -> { executed.set(true); return f.frame(200, f.result()); });
        AiAuditQueryRunner.SLOTS.acquire(2);
        try {
            assertEquals("AuditQueryUnavailable", assertThrows(ClientVisibleException.class, () -> f.service.read(f.request)).getCode());
            assertFalse(executed.get());
        } finally { AiAuditQueryRunner.SLOTS.release(2); }
    }

    @Test public void completeOwnerResultIsCanonicalAndPrivateCredentialsUseOnlyStdin() throws Exception {
        Fixture f = new Fixture(); AtomicReference<Map<String, Object>> stdin = new AtomicReference<>();
        f.use(input -> { stdin.set(f.json.readValue(input)); return f.frame(200, f.result()); });
        ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(8, 1, "apiKey", 3, null));
        when(f.http.getHeader("Authorization")).thenReturn("Basic PRIVATE-CREDENTIAL");
        when(f.http.getHeader("X-PastureStack-Key-Expected-Revision")).thenReturn("3");
        Map<String, Object> output = f.service.read(f.request);
        assertEquals("Basic PRIVATE-CREDENTIAL", stdin.get().get("authorization"));
        assertEquals("1c8", stdin.get().get("credentialId")); assertEquals(3, ((Number) stdin.get().get("revision")).intValue());
        assertEquals("1a1", stdin.get().get("actorId")); assertFalse(stdin.get().containsKey("cookie"));
        assertFalse(output.toString().contains("PRIVATE")); assertTrue(ApiAuditPayload.omitted(f.request));
        verify(f.service.keys, times(2)).actor(f.request);
        verify(f.service.audit).recordAuditQueryOutcome(eq(f.request), eq(f.actor), startsWith("aiaudit-"), eq("ALLOW"), eq("AuditQueryCompleted"), eq(1));
    }

    @Test public void deniedErrorAndRevokedCompletionNeverReturnPartialSuccess() throws Exception {
        for (int status : List.of(401, 403, 422, 502)) {
            Fixture f = new Fixture(); f.use(input -> f.frame(status, Map.of("message", "PRIVATE-UPSTREAM-ERROR")));
            ClientVisibleException failure = assertThrows(ClientVisibleException.class, () -> f.service.read(f.request));
            assertFalse(failure.getMessage().contains("PRIVATE"));
            verify(f.service.audit).recordAuditQueryOutcome(eq(f.request), eq(f.actor), anyString(),
                    eq(status == 401 || status == 403 ? "DENY" : "ERROR"), eq(failure.getCode()), eq(0));
        }
        Fixture f = new Fixture();
        when(f.service.keys.actor(f.request)).thenReturn(f.actor).thenThrow(new ClientVisibleException(403, "ApiKeyRevoked"));
        assertEquals("ApiKeyRevoked", assertThrows(ClientVisibleException.class, () -> f.service.read(f.request)).getCode());
        verify(f.service.audit).recordAuditQueryOutcome(eq(f.request), eq(f.actor), anyString(), eq("DENY"), eq("ApiKeyRevoked"), eq(0));
    }

    @Test public void malformedOrPrivateOwnerOutputAndUnavailableAuditFailClosed() throws Exception {
        Fixture f = new Fixture(); Map<String, Object> body = f.result();
        body.put("requestObject", "PRIVATE"); f.use(input -> f.frame(200, body));
        assertEquals("AuditQueryUnavailable", assertThrows(ClientVisibleException.class, () -> f.service.read(f.request)).getCode());
        verify(f.service.audit).recordAuditQueryOutcome(eq(f.request), eq(f.actor), anyString(), eq("ERROR"), eq("AuditQueryUnavailable"), eq(0));
        Fixture unavailable = new Fixture();
        doThrow(new IllegalStateException("PRIVATE-SPOOL")).when(unavailable.service.audit)
                .recordAuditQueryOutcome(any(), any(), anyString(), anyString(), anyString(), anyInt());
        assertEquals("AuditQueryAuditUnavailable", assertThrows(ClientVisibleException.class, () -> unavailable.service.read(unavailable.request)).getCode());
    }

    @Test public void callerCannotSupplyAuthorityUrlMethodOrBypassRevision() {
        for (String field : List.of("actorId", "authorization", "sourcePage", "url", "method", "credentialId")) {
            Fixture f = new Fixture(); f.request.setRequestObject(Map.of(field, "forged"));
            assertEquals("AuditQueryInputInvalid", assertThrows(ClientVisibleException.class, () -> f.service.read(f.request)).getCode());
        }
        Fixture f = new Fixture(); ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(8, 1, "apiKey", 3, null));
        when(f.http.getHeader("Authorization")).thenReturn("Basic caller");
        for (String revision : new String[] {null, "4"}) {
            when(f.http.getHeader("X-PastureStack-Key-Expected-Revision")).thenReturn(revision);
            assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class, () -> f.service.read(f.request)).getCode());
        }
    }

    @Test public void runnerRejectsPartialTrailingOversizedAndInterruptedFramesWithoutLeakingOrLosingCapacity() throws Exception {
        for (byte[] output : List.of("{\"status\":200".getBytes(), "{} {}".getBytes(), new byte[AiAuditQueryRunner.MAX_OUTPUT + 1])) {
            Fixture f = new Fixture(); AiAuditQueryRunner runner = new AiAuditQueryRunner(input -> output); runner.json = f.json;
            assertThrows(Exception.class, () -> runner.run(Map.of("query", Map.of())));
            assertEquals(2, AiAuditQueryRunner.SLOTS.availablePermits());
        }
        Fixture f = new Fixture(); f.use(input -> { throw new IOException("PRIVATE-EXECUTOR"); });
        assertEquals("AuditQueryUnavailable", assertThrows(ClientVisibleException.class, () -> f.service.read(f.request)).getCode());
        assertEquals(2, AiAuditQueryRunner.SLOTS.availablePermits());
    }

    static final class Fixture {
        final AiAuditQueryService service = new AiAuditQueryService();
        final JacksonJsonMapper json = new JacksonJsonMapper();
        final Policy actor = mock(Policy.class);
        final ApiRequest request = new ApiRequest(null, null);
        final HttpServletRequest http = mock(HttpServletRequest.class);
        Fixture() {
            ApiContext.newContext().setPolicy(actor);
            SchemaFactory schemas = mock(SchemaFactory.class); when(schemas.getBaseType(anyString())).thenAnswer(call -> call.getArgument(0));
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("apiKey", "c")); ApiContext.getContext().setIdFormatter(formatter);
            when(actor.getAuthenticatedAsAccountId()).thenReturn(1L); when(actor.getAccountId()).thenReturn(5L);
            service.keys = mock(AiKeyAuthorization.class); when(service.keys.actor(any())).thenReturn(actor);
            service.audit = mock(ApiKeyAuditSink.class);
            ApiServletContext servlet = mock(ApiServletContext.class); when(servlet.getRequest()).thenReturn(http);
            request.setRequestServletContext(servlet); request.setRequestObject(Map.of());
            when(http.getHeader("Cookie")).thenReturn("R_SESS=PRIVATE-COOKIE");
            use(input -> frame(200, result()));
        }
        void use(AiAuditQueryRunner.Executor executor) { service.runner = new AiAuditQueryRunner(executor); service.runner.json = json; }
        byte[] frame(int status, Map<String, Object> body) throws IOException { return json.writeValueAsBytes(Map.of("status", status, "body", body)); }
        Map<String, Object> result() {
            Map<String, Object> result = new LinkedHashMap<>(Map.of("resourceType", "auditLog", "total", 1,
                    "data", List.of(Map.of("id", "1al1", "type", "auditLog", "payloadsIncluded", false,
                            "requestPayloadPresence", "not_projected", "responsePayloadPresence", "not_projected")),
                    "range", Map.of("from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z"),
                    "payloadPolicy", "HUMAN_ONLY_NATIVE_AUDIT_UI"));
            result.put("nextOffset", null); return result;
        }
    }
}
