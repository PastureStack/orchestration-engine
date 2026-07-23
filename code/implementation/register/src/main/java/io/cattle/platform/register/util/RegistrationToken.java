package io.cattle.platform.register.util;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;


public class RegistrationToken {

    public static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";
    public static final String HMAC_SHA1_ALGORITHM = "HmacSHA1";
    private static final ConfigProperty<Long> TOKEN_PERIOD = ArchaiusUtil.getLongProperty("registration.token.period.millis");

    public static long getAllowedTime() {
        return TOKEN_PERIOD.get();
    }

    public static final String createToken(String accessKey, String secretKey) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.DAY_OF_YEAR, 0);

        return createToken(accessKey, secretKey, cal.getTime());
    }

    public static final String createToken(String accessKey, String secretKey, Date date) {
        return createToken(accessKey, secretKey, date, HMAC_SHA256_ALGORITHM);
    }

    public static final String createLegacyToken(String accessKey, String secretKey, Date date) {
        return createToken(accessKey, secretKey, date, HMAC_SHA1_ALGORITHM);
    }

    public static boolean isValidToken(String token, String accessKey, String secretKey, Date date) {
        if (token == null || accessKey == null || secretKey == null || date == null) {
            return false;
        }

        return constantTimeEquals(token, createToken(accessKey, secretKey, date))
                || constantTimeEquals(token, createLegacyToken(accessKey, secretKey, date));
    }

    private static final String createToken(String accessKey, String secretKey, Date date, String algorithm) {
        String prefix = String.format("%s:%d", accessKey, date.getTime());

        try {
            SecretKeySpec signingKey = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), algorithm);
            Mac mac = Mac.getInstance(algorithm);
            mac.init(signingKey);

            String signature = Base64.encodeBase64String(mac.doFinal(prefix.getBytes(StandardCharsets.UTF_8))).replaceAll("[/=+]", "");

            return String.format("%s:%s", prefix, signature);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("Failed to generate signature key for [" + prefix + "]", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to generate signature key for [" + prefix + "]", e);
        }
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
