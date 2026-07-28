package io.cattle.platform.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class MfaSettingsSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void exposesGlobalSettingsAsAnUpdatableSingleton() throws Exception {
        JsonNode schema = schema();

        assertEquals(new HashSet<String>(Arrays.asList("GET")),
                textValues(schema.path("collectionMethods")));
        assertEquals(new HashSet<String>(Arrays.asList("GET", "PUT")),
                textValues(schema.path("resourceMethods")));

        JsonNode fields = schema.path("resourceFields");
        for (String name : Arrays.asList(
                "enforcement", "passkeyLimit", "relyingPartyId", "origin",
                "relyingPartyName", "issuer", "maximumFailedAttempts",
                "lockoutSeconds", "securityConfirmationTtlSeconds",
                "federatedMfaMode", "trustedAuthenticationMethods",
                "trustedAuthenticationContexts",
                "maximumFederatedAuthenticationAgeSeconds",
                "passkeyCounterPolicy", "securityEmailLocale",
                "securityConfirmation", "smtpEnabled", "smtpHost", "smtpPort",
                "smtpUsername", "smtpPassword", "smtpClearPassword", "smtpFrom",
                "smtpStartTls", "smtpSsl", "smtpConnectionTimeoutMillis",
                "smtpReadTimeoutMillis", "emailCodeTtlSeconds", "sendTestEmail",
                "testRecipient")) {
            assertWritable(fields, name);
        }
    }

    @Test
    public void exposesEveryAdministratorControlledMfaPolicyField() throws Exception {
        JsonNode fields = resourceFields();

        assertInteger(fields, "maximumFailedAttempts", 5, 50, 10);
        assertInteger(fields, "lockoutSeconds", 30, 3600, 300);
        assertInteger(fields, "securityConfirmationTtlSeconds", 60, 900, 300);
        assertEnum(fields, "federatedMfaMode", "platform", "platform", "trustedClaims");
        assertString(fields, "trustedAuthenticationMethods", true);
        assertEquals("mfa,otp,hwk,webauthn",
                fields.path("trustedAuthenticationMethods").path("default").asText());
        assertString(fields, "trustedAuthenticationContexts", true);
        assertInteger(fields, "maximumFederatedAuthenticationAgeSeconds", 60, 3600, 300);
        assertEnum(fields, "passkeyCounterPolicy", "riskAware", "riskAware", "strict");
        assertEnum(fields, "securityEmailLocale", "zh-tw", "zh-tw", "en-us");
        assertEquals("password", fields.path("securityConfirmation").path("type").asText());
        assertTrue(fields.path("securityConfirmation").path("nullable").asBoolean());
    }

    @Test
    public void exposesLocalAdministratorRecoveryReadinessAsReadOnlyStatus() throws Exception {
        JsonNode fields = resourceFields();

        assertReadOnlyBoolean(fields, "localAdministratorRecoveryRequired");
        assertReadOnlyBoolean(fields, "localAdministratorRecoveryConfigured");
        assertReadOnlyBoolean(fields, "localAdministratorRecoveryMfaReady");
        assertReadOnlyBoolean(fields, "localAdministratorRecoveryEnabled");

        JsonNode status = fields.path("localAdministratorRecoveryStatus");
        assertEquals("enum", status.path("type").asText());
        assertEquals(new HashSet<String>(Arrays.asList("ready", "blocked")),
                textValues(status.path("options")));
        assertFalse(status.path("create").asBoolean(true));
        assertFalse(status.path("update").asBoolean(true));
        assertFalse(status.path("nullable").asBoolean(true));
    }

    private JsonNode resourceFields() throws Exception {
        return schema().path("resourceFields");
    }

    private JsonNode schema() throws Exception {
        Path schema = Paths.get("content", "schema", "base", "mfaSettings.json");
        assertTrue("MFA settings schema is missing: " + schema, Files.isRegularFile(schema));
        try (InputStream input = Files.newInputStream(schema)) {
            JsonNode document = objectMapper.readTree(input);
            JsonNode fields = document.path("resourceFields");
            assertTrue("MFA settings resource fields are missing", fields.isObject());
            return document;
        }
    }

    private void assertInteger(JsonNode fields, String name, int minimum, int maximum,
                               int defaultValue) {
        JsonNode field = fields.path(name);
        assertEquals("int", field.path("type").asText());
        assertEquals(minimum, field.path("min").asInt());
        assertEquals(maximum, field.path("max").asInt());
        assertEquals(defaultValue, field.path("default").asInt());
        assertFalse(field.path("nullable").asBoolean(true));
    }

    private void assertEnum(JsonNode fields, String name, String defaultValue,
                            String... options) {
        JsonNode field = fields.path(name);
        assertEquals("enum", field.path("type").asText());
        assertEquals(new HashSet<String>(Arrays.asList(options)),
                textValues(field.path("options")));
        assertEquals(defaultValue, field.path("default").asText());
        assertFalse(field.path("nullable").asBoolean(true));
    }

    private void assertString(JsonNode fields, String name, boolean nullable) {
        JsonNode field = fields.path(name);
        assertEquals("string", field.path("type").asText());
        assertEquals(nullable, field.path("nullable").asBoolean());
    }

    private void assertWritable(JsonNode fields, String name) {
        JsonNode field = fields.path(name);
        assertTrue(name, field.isObject());
        assertFalse(name, field.path("create").asBoolean(true));
        assertTrue(name, field.path("update").asBoolean(false));
    }

    private void assertReadOnlyBoolean(JsonNode fields, String name) {
        JsonNode field = fields.path(name);
        assertEquals("boolean", field.path("type").asText());
        assertFalse(field.path("create").asBoolean(true));
        assertFalse(field.path("update").asBoolean(true));
        assertFalse(field.path("nullable").asBoolean(true));
    }

    private Set<String> textValues(JsonNode values) {
        Set<String> result = new HashSet<String>();
        for (JsonNode value : values) {
            result.add(value.asText());
        }
        return result;
    }
}
