package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.Test;

public class AiRegistryRemovalChangeTest {
    @Test public void removalIsAnEmptyNativeDeleteOfTheInactiveRegistryNotAnotherCredentialApi() {
        var descriptor = AiResourceChangeDescriptor.require("registry", "remove");
        assertEquals("DELETE", descriptor.method()); assertNull(descriptor.action()); assertEquals("delete", descriptor.keyOperation());
        assertEquals(Map.of(), descriptor.parameters(Map.of())); assertEquals(Map.of(), descriptor.body(Map.of()));
        var root = AiRegistryLifecycleChangeTest.registry(); AiRegistryLifecycleChange.ready(descriptor, root);
        root.setState("active"); assertThrows(ClientVisibleException.class, () -> AiRegistryLifecycleChange.ready(descriptor, root));
        for (String field : List.of("secretValue", "publicValue", "credential", "registryId", "serverAddress", "force"))
            assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of(field, "PRIVATE")));
    }

    @Test public void realSqlPinsTheCompleteNonSensitiveCredentialSetAndRejectsForeignOrTruncatedSets() throws Exception {
        try (Connection connection = database()) {
            var sql = DSL.using(connection, SQLDialect.H2);
            connection.createStatement().execute("INSERT INTO credential"
                    + " VALUES(11,'credential-one',5,3,'registryCredential','active',NULL,'PRIVATE_USER','PRIVATE_SECRET')");
            var rows = AiRegistryRemovalChange.credentials(sql, 3, 5, true, List.of());
            assertEquals(1, rows.size()); assertEquals("credential-one", rows.get(0).get("uuid"));
            assertEquals(7, rows.get(0).size()); assertFalse(rows.toString().contains("PRIVATE"));
            for (String mutation : List.of("account_id=6", "kind='apiKey'", "uuid=''")) {
                connection.createStatement().execute("UPDATE credential SET " + mutation + " WHERE id=11");
                assertThrows(ClientVisibleException.class, () -> AiRegistryRemovalChange.credentials(sql, 3, 5, true, List.of()));
                connection.createStatement().execute("UPDATE credential SET account_id=5,kind='registryCredential',uuid='credential-one' WHERE id=11");
            }
            connection.createStatement().execute("UPDATE credential SET registry_id=4 WHERE id=11");
            assertThrows(ClientVisibleException.class, () -> AiRegistryRemovalChange.credentials(sql, 3, 5, false, rows));
            connection.createStatement().execute("DELETE FROM credential");
            for (int id = 1; id <= AiRegistryRemovalChange.MAX_CREDENTIALS + 1; id++)
                sql.execute("INSERT INTO credential(id,uuid,account_id,registry_id,kind,state) VALUES(?, ?,5,3,'registryCredential','inactive')",
                        id, "uuid-" + id);
            assertThrows(ClientVisibleException.class, () -> AiRegistryRemovalChange.credentials(sql, 3, 5, true, List.of()));
        }
    }

    @Test public void logicalCompletionRequiresEveryCapturedChildAndRejectsNewOrReassignedCredentials() throws Exception {
        try (Connection connection = database()) {
            var sql = DSL.using(connection, SQLDialect.H2); var root = AiRegistryLifecycleChangeTest.registry();
            connection.createStatement().execute("INSERT INTO credential"
                    + " VALUES(11,'credential-one',5,3,'registryCredential','active',NULL,'PRIVATE_USER','PRIVATE_SECRET')");
            var before = new LinkedHashMap<>(AiRegistryLifecycleChange.snapshot(root));
            var captured = AiRegistryRemovalChange.credentials(sql, 3, 5, true, List.of()); before.put(AiRegistryRemovalChange.CREDENTIALS, captured);
            root.setState("removed"); root.setRemoved(new Date());
            assertFalse(AiRegistryRemovalChange.complete(root, before, AiRegistryLifecycleChange.snapshot(root),
                    AiRegistryRemovalChange.credentials(sql, 3, 5, false, captured)));
            sql.execute("UPDATE credential SET state='purging',removed=CURRENT_TIMESTAMP WHERE id=11");
            assertTrue(AiRegistryRemovalChange.complete(root, before, AiRegistryLifecycleChange.snapshot(root),
                    AiRegistryRemovalChange.credentials(sql, 3, 5, false, captured)));
            sql.execute("INSERT INTO credential"
                    + " VALUES(12,'credential-new',5,3,'registryCredential','removed',CURRENT_TIMESTAMP,'PRIVATE_USER','PRIVATE_SECRET')");
            assertFalse(AiRegistryRemovalChange.complete(root, before, AiRegistryLifecycleChange.snapshot(root),
                    AiRegistryRemovalChange.credentials(sql, 3, 5, false, captured)));
            sql.execute("DELETE FROM credential WHERE id=12"); sql.execute("UPDATE credential SET uuid='replacement' WHERE id=11");
            assertFalse(AiRegistryRemovalChange.complete(root, before, AiRegistryLifecycleChange.snapshot(root),
                    AiRegistryRemovalChange.credentials(sql, 3, 5, false, captured)));
            sql.execute("DELETE FROM credential WHERE id=11");
            assertTrue(AiRegistryRemovalChange.complete(root, before, AiRegistryLifecycleChange.snapshot(root),
                    AiRegistryRemovalChange.credentials(sql, 3, 5, false, captured)));
            root.setUuid("replacement"); assertFalse(AiRegistryRemovalChange.complete(root, before, AiRegistryLifecycleChange.snapshot(root), List.of()));
        }
    }

    @Test public void confirmationAndApplyFenceAllChildMetadataAndKeepHumanKeyAndCancellationRules() {
        for (boolean apply : List.of(false, true)) {
            var f = new AiResourceChangePlanServiceTest.Fixture();
            var lease = new java.util.concurrent.atomic.AtomicBoolean();
            when(f.domain.withRegistryRemovalWrite(any(), anyMap(), any())).thenAnswer(call -> {
                assertFalse(lease.getAndSet(true));
                try { return ((java.util.function.Supplier<?>) call.getArgument(2)).get(); }
                finally { lease.set(false); }
            });
            when(f.domain.lock("registry", 7L)).thenAnswer(call -> { assertTrue("native lease precedes root row", lease.get()); return f.target; });
            var state = new LinkedHashMap<String, Object>(Map.of("id", 7L, "uuid", "registry", "accountId", 77L, "kind", "registry", "state", "inactive"));
            var credential = new LinkedHashMap<String, Object>(Map.of("id", "11", "uuid", "credential-one", "accountId", "77",
                    "registryId", "7", "kind", "registryCredential", "state", "active")); credential.put("removed", null);
            state.put(AiRegistryRemovalChange.CREDENTIALS, List.of(credential));
            when(f.domain.registryRemovalSnapshot(any())).thenAnswer(call -> {
                assertTrue("native lease precedes child set rows", lease.get()); return f.copy(state);
            });
            var request = f.request(null, true); request.setId(null);
            request.setRequestObject(Map.of("resourceType", "registry", "operation", "remove", "resourceId", "1sp7", "parameters", Map.of()));
            f.id = (String) f.service.preview(request).get("id");
            if (apply) f.confirm(); state.put(AiRegistryRemovalChange.CREDENTIALS, List.of(Map.of("id", "11", "uuid", "changed")));
            assertThrows(ClientVisibleException.class, () -> { if (apply) f.apply(true); else f.confirm(); });
            assertEquals(0, f.claims); assertEquals(0, f.dispatched);
        }
    }

    private static Connection database() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:registry-remove-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE");
        connection.createStatement().execute("CREATE SCHEMA cattle"); connection.setSchema("cattle");
        connection.createStatement().execute("CREATE TABLE credential(id BIGINT PRIMARY KEY,uuid VARCHAR(64),account_id BIGINT,registry_id BIGINT,"
                + "kind VARCHAR(64),state VARCHAR(64),removed TIMESTAMP,public_value VARCHAR(255),secret_value VARCHAR(255))");
        return connection;
    }
}
