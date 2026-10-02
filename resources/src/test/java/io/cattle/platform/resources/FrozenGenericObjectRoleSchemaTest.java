package io.cattle.platform.resources;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.ibuildthecloud.gdapi.model.Schema;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** v1 reads these files directly; JSON-only authorization fixes are insufficient. */
public class FrozenGenericObjectRoleSchemaTest {
    private Map<String, Schema> read(String role) throws Exception {
        Map<String, Schema> result = new LinkedHashMap<String, Schema>();
        try (ObjectInputStream input = new ObjectInputStream(new FileInputStream(
                Paths.get("content", "schema", "v1", role + ".ser").toFile()))) {
            for (Object item : (List<?>) input.readObject()) {
                Schema schema = Schema.class.cast(item);
                result.put(schema.getId(), schema);
            }
        }
        return result;
    }

    @Test
    public void lowRoleFrozenSchemasCannotRevealGenericStorageCapabilities() throws Exception {
        for (String role : Arrays.asList("readonly", "restricted")) {
            Map<String, Schema> schemas = read(role);
            assertNotNull(role, schemas.get("genericObject"));
            for (Schema schema : schemas.values()) {
                Schema current = schema;
                while (current != null && !"genericObject".equals(current.getId())) {
                    current = schemas.get(current.getParent());
                }
                if (current != null) {
                    assertFalse(role + "/" + schema.getId(), schema.getResourceFields().containsKey("key"));
                    assertFalse(role + "/" + schema.getId(), schema.getResourceFields().containsKey("resourceData"));
                }
            }
            Schema generic = schemas.get("genericObject");
            for (String field : Arrays.asList("name", "kind", "accountId", "state")) {
                assertTrue(role + "/" + field, generic.getResourceFields().containsKey(field));
            }
        }
    }

    @Test
    public void privilegedProjectSchemasRetainPluginStorage() throws Exception {
        for (String role : Arrays.asList("owner", "member", "project", "service")) {
            Schema schema = read(role).get("genericObject");
            assertNotNull(role, schema);
            assertTrue(role, schema.getResourceFields().containsKey("key"));
            assertTrue(role, schema.getResourceFields().containsKey("resourceData"));
        }
    }
}
