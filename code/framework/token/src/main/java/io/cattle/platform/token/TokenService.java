package io.cattle.platform.token;

import java.util.Date;
import java.util.Map;

public interface TokenService {

    String generateToken(Map<String, Object> payload);

    String generateToken(Map<String, Object> payload, Date expireDate);

    String generateEncryptedToken(Map<String, Object> payload);

    String generateEncryptedToken(Map<String, Object> payload, Date expireDate);

    Map<String, Object> getJsonPayload(String token, boolean encrypted) throws TokenException;

    /** Audit evidence only. NEVER use this expiry-ignoring result to authorize work. */
    default Map<String, Object> getAuditSignaturePayload(String token) throws TokenException {
        throw new TokenException("Signature-only audit verification is unavailable");
    }

}
