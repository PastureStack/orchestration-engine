package io.cattle.platform.iaas.api.auth.integration.ldap;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LDAPFilterUtilsTest {

    @Test
    public void escapesFilterValueMetacharacters() {
        assertEquals("user\\5c\\2a\\28name\\29\\00", LDAPFilterUtils.escapeValue("user\\*(name)\u0000"));
    }

    @Test
    public void buildsEqualityFilterEscapingOnlyTheValue() {
        String filter = LDAPFilterUtils.equality("uid", "alice*)(objectClass=*)");

        assertEquals("(uid=alice\\2a\\29\\28objectClass=\\2a\\29)", filter);
    }

    @Test
    public void buildsContainsFilterWithLiteralUserWildcard() {
        String filter = LDAPFilterUtils.contains("cn", "dev*ops");

        assertEquals("(cn=*dev\\2aops*)", filter);
    }

    @Test
    public void buildsAndFilterWithoutRewritingClauses() {
        String filter = LDAPFilterUtils.and(LDAPFilterUtils.equality("uid", "alice"),
                LDAPFilterUtils.equality("objectClass", "inetOrgPerson"));

        assertEquals("(&(uid=alice)(objectClass=inetOrgPerson))", filter);
    }
}
