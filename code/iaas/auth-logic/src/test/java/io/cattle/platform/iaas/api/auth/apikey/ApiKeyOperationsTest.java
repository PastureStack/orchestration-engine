package io.cattle.platform.iaas.api.auth.apikey;
import static org.junit.Assert.*;
import org.junit.Test;
public class ApiKeyOperationsTest {
    @Test public void sensitiveDownloadsAreExportsNotOrdinaryReads() {
        for (String link : new String[] {"composeConfig", "config", "pem", "certificate", "dockerComposeConfig", "rancherComposeConfig"}) {
            assertEquals("export", ApiKeyOperations.of("GET", null, link).id());
        }
    }
    @Test public void actionMethodDoesNotCollapseUpgradeOrExecutionToCreate() {
        assertEquals("upgrade", ApiKeyOperations.of("POST", "upgrade", null).id());
        assertEquals("exec", ApiKeyOperations.of("POST", "execute", null).id());
        assertEquals("logs", ApiKeyOperations.of("POST", "logs", null).id());
        assertFalse(ApiKeyOperations.of("POST", "arbitraryNewAction", null).registered());
    }
    @Test public void machineSecretsAndDatabaseBackupsAreExportsNotOrdinaryRelationshipReads() {
        for (String link : new String[] {"secretValues", "secretvalues", "dbdump", "DBDump"}) {
            var operation = ApiKeyOperations.of("GET", null, link);
            assertEquals("export", operation.id());
            assertTrue(operation.registered());
        }
        assertEquals("read", ApiKeyOperations.of("GET", null, "instances").id());
        assertEquals("read", ApiKeyOperations.of("GET", null, "projectMembers").id());
    }
}
