package io.cattle.platform.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class FrozenMfaPolicySchemaTest {

    @Test
    public void administratorSchemaExposesEveryRuntimePolicyField() throws Exception {
        Schema settings = find("admin.ser", "mfaSettings");
        assertNotNull(settings);
        assertEquals(new HashSet<String>(Arrays.asList("GET")),
                new HashSet<String>(settings.getCollectionMethods()));
        assertEquals(new HashSet<String>(Arrays.asList("GET", "PUT")),
                new HashSet<String>(settings.getResourceMethods()));
        Map<String, Field> fields = settings.getResourceFields();

        assertInteger(fields, "maximumFailedAttempts", 5, 50, 10);
        assertInteger(fields, "lockoutSeconds", 30, 3600, 300);
        assertInteger(fields, "securityConfirmationTtlSeconds", 60, 900, 300);
        assertEnum(fields, "federatedMfaMode", "platform", "platform", "trustedClaims");
        assertInput(fields, "trustedAuthenticationMethods", "string", true);
        assertInput(fields, "trustedAuthenticationContexts", "string", true);
        assertInteger(fields, "maximumFederatedAuthenticationAgeSeconds", 60, 3600, 300);
        assertEnum(fields, "passkeyCounterPolicy", "riskAware", "riskAware", "strict");
        assertEnum(fields, "securityEmailLocale", "zh-tw", "zh-tw", "en-us");
        assertInput(fields, "securityConfirmation", "password", true);

        assertReadOnly(fields, "localAdministratorRecoveryRequired", "boolean", false);
        assertReadOnly(fields, "localAdministratorRecoveryConfigured", "boolean", false);
        assertReadOnly(fields, "localAdministratorRecoveryMfaReady", "boolean", false);
        assertReadOnly(fields, "localAdministratorRecoveryEnabled", "boolean", false);
        assertReadOnly(fields, "localAdministratorRecoveryStatus", "enum", false);

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
            Field field = fields.get(name);
            assertNotNull(name, field);
            assertFalse(name, field.isCreate());
            assertTrue(name, field.isUpdate());
        }
    }

    @Test
    public void normalUserSchemaDoesNotExposeGlobalMfaSettings() throws Exception {
        assertNull(find("user.ser", "mfaSettings"));
    }

    @Test
    public void administratorAndUserSchemasExposeConventionalStepUpOperations()
            throws Exception {
        for (String schemaFile : Arrays.asList("admin.ser", "user.ser")) {
            Schema operation = find(schemaFile, "mfaOperation");
            assertNotNull(operation);
            Map<String, Field> fields = operation.getResourceFields();
            Set<String> operations = new HashSet<String>(fields.get("operation").getOptions());

            assertTrue(schemaFile, operations.contains("beginSecurityConfirmation"));
            assertTrue(schemaFile, operations.contains("confirmSecurityConfirmation"));
            assertEnum(fields, "method", null, "totp", "webauthn", "recoveryCode");
            assertInput(fields, "recoveryCode", "password", true);
            assertInput(fields, "securityConfirmation", "password", true);
            assertReadOnly(fields, "methods", "array[string]", true);
            assertReadOnly(fields, "webAuthnOptions", "map[json]", true);
            for (String name : Arrays.asList(
                    "operation", "method", "recoveryCode", "securityConfirmation")) {
                Field field = fields.get(name);
                assertNotNull(name, field);
                assertTrue(name, field.isCreate());
                assertFalse(name, field.isUpdate());
            }
        }
    }

    private Schema find(String schemaFile, String id) throws Exception {
        Path path = Paths.get("content", "schema", "v1", schemaFile);
        Object value;
        try (ObjectInputStream input =
                     new ObjectInputStream(new FileInputStream(path.toFile()))) {
            value = input.readObject();
        }
        for (Object item : (List<?>) value) {
            if (item instanceof Schema && id.equals(((Schema) item).getId())) {
                return (Schema) item;
            }
        }
        return null;
    }

    private void assertInteger(
            Map<String, Field> fields, String name, long min, long max, long defaultValue) {
        Field field = fields.get(name);
        assertInput(fields, name, "int", false);
        assertEquals(Long.valueOf(min), field.getMin());
        assertEquals(Long.valueOf(max), field.getMax());
        assertEquals(Long.valueOf(defaultValue), field.getDefault());
    }

    private void assertEnum(
            Map<String, Field> fields, String name, String defaultValue, String... options) {
        Field field = fields.get(name);
        assertInput(fields, name, "enum", defaultValue == null);
        assertEquals(new HashSet<String>(Arrays.asList(options)),
                new HashSet<String>(field.getOptions()));
        assertEquals(defaultValue, field.getDefault());
    }

    private void assertInput(
            Map<String, Field> fields, String name, String type, boolean nullable) {
        Field field = fields.get(name);
        assertNotNull(name, field);
        assertEquals(name, type, field.getType());
        assertEquals(name, nullable, field.isNullable());
    }

    private void assertReadOnly(
            Map<String, Field> fields, String name, String type, boolean nullable) {
        Field field = fields.get(name);
        assertNotNull(name, field);
        assertEquals(name, type, field.getType());
        assertFalse(name, field.isCreate());
        assertFalse(name, field.isUpdate());
        assertEquals(name, nullable, field.isNullable());
    }
}
