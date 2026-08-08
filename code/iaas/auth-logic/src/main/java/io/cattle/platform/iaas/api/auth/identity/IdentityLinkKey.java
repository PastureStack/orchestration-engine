package io.cattle.platform.iaas.api.auth.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * Produces the opaque, fixed-length lookup key used by internal login-identity
 * credentials.  Length-prefixing prevents delimiter ambiguity.  External
 * identifiers remain case-sensitive; only provider and identifier type are
 * normalized because those values are protocol labels.
 */
public final class IdentityLinkKey {

    private static final String VERSION = "v1";

    private IdentityLinkKey() {
    }

    public static String create(String provider, String externalIdType, String externalId) {
        if (StringUtils.isBlank(provider) || StringUtils.isBlank(externalIdType)
                || StringUtils.isBlank(externalId)) {
            throw new IllegalArgumentException("Provider, external identity type, and external identity are required");
        }

        String normalizedProvider = provider.trim().toLowerCase(Locale.ROOT);
        String normalizedType = externalIdType.trim().toLowerCase(Locale.ROOT);
        String value = externalId.trim();
        String canonical = VERSION
                + component(normalizedProvider)
                + component(normalizedType)
                + component(value);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(String.format(Locale.ROOT, "%02x", current & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String component(String value) {
        return ":" + value.length() + ":" + value;
    }
}
