package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
                "publicKey", "smtpPassword", "emailCode", "email", "testRecipient", "securityConfirmation", "purposeDigest",
                "token", "backendToken"
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

    @Test
    public void nestedAuthenticationCarriersAndEnvironmentValuesAreSanitizedWithoutChangingInput() {
        AuditServiceImpl service = service();
        Map<String, Object> input = new HashMap<>();
        Map<String, Object> nested = new HashMap<>();
        String[] sensitive = { "PassWord", "access_token", "REFRESH-TOKEN", "idToken", "JWT", "OTP",
                "authorization-code", "code", "Cookie", "AUTHORIZATION", "secret_key", "client-secret",
                "identity_proof", "security-confirmation", "web_authn_response", "backend_token" };
        for (String field : sensitive) nested.put(field, "private-" + field);
        nested.put("name", "worker");
        nested.put("scale", 3);
        nested.put("environment", Map.of("DATABASE_URL", "private-database-url", "MODE", "private-mode"));
        nested.put("dockerConfig", Map.of("value", "private-config"));
        nested.put("secondaryLaunchConfigs", List.of(Map.of("imageUuid", "docker:alpine", "password", "private-secondary")));
        input.put("metadata", List.of(nested));
        String before = service.auditObjectMap(input).toString();

        Map<String, Object> result = service.sanitizedAuditObject(input, "service");

        String json = result.toString();
        assertFalse(json, json.contains("private-"));
        assertTrue(json, json.contains("worker"));
        assertTrue(json, json.contains("scale=3"));
        assertTrue(json, json.contains("docker:alpine"));
        assertTrue(json, json.contains("DATABASE_URL"));
        assertEquals(before, service.auditObjectMap(input).toString());
    }

    @Test
    public void secretValuesAreRemovedOnlyInSecretResourceContexts() {
        AuditServiceImpl service = service();
        assertFalse(service.sanitizedAuditObject(Map.of("value", "private-root"), "SeCrEt").containsKey("value"));
        Map<String, Object> input = Map.of("children", List.of(
                Map.of("TYPE", "SECRET", "name", "secret-name", "value", "private-nested"),
                Map.of("type", "setting", "name", "setting-name", "value", "safe-setting")));

        String result = service.sanitizedAuditObject(input, "service").toString();

        assertFalse(result, result.contains("private-"));
        assertTrue(result, result.contains("safe-setting"));
        assertEquals("safe-setting", service.sanitizedAuditObject(Map.of("value", "safe-setting"), "setting").get("value"));
    }

    @Test
    public void hostTemplateSecretValuesCannotLeakArbitraryDriverFields() {
        AuditServiceImpl service = service();
        Map<String, Object> input = Map.of("name", "template-a", "secretValues",
                Map.of("AWS_SECRET_ACCESS_KEY", "private-driver-key", "customDriverOption", "private-custom"),
                "children", List.of(Map.of("SECRET-VALUES", Map.of("arbitrary", "private-nested"))));

        String result = service.sanitizedAuditObject(input, "hostTemplate").toString();

        assertFalse(result, result.contains("private-"));
        assertFalse(result, result.contains("AWS_SECRET_ACCESS_KEY"));
        assertTrue(result, result.contains("template-a"));
        assertTrue(input.toString().contains("private-driver-key"));
    }

    @Test
    public void certificateNativeKeyAndPreviousStackEnvironmentAreSanitized() {
        AuditServiceImpl service = service();
        Map<String, Object> certificate = Map.of("name", "certificate-a", "key", "private-pem",
                "cert", "public-certificate");
        String direct = service.sanitizedAuditObject(certificate, "certificate").toString();
        String nested = service.sanitizedAuditObject(Map.of("children", List.of(
                Map.of("type", "certificate", "key", "private-nested-pem", "cert", "public-certificate"))), "service").toString();
        String stack = service.sanitizedAuditObject(Map.of("previousEnvironment",
                Map.of("DB_PASS", "private-stack-environment"), "name", "stack-a"), "stack").toString();

        assertFalse(direct, direct.contains("private-"));
        assertTrue(direct, direct.contains("public-certificate"));
        assertFalse(nested, nested.contains("private-"));
        assertFalse(stack, stack.contains("private-"));
        assertTrue(stack, stack.contains("DB_PASS"));
        assertEquals("safe-label-key", service.sanitizedAuditObject(Map.of("key", "safe-label-key"), "label").get("key"));
        assertEquals("private-pem", certificate.get("key"));
    }

    @Test
    public void composeTextCannotBypassEnvironmentValueSanitization() {
        AuditServiceImpl service = service();
        Map<String, Object> input = Map.of("name", "stack-a", "dockerCompose",
                "services:\n  db:\n    environment:\n      CUSTOM: private-compose-canary", "rancherCompose", "private-rancher-compose");

        String result = service.sanitizedAuditObject(input, "stack").toString();

        assertFalse(result, result.contains("private-"));
        assertTrue(result, result.contains("stack-a"));
        assertTrue(input.toString().contains("private-compose-canary"));
    }

    @Test
    public void ordinaryRequestAndResourceResponseReachDaoWithoutSensitiveValues() throws Exception {
        AuditServiceImpl service = service();
        List<Object[]> writes = new ArrayList<>();
        service.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(),
                new Class<?>[] { AuditLogDao.class }, (proxy, method, args) -> {
                    writes.add(args);
                    return null;
                });
        Policy policy = (Policy) Proxy.newProxyInstance(Policy.class.getClassLoader(), new Class<?>[] { Policy.class },
                (proxy, method, args) -> {
                    if ("getAccountId".equals(method.getName())) return 1L;
                    if ("getAuthenticatedAsAccountId".equals(method.getName())) return 2L;
                    return null;
                });
        Map<String, Object> requestBody = Map.of("name", "service-a", "scale", 2, "metadata", List.of(
                Map.of("Authorization", "private-request-canary")),
                "environment", Map.of("CUSTOM", "private-request-environment"),
                "secretValues", Map.of("arbitraryDriverOption", "private-request-driver-option"));
        Map<String, Object> responseBody = Map.of("name", "service-a", "scale", 2, "imageUuid", "docker:alpine",
                "metadata", Map.of("refresh_token", "private-response-canary"),
                "secondaryLaunchConfigs", List.of(Map.of("environment", Map.of("CUSTOM", "private-response-environment"))),
                "secretValues", Map.of("arbitraryDriverOption", "private-response-driver-option"));
        ResourceImpl response = new ResourceImpl("42", "service", responseBody) {
            @Override
            public Map<String, URL> getLinks() { return Map.of(); }
        };
        ApiRequest request = new ApiRequest(null, null);
        request.setType("service"); request.setMethod("POST"); request.setResponseCode(201);
        request.setRequestObject(requestBody); request.setResponseObject(response);
        request.setAttribute("requestStartTime", 10L); request.setAttribute("requestEndTime", 25L);
        request.setAttribute("authType", "TokenAuth");

        service.logRequest(request, policy);

        assertEquals(1, writes.size());
        Object[] write = writes.getFirst();
        assertEquals("service", write[0]); assertEquals(42L, write[1]);
        assertEquals(1L, write[4]); assertEquals(2L, write[5]);
        assertEquals("api.service.create", write[6]); assertEquals("TokenAuth", write[7]); assertEquals(15L, write[8]);
        Map<?, ?> data = (Map<?, ?>) write[2];
        assertEquals(201, data.get("responseCode"));
        String delivered = data.toString();
        assertFalse(delivered, delivered.contains("private-"));
        assertTrue(delivered, delivered.contains("service-a"));
        assertTrue(delivered, delivered.contains("docker:alpine"));
        assertTrue(requestBody.toString().contains("private-request-canary"));
        assertTrue(responseBody.toString().contains("private-response-canary"));
        assertTrue(data.get("requestObject") instanceof String);
        assertTrue(data.get("responseObject") instanceof String);
    }

    protected AuditServiceImpl service() {
        JacksonMapper mapper = new JacksonMapper();
        mapper.init();

        AuditServiceImpl service = new AuditServiceImpl();
        service.jsonMapper = mapper;
        return service;
    }
}
