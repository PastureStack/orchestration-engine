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

    @Test public void ignoredSelectorsCannotChangeMutationAuthority() {
        for (String selector : new String[] {"composeConfig", "execute", "logs", "secretValues"}) {
            assertEquals("update", ApiKeyOperations.of("PUT", null, selector).id());
            assertEquals("delete", ApiKeyOperations.of("DELETE", null, selector).id());
            assertEquals("create", ApiKeyOperations.of("POST", null, selector).id());
        }
        for (String action : new String[] {"upgrade", "execute", "logs", "dbdump", "remove"}) {
            assertEquals("update", ApiKeyOperations.of("PUT", action, null).id());
            assertEquals("delete", ApiKeyOperations.of("DELETE", action, null).id());
            assertEquals("read", ApiKeyOperations.of("GET", action, null).id());
        }
    }

    @Test public void bothSelectorsFollowTheActualDispatcherPrecedence() {
        assertEquals("export", ApiKeyOperations.of("GET", "upgrade", "composeConfig").id());
        assertEquals("export", ApiKeyOperations.of("GET", "restart", "secretValues").id());
        assertEquals("upgrade", ApiKeyOperations.of("POST", "upgrade", "composeConfig").id());
        assertEquals("read", ApiKeyOperations.of("GET", "remove", "instances").id());
        assertFalse(ApiKeyOperations.of("POST", "unknownAction", "composeConfig").registered());
    }

    @Test public void headAndCaseInsensitiveMethodsKeepTheirExistingSemantics() {
        assertEquals("export", ApiKeyOperations.of("head", "restart", "DBDump").id());
        assertEquals("upgrade", ApiKeyOperations.of("post", "UPGRADE", null).id());
        assertEquals("update", ApiKeyOperations.of("put", "upgrade", "composeConfig").id());
        assertEquals("read", ApiKeyOperations.of("OPTIONS", "upgrade", "composeConfig").id());
        assertFalse(ApiKeyOperations.of(null, "upgrade", "composeConfig").registered());
    }
}
