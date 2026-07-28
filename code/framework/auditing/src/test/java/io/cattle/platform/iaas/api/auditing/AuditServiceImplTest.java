package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import io.cattle.platform.api.auth.Identity;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class AuditServiceImplTest {

    @Test
    public void internalServiceIdentityWithoutExternalTypeDoesNotBreakAuditing() {
        AuditServiceImpl service = service();
        Identity internal = new Identity(null, "internal-service");
        Set<Identity> identities = new LinkedHashSet<Identity>();
        identities.add(internal);

        assertSame(internal, service.auditIdentity(identities));

        Identity user = new Identity("oidc_user", "issuer|subject");
        identities.add(user);
        assertSame(user, service.auditIdentity(identities));
    }

    @Test
    public void auditObjectMapUsesCheckedStringKeys() {
        AuditServiceImpl service = service();
        Map<Object, Object> input = new HashMap<Object, Object>();
        input.put("name", "service-a");

        Map<String, Object> result = service.auditObjectMap(input);

        assertEquals("service-a", result.get("name"));
    }

    @Test
    public void auditObjectMapPreservesJacksonKeyNormalization() {
        AuditServiceImpl service = service();
        Map<Object, Object> input = new HashMap<Object, Object>();
        input.put(Integer.valueOf(1), "service-a");

        Map<String, Object> result = service.auditObjectMap(input);

        assertEquals("service-a", result.get("1"));
    }

    @Test
    public void authenticationSecretsNeverEnterAuditPayloads() {
        AuditServiceImpl service = service();
        Map<String, Object> input = new HashMap<String, Object>();
        String[] sensitive = new String[] {
                "password", "identityProof", "providerSwitchCode", "localPassword",
                "mfaCode", "recoveryCode", "verificationCode", "webAuthnResponse",
                "challengeId", "totpSecret", "totpProvisioningUri", "recoveryCodes",
                "publicKey", "smtpPassword", "emailCode", "email", "testRecipient"
        };
        for (String field : sensitive) {
            input.put(field, "must-not-be-recorded");
        }
        input.put("operation", "revokeFactor");

        Map<String, Object> result = service.sanitizedAuditObject(input, "mfaOperation");

        assertEquals("revokeFactor", result.get("operation"));
        for (String field : sensitive) {
            assertFalse(field + " was retained", result.containsKey(field));
        }
    }

    protected AuditServiceImpl service() {
        JacksonMapper mapper = new JacksonMapper();
        mapper.init();

        AuditServiceImpl service = new AuditServiceImpl();
        service.jsonMapper = mapper;
        return service;
    }
}
