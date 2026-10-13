package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.ApiKeySubscriptionAuditEvent;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SubscriptionTerminalAuditTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void ordinaryCloseIoAndProcessorFaultAreNotAuthorizationDenials() throws Exception {
        String[] causes = {"CLIENT_CLOSED", "SERVER_CLOSED", "IO_ERROR", "INTERNAL_ERROR", "SUBSCRIBE_FAILED", "AUTHORIZATION_FAILED"};
        String[] reasons = {"ClientDisconnected", "StreamCancelled", "StreamFailed", "StreamFailed", "StreamFailed", "StreamFailed"};
        List<Map<String, Object>> rows = new ArrayList<>(); AuditServiceImpl service = service(rows);
        for (int i = 0; i < causes.length; i++) service.recordSubscriptionTerminal(terminal(causes[i], null, String.valueOf(i + 1).repeat(64)));
        assertEquals(causes.length, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            assertEquals("ALLOW", row.get("decision")); assertEquals(reasons[i], row.get("reason"));
            assertEquals(i < 2 ? "CANCELED" : "FAILED", row.get("outcome"));
            assertEquals("completion", row.get("phase")); assertEquals("subscribe", row.get("targetType"));
            assertEquals("", row.get("targetId")); assertEquals("read", row.get("operation"));
            assertEquals(0, ((Number) row.get("httpStatus")).intValue());
            assertFalse(row.containsKey("hostUuid")); assertFalse(row.containsKey("processId"));
            assertTrue(AuditServiceImpl.API_KEY_AUDIT_FIELDS.containsAll(row.keySet()));
        }
    }

    @Test public void actualLiveFailureRetainsCodeAndCapturedActorWithoutCallbackContext() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(); AuditServiceImpl service = service(rows); ApiContext.remove();
        int id = 0;
        for (String code : List.of("ApiKeyRevoked", "ApiKeyExpired", "ApiKeyPolicyChanged", "OwnerPermissionDenied", "KeyPolicyDenied")) {
            service.recordSubscriptionTerminal(terminal("AUTHORIZATION_FAILED", code, String.valueOf(++id).repeat(64)));
        }
        assertEquals(5, rows.size());
        for (Map<String, Object> row : rows) {
            assertEquals("DENY", row.get("decision")); assertEquals("DENIED", row.get("outcome"));
            assertEquals(row.get("reason"), row.get("failureCode")); assertEquals("42", row.get("actor"));
            assertEquals(3L, ((Number) row.get("policyRevision")).longValue());
        }
    }

    @Test public void terminalMetadataRejectsRawMessagesInvalidCodesAndWrongPrincipalBeforeOutbox() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(); AuditServiceImpl service = service(rows);
        assertThrows(IllegalStateException.class, () -> service.recordSubscriptionTerminal(terminal("CLIENT_CLOSED", "ApiKeyRevoked", "a".repeat(64))));
        assertThrows(IllegalStateException.class, () -> service.recordSubscriptionTerminal(terminal("AUTHORIZATION_FAILED", "private-client-message", "a".repeat(64))));
        assertThrows(IllegalStateException.class, () -> service.recordSubscriptionTerminal(terminal("private-client-message", null, "a".repeat(64))));
        assertThrows(IllegalStateException.class, () -> service.recordSubscriptionTerminal(new ApiKeySubscriptionAuditEvent(
                "a".repeat(64), "1c12", 0, 7, 3, "550e8400-e29b-41d4-a716-446655440000", "CLIENT_CLOSED", null)));
        assertTrue(rows.isEmpty());
    }

    @Test public void queuedGenericFirstTerminalSurvivesRestartAndDelegatedAliasRemainsCompatible() throws Exception {
        Path path = temporary.newFolder().toPath(); String id = "a".repeat(64);
        Consumer<Map<String, Object>> offline = event -> { throw new IllegalStateException("DB unavailable"); };
        new ApiKeyAuditOutbox(path, mapper()).persist(Map.of("terminalEventId", id, "data", Map.of("outcome", "CANCELED")), offline);
        ApiKeyAuditOutbox restart = new ApiKeyAuditOutbox(path, mapper());
        restart.persist(Map.of("terminalEventId", id, "data", Map.of("outcome", "FAILED")), offline);
        assertEquals(1, pending(path)); List<Map<String, Object>> delivered = new ArrayList<>();
        restart.replayPending(delivered::add);
        assertEquals(1, delivered.size()); assertEquals("CANCELED", ((Map<?, ?>) delivered.get(0).get("data")).get("outcome"));
        restart.persist(Map.of("delegatedEventId", "b".repeat(64), "data", Map.of("outcome", "SUCCEEDED")), delivered::add);
        assertEquals(2, delivered.size()); assertEquals(0, pending(path));
    }

    @Test public void conflictingOrNonStringTerminalAliasesAreRejectedWithoutNewFile() throws Exception {
        Path path = temporary.newFolder().toPath(); ApiKeyAuditOutbox outbox = new ApiKeyAuditOutbox(path, mapper());
        assertThrows(IllegalStateException.class, () -> outbox.persist(Map.of("terminalEventId", "a".repeat(64), "delegatedEventId", "b".repeat(64)), event -> fail()));
        assertThrows(IllegalStateException.class, () -> outbox.persist(Map.of("terminalEventId", 12), event -> fail()));
        assertEquals(0, pending(path));
    }

    @Test public void storageFailureDoesNotClaimTerminalDurable() throws Exception {
        AuditServiceImpl service = service(new ArrayList<>());
        service.apiKeyOutbox = new ApiKeyAuditOutbox(temporary.newFolder().toPath(), mapper(), 0, 0);
        assertThrows(IllegalStateException.class, () -> service.recordSubscriptionTerminal(terminal("CLIENT_CLOSED", null, "a".repeat(64))));
    }

    private AuditServiceImpl service(List<Map<String, Object>> delivered) throws Exception {
        AuditServiceImpl service = new AuditServiceImpl(); service.jsonMapper = mapper();
        service.apiKeyOutbox = new ApiKeyAuditOutbox(temporary.newFolder().toPath(), service.jsonMapper);
        service.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(), new Class<?>[]{AuditLogDao.class}, (p, method, args) -> {
            assertEquals("createTerminalOnce", method.getName()); assertEquals("subscribe", args[1]); assertNull(args[2]);
            assertEquals(7L, args[5]); assertEquals(42L, args[6]); assertEquals("api.subscribe.read", args[7]);
            Map<?, ?> data = (Map<?, ?>) args[3]; Map<String, Object> row = new HashMap<>();
            data.forEach((key, value) -> row.put((String) key, value)); delivered.add(row); return null;
        });
        return service;
    }
    private static ApiKeySubscriptionAuditEvent terminal(String close, String code, String id) {
        return new ApiKeySubscriptionAuditEvent(id, "1c12", 42, 7, 3, "550e8400-e29b-41d4-a716-446655440000", close, code);
    }
    private static JacksonMapper mapper() { JacksonMapper mapper = new JacksonMapper(); mapper.init(); return mapper; }
    private static long pending(Path path) throws Exception {
        try (Stream<Path> paths = Files.list(path)) { return paths.filter(file -> file.toString().endsWith(".json")).count(); }
    }
}
