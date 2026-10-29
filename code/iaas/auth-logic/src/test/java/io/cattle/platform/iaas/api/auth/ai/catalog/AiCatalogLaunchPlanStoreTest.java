package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import io.cattle.platform.json.JacksonJsonMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.junit.Test;

public class AiCatalogLaunchPlanStoreTest {
    @Test public void concurrentDurableClaimsHaveOneWinnerAndRestartCannotReclaim() throws Exception {
        String url = "jdbc:h2:mem:catalog-launch-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection first = DriverManager.getConnection(url); Connection second = DriverManager.getConnection(url)) {
            first.createStatement().execute("CREATE TABLE ai_catalog_stack_launch_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),document CLOB,"
                    + "actor_id BIGINT,account_id BIGINT,stack_id BIGINT UNIQUE,expires_at BIGINT)");
            var a = store(first); var b = store(second); Map<String, Object> initial = plan("launch-one", 42, 5); a.insert(initial);
            Map<String, Object> left = a.load("launch-one"), right = b.load("launch-one"); left.put("status", "dispatching"); right.put("status",
                    "dispatching");
            CountDownLatch barrier = new CountDownLatch(1); var workers = Executors.newFixedThreadPool(2);
            try {
                var one = workers.submit(() -> { barrier.await(); return a.claim(left); });
                var two = workers.submit(() -> { barrier.await(); return b.claim(right); }); barrier.countDown();
                assertNotEquals(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
            } finally { workers.shutdownNow(); }
            assertEquals("dispatching", store(first).load("launch-one").get("status")); assertFalse(store(second).claim(initial));
        }
    }

    @Test public void inboxIncludesApprovedUnclaimedOnlyForExactActorProjectAndExpiry() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:catalog-inbox-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE")) {
            connection.createStatement().execute("CREATE TABLE ai_catalog_stack_launch_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),document CLOB,"
                    + "actor_id BIGINT,account_id BIGINT,stack_id BIGINT UNIQUE,expires_at BIGINT)");
            var store = store(connection); store.insert(plan("approved", 42, 5)); store.insert(plan("other-project", 42, 6));
                    store.insert(plan("other-actor", 43, 5));
            assertEquals(1, store.pending(42, 5, Instant.parse("2027-01-01T00:00:00Z").toEpochMilli()).size());
            assertEquals("approved", store.pending(42, 5, 0).getFirst().get("confirmationState"));
            assertTrue(store.pending(42, 5, Instant.parse("2040-01-01T00:00:00Z").toEpochMilli()).isEmpty());
            assertThrows(IllegalStateException.class, () -> store.loadForUpdate("approved"));
            connection.setAutoCommit(false); assertNotNull(store.loadForUpdate("approved")); connection.rollback();
        }
    }
    private static Map<String, Object> plan(String id, long actor, long project) {
        return new LinkedHashMap<>(Map.of("id", id, "actorId", actor, "accountId", project, "status", "needs_user", "confirmationState",
                "approved", "expiresAt", "2030-01-01T00:00:00Z"));
    }
    private static AiCatalogLaunchPlanStore store(Connection connection) {
        var store = new AiCatalogLaunchPlanStore(); store.json = new JacksonJsonMapper(); store.setConfiguration(new
                DefaultConfiguration().set(connection).set(SQLDialect.H2)); return store;
    }
}
