package io.cattle.platform.token.impl;

import static org.junit.Assert.*;

import io.cattle.platform.token.CertSet;
import io.cattle.platform.token.TokenException;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.util.Date;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.nimbusds.jose.JOSEObject;

public class JwtTokenServiceImplTest {

    JwtTokenServiceImpl impl;

    @Before
    public void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair keyPair = generator.generateKeyPair();

        impl = new JwtTokenServiceImpl();
        impl.setKeyProvider(new RSAKeyProvider() {

            KeyFactory kf;

            @Override
            public RSAPrivateKeyHolder getPrivateKey() {

                try {
                    kf = KeyFactory.getInstance("RSA");
                    return new RSAPrivateKeyHolder("abc", (RSAPrivateKey) keyPair.getPrivate());
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public Map<String, PublicKey> getPublicKeys() {
                return null;
            }

            @Override
            public PublicKey getDefaultPublicKey() {
                try {
                    kf = KeyFactory.getInstance("RSA");
                    return keyPair.getPublic();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public CertSet generateCertificate(String subject, String... sans) throws Exception {
                return null;
            }

            @Override
            public Certificate getCACertificate() {
                return null;
            }

            @Override
            public byte[] toBytes(Certificate cert) throws IOException {
                return null;
            }
        });

    }

    @Test
    public void testSignedToken() throws TokenException {
        Map<String, Object> payload = impl.getJsonPayload(
                impl.generateToken(null, new Date(1413936626719L), null, false), false);
        assertEquals(payload.get("exp"), null);
        assertEquals(payload.get("iat"), Long.valueOf(1413936626));
    }

    @Test(expected = TokenException.class)
    public void testCheckExpiry() throws TokenException {
        String expiredToken = impl.generateToken(null, new Date(1413936626719L), new Date(1413936626719L), false);
        impl.getJsonPayload(expiredToken, false);
    }

    @Test
    public void testEncryptedTokenUsesDefaultJweAlgorithm() throws ParseException {
        String newEncryptedToken = impl.generateToken(null, new Date(1413936626719L), new Date(1923109200000L), true);
        JOSEObject token = JOSEObject.parse(newEncryptedToken);
        assertEquals("RSA-OAEP", token.getHeader().getAlgorithm().getName());
    }

    @Test
    public void testDecryptsEncryptedToken() throws TokenException {
        String newEncryptedToken = impl.generateToken(null, new Date(1413936626719L), new Date(1923109200000L), true);
        Map<String, Object> decrypted = impl.getJsonPayload(newEncryptedToken, true);
        assertEquals(decrypted.get("exp"), Long.valueOf(1923109200));
        assertEquals(decrypted.get("iat"), Long.valueOf(1413936626));
    }
}
