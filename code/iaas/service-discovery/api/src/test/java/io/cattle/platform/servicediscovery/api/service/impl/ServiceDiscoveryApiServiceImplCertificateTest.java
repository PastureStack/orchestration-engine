package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.constants.NetworkConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.token.CertSet;
import io.cattle.platform.token.impl.RSAKeyProvider;
import io.cattle.platform.token.impl.RSAPrivateKeyHolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;
import org.junit.Test;

public class ServiceDiscoveryApiServiceImplCertificateTest {

    @Test
    public void generateServiceCombinesConfiguredAndInternalSans() throws Exception {
        ServiceRecord service = new ServiceRecord();
        service.setName("Web");
        service.setData(dataWithMetadata("custom.internal"));
        StackRecord stack = new StackRecord();
        stack.setName("Prod");
        RecordingKeyProvider keyProvider = new RecordingKeyProvider();
        ServiceDiscoveryApiServiceImpl api = new ServiceDiscoveryApiServiceImpl();
        api.jsonMapper = new JacksonJsonMapper();
        api.keyProvider = keyProvider;

        String encoded = api.generateService(service, stack);

        assertEquals("Web", keyProvider.subject);
        assertArrayEquals(new String[] {
                "custom.internal",
                "web",
                "web.prod",
                "web.prod." + NetworkConstants.INTERNAL_DNS_SEARCH_DOMAIN
        }, keyProvider.sans);
        assertEquals(Base64.encodeBase64String("zip".getBytes(StandardCharsets.UTF_8)), encoded);
    }

    private static Map<String, Object> dataWithMetadata(String san) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sans", Arrays.asList(san));
        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_METADATA, metadata);
        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);
        return data;
    }

    private static final class RecordingKeyProvider implements RSAKeyProvider {
        String subject;
        String[] sans;

        @Override
        public RSAPrivateKeyHolder getPrivateKey() {
            return null;
        }

        @Override
        public Map<String, PublicKey> getPublicKeys() {
            return Collections.emptyMap();
        }

        @Override
        public PublicKey getDefaultPublicKey() {
            return null;
        }

        @Override
        public CertSet generateCertificate(String subject, String... sans) {
            this.subject = subject;
            this.sans = sans;
            return new CertSet(null, null, null) {
                @Override
                public void writeZip(OutputStream output) throws IOException {
                    output.write("zip".getBytes(StandardCharsets.UTF_8));
                }
            };
        }

        @Override
        public Certificate getCACertificate() {
            return null;
        }

        @Override
        public byte[] toBytes(Certificate cert) {
            return new byte[0];
        }
    }
}
