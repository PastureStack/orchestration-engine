package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.ApiKeyDelegatedAuditEvent;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.cattle.platform.engine.process.ExitReason;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import java.io.EOFException;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ApiKeyAuditTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void readDecisionAndActualResponseContainOnlyMetadata() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        ApiRequest request = request("GET", "ALLOW", 200);
        request.setRequestObject(Map.of("Authorization", "Bearer-secret", "password", "payload-secret"));
        request.setResponseObject(Map.of("secretValue", "key-secret", "Cookie", "session-secret"));
        service.recordDecision(request, policy());
        service.recordDecision(request, policy());
        service.logRequest(request, policy());
        service.logRequest(request, policy());
        assertEquals(2, delivered.size());
        assertEquals("PENDING", delivered.get(0).get("outcome"));
        assertEquals("decision", delivered.get(0).get("phase"));
        assertEquals("SUCCEEDED", delivered.get(1).get("outcome"));
        assertEquals(200, delivered.get(1).get("httpStatus"));
        assertEquals(delivered.get(0).get("requestId"), delivered.get(1).get("requestId"));
        assertFalse(delivered.toString().contains("secret"));
        assertFalse(delivered.get(1).containsKey("requestObject"));
        assertFalse(delivered.get(1).containsKey("responseObject"));
    }

    @Test
    public void cookieKeyCreationIsLinkedToServerIssuedKeyAndNeverItsSecret() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        ApiRequest request = new ApiRequest(null, null);
        request.setMethod("POST"); request.setType("apiKey"); request.setResponseCode(201);
        request.setRequestObject(Map.of("id", "forged-key", "secretValue", "private-secret", "securityConfirmation", "private-ticket"));
        request.setResponseObject(new ResourceImpl("1c42", "apiKey", Map.of("secretValue", "private-response-secret", "apiKeyPolicyRevision", 1L)));
        service.logRequest(request, policy()); service.logRequest(request, policy());
        assertEquals(1, delivered.size());
        Map<String, Object> data = delivered.get(0);
        assertEquals("1c42", data.get("keyId")); assertEquals("1c42", data.get("targetId"));
        assertEquals("2", data.get("actor")); assertEquals("create", data.get("operation"));
        assertEquals("response", data.get("phase")); assertEquals("SUCCEEDED", data.get("outcome"));
        assertEquals(1L, ((Number) data.get("policyRevision")).longValue());
        assertEquals(201, data.get("httpStatus")); UUID.fromString((String) data.get("requestId"));
        assertFalse(delivered.toString().contains("private")); assertFalse(delivered.toString().contains("forged"));
        assertFalse(AuditServiceImpl.isApiKeyRequest(request));
    }

    @Test
    public void cookieKeyUpdatesAndRevocationRecordActualFinalStatusExactlyOnce() throws Exception {
        for (int status : new int[]{200, 202, 400, 403, 500}) {
            List<Map<String, Object>> delivered = new ArrayList<>(); AuditServiceImpl service = service(delivered);
            ApiRequest request = new ApiRequest(null, null);
            request.setMethod("PUT"); request.setType("apiKeyRestricted"); request.setId("1c42"); request.setResponseCode(status);
            request.setRequestObject(Map.of("id", "forged-key", "securityConfirmation", "private-ticket", "expiresAt", "private-input"));
            ApiContext.newContext().setPolicy(policy());
            try { service.complete(request, null); service.complete(request, null); } finally { ApiContext.remove(); }
            assertEquals(1, delivered.size());
            assertEquals("1c42", delivered.get(0).get("keyId")); assertEquals("update", delivered.get(0).get("operation"));
            assertEquals(status, delivered.get(0).get("httpStatus"));
            assertEquals(status == 403 ? "DENY" : "ALLOW", delivered.get(0).get("decision"));
            assertEquals(status == 403 ? "DENIED" : status == 202 ? "ACCEPTED" : status == 200 ? "SUCCEEDED" : "FAILED", delivered.get(0).get("outcome"));
            assertFalse(delivered.toString().contains("private")); assertFalse(delivered.toString().contains("forged"));
        }
        List<Map<String, Object>> delivered = new ArrayList<>(); AuditServiceImpl service = service(delivered);
        ApiRequest request = new ApiRequest(null, null);
        request.setMethod("POST"); request.setType("apiKey"); request.setId("1c42"); request.setAction("deactivate"); request.setResponseCode(202);
        service.logRequest(request, policy());
        assertEquals("deactivate", delivered.get(0).get("operation"));
        assertEquals("ACCEPTED", delivered.get(0).get("outcome"));
    }

    @Test
    public void failedCookieCreationCannotLinkUntrustedPayloadAndGovernanceReadsRemainUnchanged() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>(); AuditServiceImpl service = service(delivered);
        ApiRequest create = new ApiRequest(null, null);
        create.setType("apiKey"); create.setMethod("POST"); create.setResponseCode(400);
        create.setRequestObject(Map.of("id", "forged-key", "accountId", "forged-owner"));
        service.logRequest(create, policy());
        assertEquals(1, delivered.size()); assertFalse(delivered.get(0).containsKey("keyId"));
        assertEquals("FAILED", delivered.get(0).get("outcome")); assertFalse(delivered.toString().contains("forged"));
        ApiRequest read = new ApiRequest(null, null); read.setType("apiKey"); read.setMethod("GET"); read.setId("1c42");
        service.complete(read, null); service.logRequest(read, policy());
        assertEquals(1, delivered.size()); assertFalse(AuditServiceImpl.isApiKeyGovernanceRequest(read));
    }

    @Test
    public void otherOwnerGovernanceUsesDurableTargetAccountAndKeepsTheActorSeparate() throws Exception {
        for (String method : new String[]{"POST", "PUT"}) {
            List<Map<String, Object>> delivered = new ArrayList<>();
            AuditServiceImpl service = service(delivered);
            CredentialRecord key = new CredentialRecord();
            key.setId(42L); key.setAccountId(7L); key.setKind(CredentialConstants.KIND_API_KEY);
            service.objectManager = (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                    new Class<?>[]{ObjectManager.class}, (proxy, called, args) -> {
                        assertEquals("loadResource", called.getName());
                        assertEquals(Credential.class, args[0]); assertEquals(42L, args[1]);
                        return key;
                    });
            List<Long> accounts = new ArrayList<>();
            service.auditLogDao = accountCapturingDao(delivered, accounts);
            ApiRequest request = new ApiRequest(null, null);
            request.setType("apiKey"); request.setMethod(method); request.setResponseCode(200);
            request.setId("PUT".equals(method) ? "42" : null);
            request.setRequestObject(Map.of("id", "99", "accountId", "99", "secretValue", "private-secret"));
            if ("POST".equals(method)) request.setResponseObject(new ResourceImpl("42", "apiKey", Map.of("accountId", "99")));
            service.logRequest(request, policy()); service.logRequest(request, policy());
            assertEquals(List.of(7L, 2L), accounts);
            assertEquals(1, delivered.size()); assertEquals("2", delivered.get(0).get("actor"));
            assertEquals("42", delivered.get(0).get("keyId")); assertFalse(delivered.toString().contains("private-secret"));
        }
    }

    @Test
    public void deniedGovernanceUsesRealKeyOwnerButNeverAnUnrelatedCredentialOrInputOwner() throws Exception {
        for (String kind : new String[]{CredentialConstants.KIND_API_KEY_RESTRICTED, "password"}) {
            List<Map<String, Object>> delivered = new ArrayList<>();
            AuditServiceImpl service = service(delivered);
            CredentialRecord key = new CredentialRecord(); key.setId(42L); key.setAccountId(7L); key.setKind(kind);
            service.objectManager = (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                    new Class<?>[]{ObjectManager.class}, (proxy, called, args) -> key);
            List<Long> accounts = new ArrayList<>(); service.auditLogDao = accountCapturingDao(delivered, accounts);
            ApiRequest request = new ApiRequest(null, null); request.setType("apiKey"); request.setMethod("POST");
            request.setAction("deactivate"); request.setId("42"); request.setResponseCode(403);
            request.setRequestObject(Map.of("accountId", "99"));
            service.logRequest(request, policy());
            assertEquals(List.of(CredentialConstants.KIND_API_KEY_RESTRICTED.equals(kind) ? 7L : 1L, 2L), accounts);
            assertEquals("DENIED", delivered.get(0).get("outcome"));
        }
    }

    private AuditLogDao accountCapturingDao(List<Map<String, Object>> delivered, List<Long> accounts) {
        return (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(), new Class<?>[]{AuditLogDao.class},
                (proxy, method, args) -> {
                    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) args[2];
                    delivered.add(new HashMap<>(data)); accounts.add((Long) args[4]); accounts.add((Long) args[5]);
                    return null;
                });
    }

    @Test
    public void authorizationDecisionDoesNotImplySuccessAndPreviewNeverExecutes() throws Exception {
        AuditServiceImpl service = service(new ArrayList<>());
        assertEquals("FAILED", service.apiKeyAuditData(request("PUT", "ALLOW", 500), policy(), "response").get("outcome"));
        assertEquals("DENIED", service.apiKeyAuditData(request("GET", "DENY", 403), policy(), "response").get("outcome"));
        assertEquals("ACCEPTED", service.apiKeyAuditData(request("POST", "ALLOW", 202), policy(), "response").get("outcome"));
        ApiRequest streamTicket = request("POST", "ALLOW", 200);
        streamTicket.setAttribute("apiKey.audit.actualOutcome", "ACCEPTED");
        Map<String, Object> ticketAudit = service.apiKeyAuditData(streamTicket, policy(), "response");
        assertEquals("ACCEPTED", ticketAudit.get("outcome"));
        assertEquals(200, ticketAudit.get("httpStatus"));
        ApiRequest preview = request("POST", "ALLOW", 200);
        preview.setAttribute("apiKey.audit.preview", true);
        assertEquals("NOT_EXECUTED", service.apiKeyAuditData(preview, policy(), "response").get("outcome"));
    }

    @Test
    public void createdTargetUsesOnlyServerResourceIdentityAfterExecution() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        ApiRequest request = request("POST", "ALLOW", 201);
        request.setId(null);
        request.setAttribute("apiKey.audit.targetId", null);
        request.setAttribute("apiKey.audit.operation", "create");
        request.setResponseObject(new ResourceImpl("1e42", "stack", Map.of("secretValue", "private-create-secret")));
        service.recordDecision(request, policy());
        service.logRequest(request, policy());
        assertEquals("", delivered.get(0).get("targetId"));
        assertEquals("1e42", delivered.get(1).get("targetId"));
        assertFalse(delivered.toString().contains("private-create-secret"));
    }

    @Test
    public void finallyRecordsCanceledStreamWithActualStatusAndNoPrivateException() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        ApiRequest request = request("GET", "ALLOW", 200);
        ApiContext.newContext().setPolicy(policy());
        try {
            service.recordDecision(request, policy());
            service.complete(request, new EOFException("private JWT payload"));
            service.complete(request, null);
        } finally {
            ApiContext.remove();
        }
        assertEquals(2, delivered.size());
        assertEquals("CANCELED", delivered.get(1).get("outcome"));
        assertEquals(200, delivered.get(1).get("httpStatus"));
        assertFalse(delivered.toString().contains("private"));
    }

    @Test
    public void verifiedEarlyDenialWithoutPolicyPreservesStatusAndCannotBecomeAllow() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        ApiRequest expired = request("GET", "DENY", 401);
        expired.setAttribute("apiKey.audit.reason", "KeyExpired");
        expired.setAttribute("apiKey.audit.verifiedPrincipalAccountId", 2L);
        expired.setAttribute("apiKey.audit.verifiedAccountId", 2L);
        service.complete(expired, null);
        assertEquals("DENIED", delivered.get(0).get("outcome"));
        assertEquals("DENY", delivered.get(0).get("decision"));
        assertEquals(401, delivered.get(0).get("httpStatus"));
        assertEquals("2", delivered.get(0).get("actor"));

        ApiRequest foreignProject = request("GET", "DENY", 403);
        foreignProject.setAttribute("apiKey.audit.verifiedPrincipalAccountId", 2L);
        foreignProject.setAttribute("apiKey.audit.verifiedAccountId", 2L);
        foreignProject.setRequestParams(Map.of("projectId", "foreign"));
        service.complete(foreignProject, null);
        assertEquals(403, delivered.get(1).get("httpStatus"));

        ApiRequest forgedAllow = request("GET", "ALLOW", 200);
        forgedAllow.setAttribute("apiKey.audit.verifiedPrincipalAccountId", 2L);
        forgedAllow.setAttribute("apiKey.audit.verifiedAccountId", 2L);
        try {
            service.complete(forgedAllow, null);
            fail("No final policy means no ALLOW admission");
        } catch (IllegalStateException expected) { }
        assertEquals(2, delivered.size());
    }

    @Test
    public void actualProcessCompletionIsSeparateFromAcceptanceAndChain() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        service.idFormatter = typedFormatter();
        LaunchConfiguration config = new LaunchConfiguration("stack.update", "stack", "12", 1L, 0, Map.of());
        Map<String, Object> metadata = Map.of("keyId", "1a99", "requestId", "request1", "operation", "update",
                "targetType", "stack", "targetId", "12", "policyRevision", 3L, "principalAccountId", 2L, "accountId", 1L);
        service.afterExecution(config, metadata, ExitReason.SCHEDULED);
        assertTrue(delivered.isEmpty());
        service.afterExecution(config, metadata, ExitReason.CHAIN);
        assertEquals("ACCEPTED", delivered.get(0).get("outcome"));
        assertEquals("continuation", delivered.get(0).get("phase"));
        service.afterExecution(config, metadata, ExitReason.DONE);
        assertEquals("SUCCEEDED", delivered.get(1).get("outcome"));
        assertEquals("completion", delivered.get(1).get("phase"));
        service.afterExecution(config, metadata, ExitReason.AUTHORIZATION_DENIED);
        assertEquals("DENIED", delivered.get(2).get("outcome"));
        assertEquals("DENY", delivered.get(2).get("decision"));
        for (Map<String, Object> event : delivered) assertEquals("1st12", event.get("targetId"));
        assertEquals("12", metadata.get("targetId"));
    }

    @Test
    public void backgroundTargetsUseInjectedFormatterWithoutApiContextAndPreserveExternalIds() throws Exception {
        ApiContext.remove();
        assertNull(ApiContext.getContext());
        Map<String, String> targets = Map.of("service", "1s12", "stack", "1st12", "container", "1i12", "host", "1h12");
        for (Map.Entry<String, String> target : targets.entrySet()) {
            for (Object rawId : List.of(12L, target.getValue())) {
                List<Map<String, Object>> delivered = new ArrayList<>();
                AuditServiceImpl service = service(delivered);
                DefaultIdFormatter formatter = typedFormatter();
                service.idFormatter = formatter;
                LaunchConfiguration config = new LaunchConfiguration(target.getKey()+".update", target.getKey(), "12", 1L, 0, Map.of());
                Map<String, Object> metadata = Map.of("keyId", "1c99", "requestId", "request1", "operation", "update",
                        "targetType", target.getKey(), "targetId", rawId, "policyRevision", 3L,
                        "principalAccountId", 2L, "accountId", 1L);
                service.afterExecution(config, metadata, ExitReason.DONE);
                assertEquals(1, delivered.size());
                Map<String, Object> event = delivered.getFirst();
                assertEquals(target.getKey(), event.get("targetType"));
                assertEquals(target.getValue(), event.get("targetId"));
                assertEquals(formatter.formatId(target.getKey(), 12L), event.get("targetId"));
                assertEquals("1c99", event.get("keyId"));assertEquals("request1", event.get("requestId"));
                assertEquals("completion", event.get("phase"));assertEquals("SUCCEEDED", event.get("outcome"));
                assertEquals(rawId, metadata.get("targetId"));assertEquals("12", config.getResourceId());
                assertNull(ApiContext.getContext());
            }
        }
    }

    private static DefaultIdFormatter typedFormatter() {
        DefaultIdFormatter formatter = new DefaultIdFormatter();
        formatter.setSchemaFactory((SchemaFactory) Proxy.newProxyInstance(SchemaFactory.class.getClassLoader(), new Class<?>[]{SchemaFactory.class},
                (proxy, method, arguments) -> method.getName().equals("getBaseType")
                        ? ("container".equals(arguments[0]) ? "instance" : arguments[0]) : null));
        formatter.setTypeMappings(Map.of("stack", "st", "secret", "se"));
        return formatter;
    }

    @Test
    public void anonymousDenialIsDurableBeforeResponseAndNeverInfersKeyOrOwner() throws Exception {
        Path directory = temporary.newFolder().toPath();
        AuditServiceImpl first = new AuditServiceImpl(); first.jsonMapper = mapper();
        first.apiKeyOutbox = new ApiKeyAuditOutbox(directory, first.jsonMapper);
        first.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(), new Class<?>[]{AuditLogDao.class},
                (proxy, method, args) -> { throw new IllegalStateException("offline private database value"); });
        ApiRequest request = new ApiRequest(null, null); request.setMethod("GET");
        request.setRequestObject(Map.of("accessKey", "private-public-key", "secretKey", "private-secret", "token", "private-jwt"));
        request.setAttribute("apiKey.audit.verifiedPrincipalAccountId", 999L);
        first.recordAuthenticationDenied(request, "InvalidApiCredential");
        first.recordAuthenticationDenied(request, "InvalidApiCredential");
        assertEquals(Boolean.TRUE, request.getAttribute("apiKey.audit.admitted")); assertEquals(1, pending(directory));
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl restart = new AuditServiceImpl(); restart.jsonMapper = mapper();
        restart.apiKeyOutbox = new ApiKeyAuditOutbox(directory, restart.jsonMapper);
        restart.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(), new Class<?>[]{AuditLogDao.class},
                (proxy, method, args) -> {
                    assertNull(args[4]); assertNull(args[5]);
                    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) args[2];
                    delivered.add(new HashMap<>(data)); return null;
                });
        request.setResponseCode(401); restart.complete(request, null); restart.complete(request, null);
        assertEquals(2, delivered.size()); assertEquals(0, pending(directory));
        assertEquals(0, delivered.get(0).get("httpStatus")); assertEquals(401, delivered.get(1).get("httpStatus"));
        for (Map<String, Object> data : delivered) {
            assertEquals("anonymous", data.get("actor")); assertEquals("AUTHENTICATION_DENIED", data.get("outcome"));
            assertFalse(data.containsKey("keyId")); assertFalse(data.containsKey("policyRevision"));
        }
        assertEquals(delivered.get(0).get("requestId"), delivered.get(1).get("requestId"));
        assertFalse(delivered.toString().contains("private")); assertFalse(delivered.toString().contains("999"));
    }

    @Test
    public void anonymousFloodCannotCrowdOutAnAdmittedResponseOrSilentlyDropDenials() throws Exception {
        AuditServiceImpl service = new AuditServiceImpl(); service.jsonMapper = mapper();
        Path directory = temporary.newFolder().toPath();
        service.apiKeyOutbox = new ApiKeyAuditOutbox(directory, service.jsonMapper, 2, 2 * 8192);
        service.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(), new Class<?>[]{AuditLogDao.class},
                (proxy, method, args) -> { throw new IllegalStateException(); });
        ApiRequest admitted = new ApiRequest(null, null);
        service.recordAuthenticationDenied(admitted, "InvalidApiCredential");
        ApiRequest later = new ApiRequest(null, null);
        assertThrows(IllegalStateException.class, () -> service.recordAuthenticationDenied(later, "InvalidApiCredential"));
        assertNull(later.getAttribute("apiKey.audit.admitted"));
        admitted.setResponseCode(401); service.complete(admitted, null);
        assertEquals(2, pending(directory));
    }

    @Test
    public void authenticatedAgentCompletionNeverAuditsItsSignedTicketBody() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>(); AuditServiceImpl service = service(delivered);
        ApiRequest request = new ApiRequest(null, null); request.setMethod("POST"); request.setType("apiKeyDelegationCompletion");
        request.setRequestObject(Map.of("token", "private-stream-JWT", "outcome", "FAILED"));
        request.setResponseObject(Map.of("token", "private-response-JWT")); request.setResponseCode(204);
        request.setAttribute("requestStartTime", 1L); request.setAttribute("requestEndTime", 2L);
        service.logRequest(request, policy());
        assertEquals(1, delivered.size()); assertEquals(204, delivered.get(0).get("responseCode"));
        assertFalse(delivered.get(0).containsKey("requestObject")); assertFalse(delivered.get(0).containsKey("responseObject"));
        assertFalse(delivered.toString().contains("JWT")); assertFalse(AuditServiceImpl.isApiKeyRequest(request));
    }

    @Test
    public void delegatedFailureNeverAuditsEitherSignatureOnSuccessOrDenial() throws Exception {
        for (int status : new int[]{200, 401, 403, 503}) {
            List<Map<String, Object>> delivered = new ArrayList<>();
            AuditServiceImpl service = service(delivered);
            ApiRequest request = new ApiRequest(null, null);
            request.setMethod("POST"); request.setType("apiKeyDelegationFailure"); request.setLink("report");
            request.setRequestObject(Map.of("token", "private-stream-JWT", "backendToken", "private-backend-JWT"));
            request.setResponseObject(Map.of("backendToken", "private-response-JWT")); request.setResponseCode(status);
            request.setAttribute("requestStartTime", 1L); request.setAttribute("requestEndTime", 2L);
            service.logRequest(request, policy());
            assertEquals(1, delivered.size()); assertEquals(status, delivered.get(0).get("responseCode"));
            assertFalse(delivered.get(0).containsKey("requestObject")); assertFalse(delivered.get(0).containsKey("responseObject"));
            assertFalse(delivered.toString().contains("JWT")); assertFalse(AuditServiceImpl.isApiKeyRequest(request));
        }
    }

    @Test
    public void durableOutboxSurvivesRestartAndReplaysWithoutDroppingReads() throws Exception {
        Path path = temporary.newFolder().toPath();
        JacksonMapper mapper = mapper();
        ApiKeyAuditOutbox first = new ApiKeyAuditOutbox(path, mapper);
        first.persist(Map.of("eventId", "read-1", "operation", "read"), event -> { throw new IllegalStateException(); });
        assertEquals(1, pending(path));
        List<Map<String, Object>> delivered = new ArrayList<>();
        new ApiKeyAuditOutbox(path, mapper).persist(Map.of("eventId", "read-2", "operation", "read"), delivered::add);
        assertEquals(2, delivered.size());
        assertEquals(0, pending(path));
    }

    @Test
    public void queuedFirstTerminalResultSurvivesRestartAndRejectsCandidateOverwrite() throws Exception {
        Path path = temporary.newFolder().toPath();
        String eventId = "a".repeat(64);
        java.util.function.Consumer<Map<String, Object>> offline = event -> { throw new IllegalStateException(); };
        new ApiKeyAuditOutbox(path, mapper()).persist(Map.of("delegatedEventId", eventId, "data", Map.of("outcome", "SUCCEEDED")), offline);
        ApiKeyAuditOutbox restart = new ApiKeyAuditOutbox(path, mapper());
        restart.persist(Map.of("delegatedEventId", eventId, "data", Map.of("outcome", "FAILED")), offline);
        assertEquals(1, pending(path));
        List<Map<String, Object>> delivered = new ArrayList<>();
        restart.replayPending(delivered::add);
        assertEquals(1, delivered.size());
        assertEquals("SUCCEEDED", ((Map<?, ?>) delivered.get(0).get("data")).get("outcome"));
    }

    @Test
    public void delegatedAuditUnavailableIsFailureNotPermissionDenial() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        service.recordDelegatedOutcome(new ApiKeyDelegatedAuditEvent("d".repeat(64), "1a99", 2L, 1L, 3L,
                "logs", "container", "12", "request1", "FAILED", "AuditUnavailable", "host-uuid"));
        assertEquals(1, delivered.size());
        assertEquals("ALLOW", delivered.get(0).get("decision"));
        assertEquals("FAILED", delivered.get(0).get("outcome"));
        assertEquals("AuditUnavailable", delivered.get(0).get("reason"));
    }

    @Test
    public void verifiedCapabilityFailureIsActual503HandshakeNotExecutorCompletion() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        service.recordDelegatedOutcome(new ApiKeyDelegatedAuditEvent("e".repeat(64), "1a99", 2L, 1L, 3L,
                "exec", "container", "12", "request1", "FAILED", "BackendAuditCapabilityUnavailable", "host-uuid"));
        assertEquals(1, delivered.size());
        Map<String, Object> event = delivered.get(0);
        assertEquals("ALLOW", event.get("decision")); assertEquals("FAILED", event.get("outcome"));
        assertEquals("handshake", event.get("phase")); assertEquals(503, event.get("httpStatus"));
        assertEquals(503, event.get("responseCode")); assertEquals("BackendAuditCapabilityUnavailable", event.get("reason"));
        assertEquals(false, event.get("preview"));
        for (String invalid : List.of("SUCCEEDED", "CANCELLED", "CANCELED")) {
            try {
                service.recordDelegatedOutcome(new ApiKeyDelegatedAuditEvent("f".repeat(64), "1a99", 2L, 1L, 3L,
                        "exec", "container", "12", "request1", invalid, "BackendAuditCapabilityUnavailable", "host-uuid"));
                fail("A blocked handshake cannot claim execution or cancellation");
            } catch (IllegalStateException expected) { }
        }
        assertEquals(1, delivered.size());
    }

    @Test
    public void delegatedHostCompletionIsSafeMetadataAndNotAHeartbeatHttpSuccess() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        service.recordDelegatedOutcome(new ApiKeyDelegatedAuditEvent("b".repeat(64), "1a99", 2L, 1L, 3L,
                "exec", "container", "12", "request1", "CANCELLED", "AuthorizationRevoked", "host-uuid"));
        assertEquals(1, delivered.size());
        assertEquals("completion", delivered.get(0).get("phase"));
        assertEquals("CANCELED", delivered.get(0).get("outcome"));
        assertEquals(0, delivered.get(0).get("httpStatus"));
        assertEquals("AuthorizationRevoked", delivered.get(0).get("reason"));
        assertEquals("host-uuid", delivered.get(0).get("hostUuid"));
        try {
            service.recordDelegatedOutcome(new ApiKeyDelegatedAuditEvent("c".repeat(64), "1a99", 2L, 1L, 3L,
                    "exec", "container", "12", "request1", "FAILED", "private token/JWT payload", "host-uuid"));
            fail("Unreviewed failure strings must never become audit payload");
        } catch (IllegalStateException expected) { }
        assertEquals(1, delivered.size());
    }

    @Test
    public void verifiedWrongRouteIsDenied403HandshakeNotExecution() throws Exception {
        List<Map<String, Object>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        service.recordDelegatedOutcome(new ApiKeyDelegatedAuditEvent("a".repeat(64), "1a99", 2L, 1L, 3L,
                "logs", "container", "12", "request1", "FAILED", "DelegationRouteDenied", "host-uuid"));
        assertEquals(1, delivered.size());
        Map<String, Object> event = delivered.get(0);
        assertEquals("DENY", event.get("decision")); assertEquals("FAILED", event.get("outcome"));
        assertEquals("handshake", event.get("phase")); assertEquals(403, event.get("httpStatus"));
        assertEquals(403, event.get("responseCode")); assertEquals("DelegationRouteDenied", event.get("reason"));
        assertEquals("logs", event.get("operation")); assertEquals("12", event.get("targetId"));
        assertEquals("request1", event.get("requestId")); assertEquals(false, event.get("preview"));
        for (String invalid : List.of("SUCCEEDED", "CANCELLED", "CANCELED")) {
            try {
                service.recordDelegatedOutcome(new ApiKeyDelegatedAuditEvent("f".repeat(64), "1a99", 2L, 1L, 3L,
                        "logs", "container", "12", "request1", invalid, "DelegationRouteDenied", "host-uuid"));
                fail("Route denial must not claim execution or cancellation");
            } catch (IllegalStateException expected) { }
        }
        assertEquals(1, delivered.size());
    }

    @Test
    public void fullOutboxRejectsNewAdmissionAndRetainsExistingEvents() throws Exception {
        Path path = temporary.newFolder().toPath();
        ApiKeyAuditOutbox outbox = new ApiKeyAuditOutbox(path, mapper(), 1, 8192);
        outbox.persist(Map.of("eventId", "first"), event -> { throw new IllegalStateException(); });
        try {
            outbox.persist(Map.of("eventId", "second"), event -> { throw new IllegalStateException(); });
            fail("Full outbox must reject admission");
        } catch (IllegalStateException expected) {
            assertEquals("API-key audit persistence is unavailable", expected.getMessage());
        }
        assertEquals(1, pending(path));
    }

    @Test
    public void admittedResponseCapacityCannotBeConsumedByLaterRequests() throws Exception {
        Path path = temporary.newFolder().toPath();
        ApiKeyAuditOutbox outbox = new ApiKeyAuditOutbox(path, mapper(), 2, 16384);
        java.util.function.Consumer<Map<String, Object>> offline = event -> { throw new IllegalStateException(); };
        outbox.persist(Map.of("data", Map.of("requestId", "first", "phase", "decision")), offline);
        try {
            outbox.persist(Map.of("data", Map.of("requestId", "second", "phase", "decision")), offline);
            fail("New requests cannot consume the admitted response slot");
        } catch (IllegalStateException expected) { }
        outbox.persist(Map.of("data", Map.of("requestId", "first", "phase", "response", "outcome", "SUCCEEDED")), offline);
        assertEquals(2, pending(path));
    }

    @Test
    public void outboxFailureCannotMarkDecisionAsDurablyAdmitted() throws Exception {
        AuditServiceImpl service = service(new ArrayList<>());
        service.apiKeyOutbox = new ApiKeyAuditOutbox(temporary.newFolder().toPath(), mapper(), 0, 0);
        ApiRequest request = request("GET", "ALLOW", 200);
        try {
            service.recordDecision(request, policy());
            fail("Unavailable audit storage must reject admission");
        } catch (IllegalStateException expected) {
            assertNull(request.getAttribute("apiKey.audit.admitted"));
            assertEquals(Boolean.TRUE, request.getAttribute("apiKey.audit.persistenceFailed"));
        }
    }

    private int pending(Path path) throws Exception {
        try (Stream<Path> paths = Files.list(path)) {
            return (int) paths.filter(file -> file.toString().endsWith(".json")).count();
        }
    }

    private AuditServiceImpl service(List<Map<String, Object>> delivered) throws Exception {
        AuditServiceImpl service = new AuditServiceImpl();
        service.jsonMapper = mapper();
        service.apiKeyOutbox = new ApiKeyAuditOutbox(temporary.newFolder().toPath(), service.jsonMapper);
        service.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(), new Class<?>[]{AuditLogDao.class},
                (proxy, method, arguments) -> {
                    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) arguments[method.getName().equals("createDelegatedOnce") ? 3 : 2];
                    delivered.add(new HashMap<>(data));
                    return null;
                });
        return service;
    }

    private static JacksonMapper mapper() {
        JacksonMapper mapper = new JacksonMapper();
        mapper.init();
        return mapper;
    }

    private static ApiRequest request(String method, String decision, int status) {
        ApiRequest request = new ApiRequest(null, null);
        request.setMethod(method);
        request.setType("stack");
        request.setId("12");
        request.setResponseCode(status);
        request.setAttribute("apiKey.audit.keyId", "1a99");
        request.setAttribute("apiKey.audit.decision", decision);
        request.setAttribute("apiKey.audit.operation", "read");
        request.setAttribute("apiKey.audit.targetType", "stack");
        request.setAttribute("apiKey.audit.targetId", "12");
        request.setAttribute("apiKey.audit.policyRevision", 3);
        return request;
    }

    private static Policy policy() {
        return (Policy) Proxy.newProxyInstance(Policy.class.getClassLoader(), new Class<?>[]{Policy.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getAccountId")) return 1L;
                    if (method.getName().equals("getAuthenticatedAsAccountId")) return 2L;
                    return null;
                });
    }
}
