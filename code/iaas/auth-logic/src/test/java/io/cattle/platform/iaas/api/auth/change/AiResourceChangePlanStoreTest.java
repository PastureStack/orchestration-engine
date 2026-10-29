package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import io.cattle.platform.json.JacksonJsonMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.junit.Test;

public class AiResourceChangePlanStoreTest {
    @Test public void durableClaimSurvivesANewStoreAndNeverBecomesPreviewAgain() throws Exception {
        try (Connection connection = database()) {
            var first = store(connection); Map<String, Object> plan = plan("aichange-one", 42);
            first.insert(plan); var restarted = store(connection); var stale = first.load("aichange-one");
            plan.put("status", "dispatching"); stale.put("status", "dispatching");
            assertTrue(restarted.claim(plan)); assertFalse(first.claim(stale));
            assertEquals("dispatching", restarted.load("aichange-one").get("status"));
            restarted.prune(Instant.parse("2026-10-30T00:00:00Z").toEpochMilli());
            assertNotNull(first.load("aichange-one"));
        }
    }
    @Test public void inboxIsActorBoundTimeBoundAndAtMostTwenty() throws Exception {
        try (Connection connection = database()) {
            var store = store(connection);
            for (int i = 0; i < 24; i++) store.insert(plan("aichange-" + i, i == 23 ? 43 : 42));
            assertEquals(20, store.pending(42, Instant.parse("2026-10-29T00:00:00Z").toEpochMilli()).size());
            assertTrue(store.pending(42, Instant.parse("2026-10-30T00:00:00Z").toEpochMilli()).isEmpty());
            assertEquals(1, store.pending(43, Instant.parse("2026-10-29T00:00:00Z").toEpochMilli()).size());
        }
    }
    @Test public void lockedPlanReadRequiresTheExistingWriteTransaction() throws Exception {
        try (Connection connection = database()) {
            var store = store(connection); store.insert(plan("aichange-one", 42));
            assertThrows(IllegalStateException.class, () -> store.loadForUpdate("aichange-one"));
            connection.setAutoCommit(false); assertNotNull(store.loadForUpdate("aichange-one")); connection.rollback();
        }
    }
    private static Map<String, Object> plan(String id, long actor) {
        return new LinkedHashMap<>(Map.of("id", id, "status", "preview", "actorId", actor, "expiresAt", "2026-10-29T00:05:00Z"));
    }
    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:resource-plan-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
        connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
        return connection;
    }
    private static AiResourceChangePlanStore store(Connection connection) {
        var store = new AiResourceChangePlanStore(); store.json = new JacksonJsonMapper();
        store.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2)); return store;
    }
}
