package io.cattle.platform.iaas.api.auth.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class IdentityLinkKeyTest {

    @Test
    public void providerAndTypeAreNormalizedButSubjectRemainsCaseSensitive() {
        String first = IdentityLinkKey.create(" OIDCConfig ", "OIDC_USER", "issuer|Subject");
        String normalized = IdentityLinkKey.create("oidcconfig", "oidc_user", "issuer|Subject");
        String differentSubject = IdentityLinkKey.create("oidcconfig", "oidc_user", "issuer|subject");

        assertEquals(first, normalized);
        assertFalse(first.equals(differentSubject));
        assertEquals(64, first.length());
    }

    @Test
    public void lengthPrefixPreventsDelimiterAmbiguity() {
        String first = IdentityLinkKey.create("ab", "c", "d:e");
        String second = IdentityLinkKey.create("a", "bc", "d:e");

        assertFalse(first.equals(second));
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankExternalIdentityIsRejected() {
        IdentityLinkKey.create("oidcconfig", "oidc_user", " ");
    }
}
