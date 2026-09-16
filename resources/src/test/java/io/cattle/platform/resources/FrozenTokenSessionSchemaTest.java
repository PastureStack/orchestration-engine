package io.cattle.platform.resources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

/** Verifies the frozen v1 schemas preserve the browser-session ownership input. */
public class FrozenTokenSessionSchemaTest {
    private final Path schemas = Paths.get("content", "schema", "v1");

    @Test
    public void everyFrozenSchemaWithTokenAcceptsClientSessionIdOnlyOnCreate()
            throws Exception {
        int checked = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(schemas)) {
            for (Path schemaFile : (Iterable<Path>) stream
                    .filter(path -> path.getFileName().toString().endsWith(".ser"))::iterator) {
                Schema token = find(readSchemas(schemaFile), "token");
                if (token == null) {
                    continue;
                }
                Field clientSessionId = token.getResourceFields().get("clientSessionId");
                assertNotNull(schemaFile + " lacks token.clientSessionId", clientSessionId);
                assertTrue(schemaFile + " must create token.clientSessionId",
                        clientSessionId.isCreate());
                assertFalse(schemaFile + " must not update token.clientSessionId",
                        clientSessionId.isUpdate());
                assertTrue(schemaFile + " must keep token.clientSessionId read-on-create-only",
                        clientSessionId.isReadOnCreateOnly());
                assertTrue(schemaFile + " must allow a missing legacy client generation",
                        clientSessionId.isNullable());
                assertEquals(schemaFile + " changed token.clientSessionId type",
                        "password", clientSessionId.getType());
                assertEquals(schemaFile + " changed token.clientSessionId minimum length",
                        Long.valueOf(78L), clientSessionId.getMinLength());
                assertEquals(schemaFile + " changed token.clientSessionId maximum length",
                        Long.valueOf(78L), clientSessionId.getMaxLength());
                checked++;
            }
        }
        assertTrue("no frozen schema exposed token", checked > 0);
    }

    private List<?> readSchemas(Path schemaFile) throws Exception {
        try (ObjectInputStream input =
                     new ObjectInputStream(new FileInputStream(schemaFile.toFile()))) {
            return (List<?>) input.readObject();
        }
    }

    private Schema find(List<?> schemas, String id) {
        for (Object item : schemas) {
            if (item instanceof Schema && id.equals(((Schema) item).getId())) {
                return (Schema) item;
            }
        }
        return null;
    }
}
