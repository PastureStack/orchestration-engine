package io.cattle.platform.iaas.api.filter.ssl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import io.cattle.platform.core.model.Certificate;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.ssh.common.SslCertificateUtils;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class CertificateCreateValidationFilterTest {
    private static final String[] DERIVED = { "certFingerprint", "expiresAt", "CN", "issuer", "issuedAt",
            "version", "algorithm", "serialNumber", "keySize", "subjectAlternativeNames" };
    private static String pem;
    private static boolean addedProvider;

    @BeforeClass
    public static void createSyntheticPublicCertificate() throws Exception {
        addedProvider = Security.getProvider("BC") == null;
        if (addedProvider) Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=certificate-regression.invalid,O=Offline Test");
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                BigInteger.valueOf(42), new Date(1700000000000L), new Date(2000000000000L), name, pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, "certificate-regression.invalid")));
        X509CertificateHolder certificate = builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(pair.getPrivate()));
        StringWriter text = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(text)) {
            writer.writeObject(certificate);
        }
        pem = text.toString(); // Public synthetic certificate only; no private key is persisted.
    }

    @AfterClass
    public static void restoreProviderRegistration() {
        if (addedProvider) Security.removeProvider("BC");
    }

    @Test
    public void nameOnlyUpdateForwardsExactRequestWithoutCertificateMaterialOrDerivedFields() {
        assertOmittedCertForwarded("name", "renamed-certificate");
    }

    @Test
    public void descriptionOnlyUpdateForwardsExactRequestWithoutCertificateMaterialOrDerivedFields() {
        assertOmittedCertForwarded("description", "updated description");
    }

    @Test
    public void omittedCertificatePreservesAllExistingRequestFieldsAndMetadata() {
        Map<String, Object> body = materialAndMetadata();
        body.remove("cert");
        Map<String, Object> original = new LinkedHashMap<>(body);
        Map<String, Object> originalFields = new LinkedHashMap<>(fields(body));
        ApiRequest request = request(body);
        RecordingNext next = new RecordingNext("update", request);
        assertSame(next.result, new CertificateCreateValidationFilter().update("certificate", "1c42", request, next.manager));
        assertEquals(1, next.calls);
        assertSame(body, request.getRequestObject());
        assertEquals(original, body);
        assertEquals(originalFields, fields(body));
        assertFalse(body.containsKey("cert"));
    }

    @Test
    public void explicitNullCertificateUpdateStillRejectsBeforeNext() {
        assertInvalid(false, true, null);
    }

    @Test
    public void explicitEmptyAndWhitespaceCertificateUpdateStillRejectsBeforeNext() {
        assertInvalid(false, true, "");
        assertInvalid(false, true, " \r\n\t ");
    }

    @Test
    public void malformedCertificateUpdateStillRejectsBeforeNext() {
        assertInvalid(false, true, "not a PEM certificate");
        assertInvalid(false, true, "-----BEGIN CERTIFICATE-----\nYWJj\n-----END CERTIFICATE-----");
    }

    @Test
    public void validExplicitCertificateUpdateRecomputesAllDerivedFieldsAndPreservesOtherFields() throws Exception {
        assertValid(false);
    }

    @Test
    public void validCreateStillParsesAllDerivedFieldsBeforeNext() throws Exception {
        assertValid(true);
    }

    @Test
    public void createStillRejectsMissingNullEmptyWhitespaceAndMalformedCertificate() {
        assertInvalid(true, false, null);
        for (String value : new String[] { null, "", "  \n\t ", "not a PEM certificate" }) {
            assertInvalid(true, true, value);
        }
    }

    private static void assertOmittedCertForwarded(String key, String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, value);
        Map<String, Object> original = new LinkedHashMap<>(body);
        ApiRequest request = request(body);
        RecordingNext next = new RecordingNext("update", request);
        assertSame(next.result, new CertificateCreateValidationFilter().update("certificate", "1c42", request, next.manager));
        assertEquals(1, next.calls);
        assertSame(body, request.getRequestObject());
        assertEquals(original, body);
        for (String forbidden : Arrays.asList("cert", "key", "certChain", "data")) assertFalse(body.containsKey(forbidden));
    }

    private static void assertInvalid(boolean create, boolean present, String value) {
        Map<String, Object> body = materialAndMetadata();
        if (present) body.put("cert", value); else body.remove("cert");
        Map<String, Object> before = new LinkedHashMap<>(fields(body));
        ApiRequest request = request(body);
        RecordingNext next = new RecordingNext(create ? "create" : "update", request);
        try {
            CertificateCreateValidationFilter filter = new CertificateCreateValidationFilter();
            if (create) filter.create("certificate", request, next.manager);
            else filter.update("certificate", "1c42", request, next.manager);
            fail("Invalid or absent required certificate must not reach next");
        } catch (ClientVisibleException error) {
            assertEquals(ResponseCodes.UNPROCESSABLE_ENTITY, error.getStatus());
            assertEquals(ValidationErrorCodes.INVALID_FORMAT, error.getCode());
        }
        assertEquals(0, next.calls);
        assertEquals(before, fields(body));
        assertEquals("synthetic-key-sentinel", body.get("key"));
        assertEquals("synthetic-chain-sentinel", body.get("certChain"));
        assertEquals("keep-description", body.get("description"));
    }

    private static void assertValid(boolean create) throws Exception {
        Map<String, Object> body = materialAndMetadata();
        body.put("cert", pem);
        ApiRequest request = request(body);
        RecordingNext next = new RecordingNext(create ? "create" : "update", request);
        CertificateCreateValidationFilter filter = new CertificateCreateValidationFilter();
        Object actual = create ? filter.create("certificate", request, next.manager)
                : filter.update("certificate", "1c42", request, next.manager);
        assertSame(next.result, actual);
        assertEquals(1, next.calls);
        Certificate proxy = request.proxyRequestObject(Certificate.class);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("certFingerprint", SslCertificateUtils.getCertificateFingerprint(pem));
        expected.put("expiresAt", SslCertificateUtils.getExpirationDate(pem));
        expected.put("CN", "certificate-regression.invalid");
        expected.put("issuer", SslCertificateUtils.getIssuer(pem));
        expected.put("issuedAt", SslCertificateUtils.getIssuedDate(pem));
        expected.put("version", "3");
        expected.put("algorithm", SslCertificateUtils.getAlgorithm(pem));
        expected.put("serialNumber", "42");
        expected.put("keySize", 2048);
        expected.put("subjectAlternativeNames", Arrays.asList("certificate-regression.invalid"));
        expected.put("unrelated", Arrays.asList("keep", "nested metadata"));
        assertEquals(expected, DataUtils.getFields(proxy));
        assertEquals(pem, proxy.getCert());
        assertEquals("synthetic-key-sentinel", proxy.getKey());
        assertEquals("synthetic-chain-sentinel", proxy.getCertChain());
        assertEquals("keep-description", proxy.getDescription());
        assertEquals("keep-name", proxy.getName());
        assertEquals("keep-id", body.get("opaqueMarker"));
    }

    private static Map<String, Object> materialAndMetadata() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String name : DERIVED) fields.put(name, "old-" + name);
        fields.put("unrelated", Arrays.asList("keep", "nested metadata"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(DataUtils.FIELDS, fields);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "keep-name");
        body.put("description", "keep-description");
        body.put("key", "synthetic-key-sentinel");
        body.put("certChain", "synthetic-chain-sentinel");
        body.put("opaqueMarker", "keep-id");
        body.put("data", data);
        return body;
    }

    private static Map<String, Object> fields(Map<String, Object> body) {
        Map<String, Object> data = CollectionUtils.castMap(body.get("data"));
        return CollectionUtils.castMap(data.get(DataUtils.FIELDS));
    }

    private static ApiRequest request(Map<String, Object> body) {
        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(body);
        return request;
    }

    private static final class RecordingNext {
        final Object result = new Object();
        final ResourceManager manager;
        int calls;

        RecordingNext(String operation, ApiRequest request) {
            manager = ResourceManager.class.cast(Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                    new Class<?>[] { ResourceManager.class }, (proxy, method, arguments) -> {
                        assertEquals(operation, method.getName());
                        assertEquals("certificate", arguments[0]);
                        if ("update".equals(operation)) assertEquals("1c42", arguments[1]);
                        assertSame(request, arguments[arguments.length - 1]);
                        calls++;
                        return result;
                    }));
        }
    }
}
