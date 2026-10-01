package io.cattle.platform.iaas.api.auth.mfa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;

public class WebAuthnConfigurationTest {

    private final WebAuthnService service = new WebAuthnService();

    @Test
    public void acceptsExactHttpsOriginAndRelyingParty() {
        service.validateConfiguration(policy("console.example.test", "https://console.example.test"));
        service.validateConfiguration(policy("example.test", "https://console.example.test:8443"));
    }

    @Test
    public void acceptsHttpOnlyForLoopbackDevelopment() {
        service.validateConfiguration(policy("localhost", "http://localhost:8080"));
        service.validateConfiguration(policy("127.0.0.1", "http://127.0.0.1:8080"));
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsInsecureLanOrigin() {
        service.validateConfiguration(policy("192.0.2.10", "http://192.0.2.10:8080"));
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsUnrelatedRelyingParty() {
        service.validateConfiguration(policy("other.example.test", "https://console.example.test"));
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsPublicSuffixAsRelyingParty() {
        service.validateConfiguration(policy("co.uk", "https://console.co.uk"));
    }

    @Test
    public void objectConverterJsonRoundTripsNormalWebAuthnValues() {
        ObjectConverter converter = new ObjectConverter();
        Map<String, Object> fixture = converterFixture();

        String json = converter.getJsonConverter().writeValueAsString(fixture);
        Map<?, ?> restored = converter.getJsonConverter().readValue(json, Map.class);

        assertEquals(fixture, restored);
    }

    @Test
    public void objectConverterCborRoundTripsNormalValuesAndBinaryData() {
        ObjectConverter converter = new ObjectConverter();
        Map<String, Object> fixture = converterFixture();
        byte[] binary = new byte[] { 0, 1, 127, (byte)128, (byte)255 };
        fixture.put("binary", binary);

        byte[] cbor = converter.getCborConverter().writeValueAsBytes(fixture);
        Map<?, ?> restored = converter.getCborConverter().readValue(cbor, Map.class);

        assertEquals(fixture.size(), restored.size());
        assertEquals(fixture.get("name"), restored.get("name"));
        assertEquals(fixture.get("counter"), restored.get("counter"));
        assertEquals(fixture.get("transports"), restored.get("transports"));
        assertEquals(fixture.get("verified"), restored.get("verified"));
        assertArrayEquals(binary, (byte[])restored.get("binary"));
    }

    @Test
    public void credentialDataStorageRoundTripPreservesPublicKeyAndCredentialId() throws Exception {
        // Exercise the same CBOR converter and base64url storage representation as WebAuthnService.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        EC2COSEKey publicKey = EC2COSEKey.create(
                (ECPublicKey)generator.generateKeyPair().getPublic(), COSEAlgorithmIdentifier.ES256);
        byte[] credentialId = new byte[] { 0, 1, 2, 127, (byte)128, (byte)255 };
        AttestedCredentialData fixture = new AttestedCredentialData(AAGUID.ZERO, credentialId, publicKey);
        AttestedCredentialDataConverter converter = new AttestedCredentialDataConverter(new ObjectConverter());

        byte[] original = converter.convert(fixture);
        String stored = Base64.getUrlEncoder().withoutPadding().encodeToString(original);
        AttestedCredentialData restored = converter.convert(Base64.getUrlDecoder().decode(stored));

        assertEquals(fixture, restored);
        assertArrayEquals(credentialId, restored.getCredentialId());
        assertEquals(AAGUID.ZERO, restored.getAaguid());
        assertEquals(COSEAlgorithmIdentifier.ES256, restored.getCOSEKey().getAlgorithm());
        assertTrue(restored.getCOSEKey().hasPublicKey());
        assertFalse(restored.getCOSEKey().hasPrivateKey());
        assertArrayEquals(publicKey.getPublicKey().getEncoded(), restored.getCOSEKey().getPublicKey().getEncoded());
        assertArrayEquals(original, converter.convert(restored));
    }

    private Map<String, Object> converterFixture() {
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("name", "測試憑證／✓");
        fixture.put("counter", 7);
        fixture.put("transports", Arrays.asList("internal", "usb"));
        fixture.put("verified", true);
        return fixture;
    }

    private MfaPolicy policy(String rpId, String origin) {
        return new MfaPolicy("optional", 5, rpId, origin, "PastureStack", "PastureStack");
    }
}
