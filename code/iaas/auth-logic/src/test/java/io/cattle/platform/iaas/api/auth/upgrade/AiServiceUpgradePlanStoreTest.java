package io.cattle.platform.iaas.api.auth.upgrade;

import static org.junit.Assert.*;
import io.cattle.platform.json.JacksonJsonMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.junit.Test;

public class AiServiceUpgradePlanStoreTest {
    @Test public void pendingInboxQueriesOnlyLiveActorRowsAndCapsTheRead() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:ai-upgrade-inbox;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")) {
            connection.createStatement().execute("CREATE TABLE ai_service_upgrade_plan(id VARCHAR(64) PRIMARY KEY, state VARCHAR(32), document CLOB, actor_id BIGINT, expires_at BIGINT)");
            AiServiceUpgradePlanStore store = store(connection);
            for (int i = 0; i < 23; i++) {
                store.insert(new LinkedHashMap<>(Map.of("id", "aiplan-" + i, "status", "preview", "actorId", i == 22 ? 43 : 42,
                        "expiresAt", i == 21 ? "2026-10-28T00:05:00Z" : "2026-10-29T00:05:00Z")));
            }
            var pending = store.pending(42, java.time.Instant.parse("2026-10-29T00:00:00Z").toEpochMilli());
            assertEquals(20, pending.size());
            assertTrue(pending.stream().allMatch(plan -> ((Number)plan.get("actorId")).longValue() == 42));
            assertFalse(pending.stream().anyMatch(plan -> "aiplan-21".equals(plan.get("id"))));
        }
    }
    @Test public void receiptSurvivesNewStoreAndOnlyOneSqlClaimSucceeds() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:ai-upgrade;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")) {
            connection.createStatement().execute("CREATE TABLE ai_service_upgrade_plan(id VARCHAR(64) PRIMARY KEY, state VARCHAR(32), document CLOB, actor_id BIGINT, expires_at BIGINT)");
            AiServiceUpgradePlanStore first = store(connection);
            Map<String, Object> plan = new LinkedHashMap<>(Map.of("id", "aiplan-11111111-1111-1111-1111-111111111111", "status", "preview", "actorId", 42, "expiresAt", "2026-10-29T00:05:00Z",
                    "snapshot", Map.of("environment", Map.of("PRIVATE", "retained"))));
            first.insert(plan);
            assertEquals(1, first.pendingCount(42));
            AiServiceUpgradePlanStore restarted = store(connection);
            assertEquals(plan, restarted.load((String) plan.get("id")));
            Map<String, Object> staleCopy = first.load((String) plan.get("id"));
            plan.put("status", "dispatching"); staleCopy.put("status", "dispatching");
            assertTrue(restarted.claim(plan)); assertFalse(first.claim(staleCopy));
            assertEquals("dispatching", first.load((String) plan.get("id")).get("status"));
            Map<String, Object> expired = new LinkedHashMap<>(plan);
            expired.put("id", "aiplan-22222222-2222-2222-2222-222222222222"); expired.put("status", "preview");
            restarted.insert(expired); assertEquals(1, restarted.pendingCount(42));
            restarted.pruneExpiredPreviews(java.time.Instant.parse("2026-10-30T00:00:00Z").toEpochMilli());
            assertNull(restarted.load((String) expired.get("id"))); assertEquals(0, restarted.pendingCount(42));
            assertNotNull(restarted.load((String) plan.get("id"))); // Never prune accepted/uncertain receipts.
        }
    }
    private AiServiceUpgradePlanStore store(Connection connection) {
        AiServiceUpgradePlanStore store = new AiServiceUpgradePlanStore();
        store.json = new JacksonJsonMapper();
        store.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
        return store;
    }
}
