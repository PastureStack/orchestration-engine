package io.cattle.platform.iaas.api.auth.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.cattle.platform.token.TokenException;
import io.cattle.platform.token.TokenService;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class IdentityProofVerifierTest {

    @Test
    public void validSignedClaimsBecomeAReplayProtectedProof() {
        IdentityProofVerifier verifier = new IdentityProofVerifier();
        verifier.setTokenService(new FixedTokenService(validClaims()));

        VerifiedIdentityProof proof = verifier.verify("signed");

        assertEquals("oidcconfig", proof.getProvider());
        assertEquals("oidc_user", proof.getExternalIdType());
        assertEquals("https://issuer.example|subject-1", proof.getExternalId());
        assertEquals("Alice", proof.getName());
        assertEquals("alice", proof.getLogin());
        assertNotNull(proof.getReplayKey());
        assertEquals(64, proof.getReplayKey().length());
    }

    @Test(expected = ClientVisibleException.class)
    public void wrongPurposeIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.put("purpose", "ordinary-login");
        IdentityProofVerifier verifier = new IdentityProofVerifier();
        verifier.setTokenService(new FixedTokenService(claims));
        verifier.verify("signed");
    }

    @Test(expected = ClientVisibleException.class)
    public void futureIssueTimeBeyondClockSkewIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.put("iat", System.currentTimeMillis() / 1000L + 61L);
        IdentityProofVerifier verifier = new IdentityProofVerifier();
        verifier.setTokenService(new FixedTokenService(claims));
        verifier.verify("signed");
    }

    @Test(expected = ClientVisibleException.class)
    public void tokenVerificationFailureIsRejected() {
        IdentityProofVerifier verifier = new IdentityProofVerifier();
        verifier.setTokenService(new FixedTokenService(null));
        verifier.verify("bad-signature");
    }

    private static Map<String, Object> validClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("purpose", "auth-identity-proof");
        claims.put("provider", "oidcconfig");
        claims.put("external_id_type", "oidc_user");
        claims.put("external_id", "https://issuer.example|subject-1");
        claims.put("name", "Alice");
        claims.put("login", "alice");
        claims.put("jti", "one-use-id");
        claims.put("iat", System.currentTimeMillis() / 1000L);
        claims.put("exp", System.currentTimeMillis() / 1000L + 300L);
        return claims;
    }

    private static final class FixedTokenService implements TokenService {
        private final Map<String, Object> claims;

        private FixedTokenService(Map<String, Object> claims) {
            this.claims = claims;
        }

        @Override
        public Map<String, Object> getJsonPayload(String token, boolean encrypted) throws TokenException {
            if (claims == null) {
                throw new TokenException("invalid");
            }
            return claims;
        }

        @Override
        public String generateToken(Map<String, Object> payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String generateToken(Map<String, Object> payload, Date expireDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String generateEncryptedToken(Map<String, Object> payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String generateEncryptedToken(Map<String, Object> payload, Date expireDate) {
            throw new UnsupportedOperationException();
        }
    }
}
