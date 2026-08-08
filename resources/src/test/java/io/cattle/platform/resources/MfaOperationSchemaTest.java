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

public class MfaOperationSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void exposesConventionalStepUpAuthenticationOperations() throws Exception {
        JsonNode fields = resourceFields();
        Set<String> operations = textValues(fields.path("operation").path("options"));

        assertTrue(operations.contains("beginSecurityConfirmation"));
        assertTrue(operations.contains("confirmSecurityConfirmation"));
        assertSecret(fields, "challengeId");
        assertSecret(fields, "verificationCode");
        assertSecret(fields, "recoveryCode");
        assertSecret(fields, "securityConfirmation");
        assertSecret(fields, "webAuthnResponse");

        JsonNode method = fields.path("method");
        assertEquals("enum", method.path("type").asText());
        assertEquals(new HashSet<String>(Arrays.asList("totp", "webauthn", "recoveryCode")),
                textValues(method.path("options")));
        assertTrue(method.path("nullable").asBoolean());
    }

    @Test
    public void exposesStepUpChallengeMethodsAndPasskeyOptionsAsReadOnlyFields()
            throws Exception {
        JsonNode fields = resourceFields();

        assertReadOnly(fields, "methods", "array[string]");
        assertReadOnly(fields, "webAuthnOptions", "map[json]");
    }

    private JsonNode resourceFields() throws Exception {
        Path schema = Paths.get("content", "schema", "base", "mfaOperation.json");
        assertTrue("MFA operation schema is missing: " + schema, Files.isRegularFile(schema));
        try (InputStream input = Files.newInputStream(schema)) {
            JsonNode fields = objectMapper.readTree(input).path("resourceFields");
            assertTrue("MFA operation resource fields are missing", fields.isObject());
            return fields;
        }
    }

    private void assertSecret(JsonNode fields, String name) {
        JsonNode field = fields.path(name);
        assertEquals("password", field.path("type").asText());
        assertTrue(field.path("nullable").asBoolean());
    }

    private void assertReadOnly(JsonNode fields, String name, String type) {
        JsonNode field = fields.path(name);
        assertEquals(type, field.path("type").asText());
        assertFalse(field.path("create").asBoolean(true));
        assertFalse(field.path("update").asBoolean(true));
        assertTrue(field.path("nullable").asBoolean());
    }

    private Set<String> textValues(JsonNode values) {
        Set<String> result = new HashSet<String>();
        for (JsonNode value : values) {
            result.add(value.asText());
        }
        return result;
    }
}
