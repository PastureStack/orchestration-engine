package io.cattle.platform.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.ibuildthecloud.gdapi.model.Filter;
import io.github.ibuildthecloud.gdapi.model.Schema;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class FrozenIdentitySecuritySchemaTest {

    private static final Set<String> FILTERED_TYPES = new HashSet<String>(Arrays.asList(
            "authIdentityLink",
            "mfaFactor",
            "mfaStatus"));

    @Test
    public void frozenSchemasExposeAccountScopedIdentitySecurityFilters() throws Exception {
        Map<String, Integer> expectedCounts = new HashMap<String, Integer>();
        expectedCounts.put("admin.ser", 3);
        expectedCounts.put("base.ser", 3);
        expectedCounts.put("member.ser", 2);
        expectedCounts.put("owner.ser", 2);
        expectedCounts.put("project.ser", 2);
        expectedCounts.put("projectadmin.ser", 2);
        expectedCounts.put("readAdmin.ser", 3);
        expectedCounts.put("readonly.ser", 2);
        expectedCounts.put("restricted.ser", 2);
        expectedCounts.put("service.ser", 3);
        expectedCounts.put("superadmin.ser", 3);
        expectedCounts.put("user.ser", 2);

        Path schemaDirectory = Paths.get("content", "schema", "v1");
        assertTrue("Frozen schema directory is missing", Files.isDirectory(schemaDirectory));

        for (Map.Entry<String, Integer> expected : expectedCounts.entrySet()) {
            Path schemaFile = schemaDirectory.resolve(expected.getKey());
            assertTrue("Frozen schema is missing: " + schemaFile, Files.isRegularFile(schemaFile));
            assertEquals("Unexpected identity-security schema count in " + schemaFile,
                    expected.getValue().intValue(), countAndValidate(schemaFile));
        }
    }

    private int countAndValidate(Path schemaFile) throws Exception {
        int matched = 0;
        Object value;
        try (ObjectInputStream input =
                     new ObjectInputStream(new FileInputStream(schemaFile.toFile()))) {
            value = input.readObject();
        }

        assertTrue("Frozen schema must contain a list: " + schemaFile, value instanceof List<?>);
        for (Object item : (List<?>) value) {
            if (!(item instanceof Schema)) {
                continue;
            }
            Schema schema = (Schema) item;
            if (!FILTERED_TYPES.contains(schema.getId())) {
                continue;
            }

            Map<String, Filter> filters = schema.getCollectionFilters();
            assertNotNull("Collection filters are missing for " + schema.getId(), filters);
            Filter accountId = filters.get("accountId");
            assertNotNull("accountId filter is missing for " + schema.getId(), accountId);
            assertNotNull("accountId modifiers are missing for " + schema.getId(),
                    accountId.getModifiers());
            assertTrue("accountId eq modifier is missing for " + schema.getId(),
                    accountId.getModifiers().contains("eq"));
            if ("mfaStatus".equals(schema.getId())) {
                assertNotNull("SMTP enrollment availability is missing from " + schemaFile,
                        schema.getResourceFields().get("recoveryEmailEnrollmentAvailable"));
                assertEquals("SMTP enrollment availability must be a boolean in " + schemaFile,
                        "boolean",
                        schema.getResourceFields().get("recoveryEmailEnrollmentAvailable").getType());
            }
            matched++;
        }
        return matched;
    }
}
