package io.cattle.platform.iaas.api.auth.mfa;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.lang3.StringUtils;

/**
 * RFC 6238 TOTP using the broadly interoperable SHA-1 / 30 second profile.
 *
 * Secrets are generated at 160 bits. Verification accepts the immediately
 * adjacent time steps for clock drift and returns the accepted step so callers
 * can reject replay of an already-used code.
 */
public class TotpService {

    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    private static final int SECRET_BYTES = 20;
    private static final int WINDOW = 1;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base32 base32 = new Base32();

    public String generateSecret() {
        byte[] value = new byte[SECRET_BYTES];
        secureRandom.nextBytes(value);
        return base32.encodeToString(value).replace("=", "").toUpperCase(Locale.ROOT);
    }

    public long matchStep(String encodedSecret, String code, long epochSeconds, long lastUsedStep) {
        if (StringUtils.isBlank(encodedSecret) || code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return -1L;
        }
        byte[] secret;
        try {
            secret = base32.decode(encodedSecret);
        } catch (RuntimeException e) {
            return -1L;
        }
        long currentStep = epochSeconds / PERIOD_SECONDS;
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            long candidateStep = currentStep + offset;
            if (candidateStep <= lastUsedStep || candidateStep < 0) {
                continue;
            }
            String candidate = calculate(secret, candidateStep, DIGITS);
            if (MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return candidateStep;
            }
        }
        return -1L;
    }

    public String provisioningUri(String issuer, String accountLabel, String encodedSecret) {
        String safeIssuer = StringUtils.defaultIfBlank(issuer, "PastureStack");
        String safeAccount = StringUtils.defaultIfBlank(accountLabel, "account");
        return "otpauth://totp/" + encode(safeIssuer + ":" + safeAccount)
                + "?secret=" + encodedSecret
                + "&issuer=" + encode(safeIssuer)
                + "&algorithm=SHA1&digits=" + DIGITS
                + "&period=" + PERIOD_SECONDS;
    }

    static String calculate(byte[] secret, long counter, int digits) {
        if (secret == null || secret.length == 0 || digits < 6 || digits > 8) {
            throw new IllegalArgumentException("A secret and 6 to 8 digits are required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            int divisor = 1;
            for (int i = 0; i < digits; i++) {
                divisor *= 10;
            }
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % divisor);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The runtime does not support HmacSHA1", e);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
