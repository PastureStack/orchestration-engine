package io.cattle.platform.iaas.api.auditing.dao.impl;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.AuditLog;
import io.cattle.platform.db.jooq.converter.DataConverter;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.Test;

public class ApiKeyAuditLogDaoTest {
    @Test
    public void replayPreservesVerifiedOwnerAndActorRegardlessOfCurrentRequestPolicy() {
        AtomicInteger policyReads = new AtomicInteger();
        Policy unrelatedPolicy = (Policy) Proxy.newProxyInstance(Policy.class.getClassLoader(), new Class<?>[]{Policy.class},
                (proxy, method, args) -> { policyReads.incrementAndGet(); throw new AssertionError("Replay must not use current policy"); });
        ApiContext.newContext().setPolicy(unrelatedPolicy);
        try {
            assertStoredEvent(2513L, 2513L, "DENIED");
            assertStoredEvent(2540L, 2510L, "SUCCEEDED");
            assertStoredEvent(2513L, 1L, "KeyGovernanceCompleted");
            assertEquals(0, policyReads.get());
        } finally {
            ApiContext.remove();
        }
    }

    @Test
    public void anonymousDenialRemainsAnonymousDuringAuthenticatedReplay() {
        ApiContext.newContext();
        try { assertStoredEvent(null, null, "AUTHENTICATION_DENIED"); }
        finally { ApiContext.remove(); }
    }

    @Test
    public void backgroundReplayDoesNotRequireApiContext() {
        ApiContext.remove();
        assertStoredEvent(2540L, 2511L, "SUCCEEDED");
    }

    private static void assertStoredEvent(Long account, Long actor, String outcome) {
        AtomicInteger inserts = new AtomicInteger();
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(SQLDialect.MYSQL);
        configuration.set(new MockConnection(context -> {
            String sql = context.sql();
            assertTrue(sql.toLowerCase(java.util.Locale.ROOT).startsWith("insert"));
            assertTrue(sql.contains("audit_log"));
            var fields = Pattern.compile("\\(([^()]+)\\)\\s*values", Pattern.CASE_INSENSITIVE).matcher(sql);
            assertTrue(fields.find());
            String[] names = fields.group(1).split(",");
            Object[] values = context.bindings();
            assertEquals(names.length, values.length);
            Map<String, Object> persisted = new HashMap<>();
            for (int i = 0; i < names.length; i++) persisted.put(names[i].trim().replace("`", "").replace("\"", ""), values[i]);
            assertEquals(account, persisted.get("account_id"));
            assertEquals(actor, persisted.get("authenticated_as_account_id"));
            assertEquals("auditLog", persisted.get("kind"));
            assertNotNull(persisted.get("created"));
            assertEquals("api.stack.read", persisted.get("event_type"));
            assertEquals(outcome, ((Map<?, ?>) new DataConverter().from((String) persisted.get("data")).get(DataUtils.FIELDS)).get("outcome"));
            inserts.incrementAndGet();
            return new MockResult[]{new MockResult(1)};
        }));
        AuditLogDaoImpl dao = new AuditLogDaoImpl();
        dao.setConfiguration(configuration);
        dao.objectManager = (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                new Class<?>[]{ObjectManager.class}, (proxy, method, args) -> { throw new AssertionError("Trusted event must not run request post-init"); });
        AuditLog stored = dao.createApiKeyEvent("stack", 42L, Map.of("outcome", outcome), null,
                account, actor, "api.stack.read", "BasicAuth", 0L, null, "");
        assertEquals(1, inserts.get());
        assertEquals(account, stored.getAccountId());
        assertEquals(actor, stored.getAuthenticatedAsAccountId());
        assertEquals("auditLog", stored.getKind());
        assertNotNull(stored.getCreated());
    }
}
