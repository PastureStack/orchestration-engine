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
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.handler.BodyParserRequestHandler;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

public class AiAuditQueryServiceTest {
    @After public void clean() { ApiContext.remove(); }

    @Test public void realParserAndSchemaCannotEraseOriginalUnknownFieldsBeforeCanonicalError() throws Exception {
        for (String field : List.of("url", "method", "actorId", "authorization", "credentialId", "sourcePage", "data", "total")) {
            Fixture f = new Fixture(); var ran = new java.util.concurrent.atomic.AtomicBoolean();
            f.use(input -> { ran.set(true); return f.frame(200, f.result()); });
            ApiRequest request = f.parsed("{\"lastMinutes\":10,\"limit\":1,\"" + field + "\":\"PRIVATE-IGNORED-INPUT\"}", Map.of());
            assertFalse(((Map<?, ?>) request.getRequestObject()).containsKey(field)); // Real generic sanitization still operates.
            assertEquals(Boolean.TRUE, request.getAttribute(AiAuditQueryService.ORIGINAL_FIELDS_REJECTED));
            new AiAuditQueryInputHandler().handle(request); // Sanitized re-entry cannot erase the rejection.
            var manager = new AiAuditQueryResourceManager(); manager.service = f.service;
            var rejected = assertThrows(ClientVisibleException.class, () -> manager.createInternal("aiAuditQuery", request));
            assertEquals(422, rejected.getStatus()); assertEquals("AuditQueryInputInvalid", rejected.getCode());
            assertFalse(ran.get()); assertTrue(ApiAuditPayload.omitted(request));
            verify(f.service.audit).recordAuditQueryOutcome(eq(request), eq(f.actor), startsWith("aiaudit-"),
                    eq("ERROR"), eq("AuditQueryInputInvalid"), eq(0));
        }
    }

    @Test public void originalQueryParametersAreClosedButValidTypedQueriesKeepTheirCanonicalSuccess() throws Exception {
        Fixture f = new Fixture(); ApiRequest invalid = f.parsed("{\"lastMinutes\":10,\"limit\":1}", Map.of("url", new String[] {"PRIVATE"}));
        assertEquals("AuditQueryInputInvalid", assertThrows(ClientVisibleException.class, () -> f.service.read(invalid)).getCode());
        Fixture valid = new Fixture(); ApiRequest request = valid.parsed("{\"type\":\"aiAuditQuery\",\"lastMinutes\":10,\"limit\":1}", Map.of());
        assertEquals(Boolean.FALSE, request.getAttribute(AiAuditQueryService.ORIGINAL_FIELDS_REJECTED));
        assertEquals("auditLog", valid.service.read(request).get("resourceType"));
        verify(valid.service.audit).recordAuditQueryOutcome(eq(request), eq(valid.actor), anyString(), eq("ALLOW"), eq("AuditQueryCompleted"), eq(1));
    }

    @Test public void originalFieldCaptureIsOnlyTheAuditCollectionOwnerAndPrecedesTheRealSchemaValidator() throws Exception {
        Fixture f = new Fixture(); var handler = new AiAuditQueryInputHandler();
        f.request.setMethod("POST"); f.request.setType("service"); f.request.setRequestObject(Map.of("url", "PRIVATE"));
        handler.handle(f.request);
        assertNull(f.request.getAttribute(AiAuditQueryService.ORIGINAL_FIELDS_REJECTED));
        assertEquals(Map.of("url", "PRIVATE"), f.request.getRequestObject());
        Path root = sourceRoot();
        String order = Files.readString(root.resolve("code/packaging/app-config/src/main/resources/META-INF/cattle/iaas-api/defaults.properties"));
        assertTrue(order.indexOf("BodyParserRequestHandler,") < order.indexOf("AiAuditQueryInputHandler,"));
        assertTrue(order.indexOf("AiAuditQueryInputHandler,") < order.indexOf("ValidationHandler,"));
        String beans = Files.readString(root.resolve("code/packaging/app-config/src/main/java/io/cattle/platform/app/IaasApiConfig.java"));
        assertTrue(beans.contains("return EMUtils.add(em, ApiRequestHandler.class, new AiAuditQueryInputHandler());"));
    }

    private static Path sourceRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("resources/content/schema/base/aiAuditQuery.json"))) path = path.getParent();
        assertNotNull("native source schema required", path); return path;
    }

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
        ApiRequest parsed(String body, Map<String, Object> params) throws Exception {
            var mapper = new io.github.ibuildthecloud.gdapi.json.JacksonMapper(); mapper.init();
            SchemaImpl schema = mapper.readValue(Files.readAllBytes(sourceRoot().resolve("resources/content/schema/base/aiAuditQuery.json")), SchemaImpl.class);
            schema.setId("aiAuditQuery");
            SchemaFactory schemas = mock(SchemaFactory.class); when(schemas.getSchema("aiAuditQuery")).thenReturn(schema);
            ApiRequest parsed = new ApiRequest(null, schemas) {
                @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            };
            parsed.setMethod("POST"); parsed.setType("aiAuditQuery"); parsed.setRequestParams(params);
            parsed.setRequestServletContext(request.getServletContext());
            var parser = new BodyParserRequestHandler(); parser.setJsonMarshaller(mapper); parser.init(); parser.handle(parsed);
            new AiAuditQueryInputHandler().handle(parsed);
            var validation = new ValidationHandler(); validation.init(); validation.generate(parsed);
            return parsed;
        }
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
