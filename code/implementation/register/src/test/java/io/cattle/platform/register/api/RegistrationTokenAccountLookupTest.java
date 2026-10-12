package io.cattle.platform.register.api;

import static org.junit.Assert.*;
import org.junit.Test;

public class RegistrationTokenAccountLookupTest {
    @Test public void onlyExactReservedRegistrationUsernameClaimsBasicAuthentication() {
        RegistrationTokenAccountLookup lookup = new RegistrationTokenAccountLookup();
        assertTrue(lookup.acceptsBasicUsername("registrationToken"));
        for (String username : new String[] {null, "", "apiKey", "RegistrationToken", "registrationtoken", "registrationToken-other"}) {
            assertFalse(lookup.acceptsBasicUsername(username));
        }
    }
}
