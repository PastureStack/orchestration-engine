package io.cattle.platform.iaas.api.auth.integration.ldap.OpenLDAP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class OpenLDAPConfigManagerIdentityListTest {

    @Test
    public void identityMapListKeepsStringIdentityMaps() {
        Map<String, String> identity = new LinkedHashMap<String, String>();
        identity.put("externalIdType", "user");
        identity.put("externalId", "alice");

        List<Map<String, String>> identities = OpenLDAPConfigManager.identityMapList(Arrays.asList(identity));

        assertEquals(1, identities.size());
        assertEquals("user", identities.get(0).get("externalIdType"));
        assertEquals("alice", identities.get(0).get("externalId"));
    }

    @Test
    public void identityMapListAllowsNullLikeLegacyCast() {
        assertEquals(null, OpenLDAPConfigManager.identityMapList(null));
    }

    @Test
    public void identityMapListRejectsNonListAllowedIdentities() {
        try {
            OpenLDAPConfigManager.identityMapList("user:alice");
            fail("Expected non-list allowedIdentities to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.util.List"));
        }
    }

    @Test
    public void identityMapListRejectsNonMapEntries() {
        try {
            OpenLDAPConfigManager.identityMapList(Arrays.asList("user:alice"));
            fail("Expected non-map allowedIdentities entry to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.util.Map"));
        }
    }

    @Test
    public void identityMapListRejectsNonStringMapValues() {
        Map<String, Object> identity = new LinkedHashMap<String, Object>();
        identity.put("externalIdType", "user");
        identity.put("externalId", 1);

        try {
            OpenLDAPConfigManager.identityMapList(Arrays.asList(identity));
            fail("Expected non-string identity map value to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.lang.String"));
        }
    }
}
