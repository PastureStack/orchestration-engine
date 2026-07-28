package io.cattle.platform.iaas.api.auth.mfa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.apache.commons.codec.binary.Base32;
import org.junit.Test;

public class TotpServiceTest {

    private static final byte[] RFC_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void matchesRfc6238Sha1Vectors() {
        assertEquals("94287082", TotpService.calculate(RFC_SECRET, 59L / 30L, 8));
        assertEquals("07081804", TotpService.calculate(RFC_SECRET, 1111111109L / 30L, 8));
        assertEquals("14050471", TotpService.calculate(RFC_SECRET, 1111111111L / 30L, 8));
        assertEquals("89005924", TotpService.calculate(RFC_SECRET, 1234567890L / 30L, 8));
        assertEquals("69279037", TotpService.calculate(RFC_SECRET, 2000000000L / 30L, 8));
        assertEquals("65353130", TotpService.calculate(RFC_SECRET, 20000000000L / 30L, 8));
    }

    @Test
    public void acceptsClockDriftButRejectsReplay() {
        TotpService service = new TotpService();
        String encoded = new Base32().encodeToString(RFC_SECRET);
        long currentStep = 2000000000L / 30L;
        String code = TotpService.calculate(RFC_SECRET, currentStep - 1, TotpService.DIGITS);

        assertEquals(currentStep - 1, service.matchStep(encoded, code, 2000000000L, -1L));
        assertEquals(-1L, service.matchStep(encoded, code, 2000000000L, currentStep - 1));
    }

    @Test
    public void generatesInteroperableProvisioningData() {
        TotpService service = new TotpService();
        String secret = service.generateSecret();

        assertTrue(secret.matches("[A-Z2-7]{32}"));
        assertEquals(
                "otpauth://totp/PastureStack%3Aadmin%20account?secret=" + secret
                        + "&issuer=PastureStack&algorithm=SHA1&digits=6&period=30",
                service.provisioningUri("PastureStack", "admin account", secret));
    }
}
