package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.token.TokenException;
import io.cattle.platform.token.TokenService;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;

public class IdentityProofVerifier {

    private static final long MAX_CLOCK_SKEW_SECONDS = 60;
    private static final String PURPOSE = "auth-identity-proof";

    @Inject
    TokenService tokenService;

    public VerifiedIdentityProof verify(String proof) {
        if (StringUtils.isBlank(proof)) {
            throw invalidProof("A verified identity proof is required.");
        }

        final Map<String, Object> claims;
        try {
            claims = tokenService.getJsonPayload(proof, false);
        } catch (TokenException e) {
            throw invalidProof("The verified identity proof is invalid or expired.");
        }

        String purpose = stringClaim(claims, "purpose");
        String provider = stringClaim(claims, "provider");
        String externalIdType = stringClaim(claims, "external_id_type");
        String externalId = stringClaim(claims, "external_id");
        String name = optionalStringClaim(claims, "name");
        String login = optionalStringClaim(claims, "login");
        String jti = stringClaim(claims, "jti");
        long issuedAt = numberClaim(claims, "iat");

        long now = System.currentTimeMillis() / 1000L;
        if (!PURPOSE.equals(purpose) || issuedAt > now + MAX_CLOCK_SKEW_SECONDS) {
            throw invalidProof("The verified identity proof has an invalid purpose or issue time.");
        }

        String replayKey = IdentityLinkKey.create("identity-proof", "jti", jti);
        return new VerifiedIdentityProof(provider, externalIdType, externalId, name, login, replayKey);
    }

    private String stringClaim(Map<String, Object> claims, String key) {
        String value = optionalStringClaim(claims, key);
        if (StringUtils.isBlank(value)) {
            throw invalidProof("The verified identity proof is missing claim: " + key);
        }
        return value;
    }

    private String optionalStringClaim(Map<String, Object> claims, String key) {
        Object value = claims == null ? null : claims.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private long numberClaim(Map<String, Object> claims, String key) {
        Object value = claims == null ? null : claims.get(key);
        if (!(value instanceof Number)) {
            throw invalidProof("The verified identity proof is missing numeric claim: " + key);
        }
        return ((Number) value).longValue();
    }

    private ClientVisibleException invalidProof(String message) {
        return new ClientVisibleException(ResponseCodes.UNAUTHORIZED, "InvalidIdentityProof", message, null);
    }

    void setTokenService(TokenService tokenService) {
        this.tokenService = tokenService;
    }
}
