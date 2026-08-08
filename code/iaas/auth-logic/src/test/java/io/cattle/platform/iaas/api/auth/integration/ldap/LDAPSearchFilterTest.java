package io.cattle.platform.iaas.api.auth.integration.ldap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LDAPSearchFilterTest {

    @Test
    public void buildsParameterizedEqualityFilter() {
        LDAPSearchFilter filter = LDAPSearchFilter.equality("uid", "alice*)(objectClass=*)");

        assertEquals("(uid={0})", filter.expression());
        assertArrayEquals(new Object[] { "alice*)(objectClass=*)" }, filter.arguments());
        assertEquals("(uid=alice\\2a\\29\\28objectClass=\\2a\\29)", filter.escapedExpression());
    }

    @Test
    public void buildsParameterizedAndFilterWithShiftedArguments() {
        LDAPSearchFilter filter = LDAPSearchFilter.and(LDAPSearchFilter.contains("cn", "dev*ops"),
                LDAPSearchFilter.equality("objectClass", "inetOrgPerson"));

        assertEquals("(&(cn=*{0}*)(objectClass={1}))", filter.expression());
        assertArrayEquals(new Object[] { "dev*ops", "inetOrgPerson" }, filter.arguments());
        assertEquals("(&(cn=*dev\\2aops*)(objectClass=inetOrgPerson))", filter.toString());
    }
}
