package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.netflix.config.ConfigurationManager;
import com.sun.net.httpserver.HttpServer;
import io.cattle.platform.framework.secret.SecretsService;
import io.cattle.platform.framework.secret.SecretsServiceImpl;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.token.impl.RSAKeyProvider;
import io.cattle.platform.token.impl.RSAPrivateKeyHolder;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Test;

/** Real Java SecretsService HTTP/crypto against a synthetic source-derived Go
 * create/rewrap wire. It never uses a platform session or real secret material. */
public class AiCatalogLaunchPrivateWireTest {
    private static final int LIMIT = 512 * 1024;
    private final JacksonJsonMapper json = new JacksonJsonMapper();

    private Map<String, Object> plan() {
        return new LinkedHashMap<>(Map.of("id", "aicataloglaunch-11111111-1111-1111-1111-111111111111", "actorId", 42L,
                "accountId", 5L, "credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted"));
    }

    private AiCatalogLaunchPrivate vault(SecretsService secrets) {
        var vault = new AiCatalogLaunchPrivate(); vault.json = json; vault.secrets = secrets; return vault;
    }

    private void unavailable(AiCatalogLaunchPrivate vault, Map<String, Object> plan) {
        var error = assertThrows(ClientVisibleException.class, () -> vault.open(plan));
        assertEquals("CatalogLaunchUnavailable", error.getCode());
        assertEquals("CatalogLaunchUnavailable", error.getMessage()); assertNull(error.getCause());
    }

    private String envelope(Map<String, Object> plan, String padding) throws Exception {
        var binding = new LinkedHashMap<>(plan); binding.remove("privateEnvelope");
        return json.writeValueAsString(Map.of("version", 1, "binding", binding, "values", Map.of("padding", padding)));
    }

    @Test public void nativeCryptoHttpRoundTripOpensExactlyOneGoBase64JsonLayer() throws Exception {
        try (var backend = new NativeWire()) {
            var plan = plan(); var vault = vault(backend.client);
            var values = Map.<String, Object>of("humanAnswers", Map.of("password", "synthetic-private-answer-繁體中文－私密測試🧪"));
            plan.put("privateEnvelope", vault.seal(plan, values));
            String legacyWire = backend.client.decrypt(5L, (String) plan.get("privateEnvelope"));
            assertFalse(legacyWire.startsWith("{"));
            assertTrue(json.<Map<String, Object>>readValue(Base64.getDecoder().decode(legacyWire)).containsKey("binding"));
            assertEquals(values, vault.open(plan));
            System.out.println("LOOPBACK_TEST_ONLY nativeWireBase64=true realRsaOaepSha256=true realAesGcm=true binding=true");
        }
    }

    @Test public void nativeCryptoEnvelopeRejectsEveryPlanActorProjectAndOriginalKeyRebinding() throws Exception {
        try (var backend = new NativeWire()) {
            var plan = plan(); var vault = vault(backend.client);
            plan.put("privateEnvelope", vault.seal(plan, Map.of("padding", "synthetic")));
            for (var mutation : Map.of("id", "aicataloglaunch-22222222-2222-2222-2222-222222222222", "actorId", 99L,
                    "accountId", 88L, "credentialId", 8L, "credentialRevision", 4L, "credentialKind", "apiKey").entrySet()) {
                var other = new LinkedHashMap<>(plan); other.put(mutation.getKey(), mutation.getValue()); unavailable(vault, other);
            }
        }
    }

