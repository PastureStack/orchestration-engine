package io.cattle.platform.core.dao.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.model.DynamicSchema;
import io.cattle.platform.core.model.tables.records.DynamicSchemaRecord;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class DynamicSchemaDaoImplTest {

    @Test
    public void rolesReadsStringLists() {
        TestDynamicSchemaDao dao = new TestDynamicSchemaDao();

        assertEquals(Arrays.asList("admin", "user"), dao.rolesFrom(schemaWithRoles(Arrays.asList("admin", "user"))));
    }

    @Test
    public void rolesPreservesScalarFallbackAndMissingDefault() {
        TestDynamicSchemaDao dao = new TestDynamicSchemaDao();

        assertEquals(Arrays.asList("admin"), dao.rolesFrom(schemaWithRoles("admin")));
        assertTrue(dao.rolesFrom(schemaWithRoles(null)).isEmpty());
    }

    @Test
    public void rolesRejectsNonStringEntriesAtCheckedBoundary() {
        TestDynamicSchemaDao dao = new TestDynamicSchemaDao();

        try {
            dao.rolesFrom(schemaWithRoles(Arrays.asList("admin", Integer.valueOf(1))));
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains(String.class.getName()));
            return;
        }

        throw new AssertionError("Expected non-string role to fail at checked boundary");
    }

    private static DynamicSchema schemaWithRoles(Object roles) {
        DynamicSchemaRecord schema = new DynamicSchemaRecord();
        Map<String, Object> data = new HashMap<String, Object>();
        Map<String, Object> fields = new HashMap<String, Object>();
        if (roles != null) {
            fields.put("roles", roles);
        }
        data.put("fields", fields);
        schema.setData(data);
        return schema;
    }

    private static class TestDynamicSchemaDao extends DynamicSchemaDaoImpl {
        java.util.List<String> rolesFrom(DynamicSchema dynamicSchema) {
            return roles(dynamicSchema);
        }
    }
}
