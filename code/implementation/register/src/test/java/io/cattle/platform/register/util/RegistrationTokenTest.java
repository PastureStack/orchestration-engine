package io.cattle.platform.register.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.junit.Test;

public class RegistrationTokenTest {

    private static final Date TOKEN_DATE = new Date(1700000000000L);
    private static final String ACCESS_KEY = "reg-access-key";
    private static final String SECRET_KEY = "reg-secret-key";

    @Test
    public void createsAndValidatesSha256RegistrationToken() {
        String token = RegistrationToken.createToken(ACCESS_KEY, SECRET_KEY, TOKEN_DATE);

        assertTrue(RegistrationToken.isValidToken(token, ACCESS_KEY, SECRET_KEY, TOKEN_DATE));
        assertFalse(RegistrationToken.isValidToken(token, ACCESS_KEY, "wrong-secret", TOKEN_DATE));
    }

    @Test
    public void stillAcceptsLegacySha1RegistrationToken() {
        String legacyToken = RegistrationToken.createLegacyToken(ACCESS_KEY, SECRET_KEY, TOKEN_DATE);
        String currentToken = RegistrationToken.createToken(ACCESS_KEY, SECRET_KEY, TOKEN_DATE);

        assertFalse("new tokens must not continue using the legacy SHA1 signature", legacyToken.equals(currentToken));
        assertTrue(RegistrationToken.isValidToken(legacyToken, ACCESS_KEY, SECRET_KEY, TOKEN_DATE));
    }

    @Test
    public void rejectsMalformedOrTamperedRegistrationToken() {
        String token = RegistrationToken.createToken(ACCESS_KEY, SECRET_KEY, TOKEN_DATE);

        assertFalse(RegistrationToken.isValidToken(null, ACCESS_KEY, SECRET_KEY, TOKEN_DATE));
        assertFalse(RegistrationToken.isValidToken(token + "tampered", ACCESS_KEY, SECRET_KEY, TOKEN_DATE));
        assertFalse(RegistrationToken.isValidToken(token, null, SECRET_KEY, TOKEN_DATE));
        assertFalse(RegistrationToken.isValidToken(token, ACCESS_KEY, null, TOKEN_DATE));
        assertFalse(RegistrationToken.isValidToken(token, ACCESS_KEY, SECRET_KEY, null));
    }
}