    @Test public void strictWireRejectsMalformedNoncanonicalNestedRawJsonAndUnknownEnvelope() throws Exception {
        var plan = plan(); String clear = envelope(plan, "synthetic");
        for (int padding = 1; clear.length() % 3 == 0; padding++) clear = envelope(plan, "synthetic" + "x".repeat(padding));
        String encoded = Base64.getEncoder().encodeToString(clear.getBytes(StandardCharsets.UTF_8));
        var wires = new java.util.ArrayList<String>(List.of("%%%", clear, encoded.replace("=", ""), encoded + "\n",
                Base64.getEncoder().encodeToString(encoded.getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString("not-json".getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString("{\"version\":2}".getBytes(StandardCharsets.UTF_8))));
        wires.add(null);
        for (String wire : wires) {
            var secrets = mock(SecretsService.class); when(secrets.decrypt(5L, "synthetic-cipher")).thenReturn(wire);
            plan.put("privateEnvelope", "synthetic-cipher"); unavailable(vault(secrets), plan);
        }
    }

    @Test public void decodedJsonLimitAllowsLargeNativeWireAndRejectsOneByteOverflow() throws Exception {
        try (var backend = new NativeWire()) {
            var plan = plan(); var vault = vault(backend.client);
            var values = Map.<String, Object>of("padding", "x".repeat(LIMIT - 1024));
            plan.put("privateEnvelope", vault.seal(plan, values));
            String wire = backend.client.decrypt(5L, (String) plan.get("privateEnvelope"));
            int decoded = Base64.getDecoder().decode(wire).length;
            assertTrue(decoded > 3 * LIMIT / 4 && decoded <= LIMIT); assertTrue(wire.length() > LIMIT);
            assertEquals(values, vault.open(plan));
            System.out.println("LOOPBACK_TEST_ONLY largeDecodedBytes=" + decoded + " base64WireChars=" + wire.length());
        }
        var plan = plan(); int fixed = envelope(plan, "").getBytes(StandardCharsets.UTF_8).length;
        String tooLarge = envelope(plan, "x".repeat(LIMIT + 1 - fixed));
        assertEquals(LIMIT + 1, tooLarge.getBytes(StandardCharsets.UTF_8).length);
        var secrets = mock(SecretsService.class);
        when(secrets.decrypt(5L, "synthetic-cipher")).thenReturn(Base64.getEncoder().encodeToString(tooLarge.getBytes(StandardCharsets.UTF_8)));
        plan.put("privateEnvelope", "synthetic-cipher"); unavailable(vault(secrets), plan);
    }

    private final class NativeWire implements AutoCloseable {
        final HttpServer server;
        final SecretsServiceImpl client = new SecretsServiceImpl();
        final SecureRandom random = new SecureRandom();
        final byte[] backendKey = random(32);
        final Object previousUrl = ConfigurationManager.getConfigInstance().getProperty("secrets.url");

        NativeWire() throws Exception {
            if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
            KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
            var provider = mock(RSAKeyProvider.class);
            when(provider.getPrivateKey()).thenReturn(new RSAPrivateKeyHolder("synthetic", (RSAPrivateKey) pair.getPrivate()));
            when(provider.getPublicKeys()).thenReturn(Map.of("synthetic", pair.getPublic()));
            for (var field : Map.of("jsonMapper", json, "rsaKeyProvider", provider).entrySet()) {
                var target = SecretsServiceImpl.class.getDeclaredField(field.getKey()); target.setAccessible(true); target.set(client, field.getValue());
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1-secrets/secrets/", exchange -> {
                byte[] response; int status = 200;
                try {
                    if (!"POST".equals(exchange.getRequestMethod())) throw new IllegalArgumentException();
                    Map<String, Object> input = json.readValue(exchange.getRequestBody().readNBytes(2 * 1024 * 1024));
                    String action = exchange.getRequestURI().getPath();
                    Map<String, Object> output;
                    if (action.endsWith("/create")) {
                        String wire = (String) input.get("clearText");
                        try { Base64.getDecoder().decode(wire); }
                        catch (IllegalArgumentException plain) { wire = Base64.getEncoder().encodeToString(wire.getBytes(StandardCharsets.UTF_8)); }
                        String cipher = encrypt(backendKey, wire);
                        output = Map.of("backend", "localkey", "keyName", "synthetic", "cipherText", cipher, "signature", sign(wire));
                    } else if (action.endsWith("/rewrap")) {
                        String wire = decrypt(backendKey, (String) input.get("cipherText"));
                        if (!MessageDigest.isEqual(sign(wire).getBytes(StandardCharsets.US_ASCII),
                                ((String) input.get("signature")).getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException();
                        String publicPem = ((String) input.get("rewrapKey")).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
                        var publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(
                                new X509EncodedKeySpec(Base64.getDecoder().decode(publicPem)));
                        byte[] key = random(32);
                        var rsa = Cipher.getInstance("RSA/NONE/OAEPWithSHA256AndMGF1Padding", "BC");
                        rsa.init(Cipher.ENCRYPT_MODE, publicKey,
                                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
                        var keyDto = Map.of("encryptionAlgorithm", "PKCS1_OAEP", "encryptedText", Base64.getEncoder().encodeToString(rsa.doFinal(key)),
                                "hashAlgorithm", "sha256");
                        var dto = Map.of("encryptionAlgorithm", "aes256-gcm96", "encryptedText", encrypt(key, wire), "encryptedKey", keyDto);
                        output = Map.of("rewrapText", Base64.getEncoder().encodeToString(json.writeValueAsString(dto).getBytes(StandardCharsets.UTF_8)));
                    } else throw new IllegalArgumentException();
                    response = json.writeValueAsString(output).getBytes(StandardCharsets.UTF_8);
                } catch (Exception unavailable) { status = 503; response = "{}".getBytes(StandardCharsets.UTF_8); }
                exchange.sendResponseHeaders(status, response.length); exchange.getResponseBody().write(response); exchange.close();
            });
            server.start();
            ConfigurationManager.getConfigInstance().setProperty("secrets.url", "http://127.0.0.1:" + server.getAddress().getPort());
        }

        byte[] random(int length) { byte[] result = new byte[length]; random.nextBytes(result); return result; }

        String sign(String wire) throws Exception {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(backendKey, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(wire.getBytes(StandardCharsets.UTF_8)));
        }

        String encrypt(byte[] key, String wire) throws Exception {
            byte[] nonce = random(12); var aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return json.writeValueAsString(Map.of("Nonce", Base64.getEncoder().encodeToString(nonce), "Algorithm", "aes256-gcm",
                    "CipherText", Base64.getEncoder().encodeToString(aes.doFinal(wire.getBytes(StandardCharsets.UTF_8)))));
        }

        String decrypt(byte[] key, String cipher) throws Exception {
            Map<String, Object> object = json.readValue(cipher); var aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode((String) object.get("Nonce"))));
            return new String(aes.doFinal(Base64.getDecoder().decode((String) object.get("CipherText"))), StandardCharsets.UTF_8);
        }

        @Override public void close() {
            server.stop(0);
            if (previousUrl == null) ConfigurationManager.getConfigInstance().clearProperty("secrets.url");
            else ConfigurationManager.getConfigInstance().setProperty("secrets.url", previousUrl);
        }
    }
}
