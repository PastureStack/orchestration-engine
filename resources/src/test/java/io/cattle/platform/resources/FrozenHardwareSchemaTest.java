package io.cattle.platform.resources;

import static org.junit.Assert.assertEquals;
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

public class FrozenHardwareSchemaTest {

    @Test
    public void frozenV1SchemasKeepHardwareFieldsAndNestedGpuType() throws Exception {
        Path schemaDirectory = Paths.get("content", "schema", "v1");
        assertTrue("Frozen schema directory is missing", Files.isDirectory(schemaDirectory));

        int checked = 0;
        try (java.util.stream.Stream<Path> files = Files.list(schemaDirectory)) {
            for (Path schemaFile : (Iterable<Path>) files
                    .filter(path -> path.getFileName().toString().endsWith(".ser"))::iterator) {
                List<?> schemas = readSchemas(schemaFile);
                Schema container = find(schemas, "container");
                if (container == null || container.getResourceFields().get("shmSize") == null) {
                    continue;
                }

                Field shmSize = container.getResourceFields().get("shmSize");
                Field runtime = container.getResourceFields().get("runtime");
                Field deviceRequests = container.getResourceFields().get("deviceRequests");
                assertNotNull(schemaFile + " is missing container.runtime", runtime);
                assertNotNull(schemaFile + " is missing container.deviceRequests", deviceRequests);
                assertEquals(schemaFile + " changed runtime create authorization",
                        shmSize.isCreate(), runtime.isCreate());
                assertEquals(schemaFile + " changed runtime update authorization",
                        shmSize.isUpdate(), runtime.isUpdate());
                assertEquals(schemaFile + " changed deviceRequests create authorization",
                        shmSize.isCreate(), deviceRequests.isCreate());
                assertEquals(schemaFile + " changed deviceRequests update authorization",
                        shmSize.isUpdate(), deviceRequests.isUpdate());
                assertEquals("array[deviceRequest]", deviceRequests.getType());

                Schema deviceRequest = find(schemas, "deviceRequest");
                assertNotNull(schemaFile + " is missing the deviceRequest schema", deviceRequest);
                for (String fieldName : new String[] {
                        "driver", "count", "deviceIds", "capabilities", "options"}) {
                    Field field = deviceRequest.getResourceFields().get(fieldName);
                    assertNotNull(schemaFile + " is missing deviceRequest." + fieldName, field);
                    assertEquals(schemaFile + " changed deviceRequest." + fieldName
                                    + " create authorization",
                            shmSize.isCreate(), field.isCreate());
                    assertEquals(schemaFile + " changed deviceRequest." + fieldName
                                    + " update authorization",
                            shmSize.isUpdate(), field.isUpdate());
                }
                checked++;
            }
        }
        assertTrue("No frozen container schemas were checked", checked > 0);
    }

    private List<?> readSchemas(Path schemaFile) throws Exception {
        try (ObjectInputStream input =
                     new ObjectInputStream(new FileInputStream(schemaFile.toFile()))) {
            Object value = input.readObject();
            assertTrue("Frozen schema must contain a list: " + schemaFile,
                    value instanceof List<?>);
            return (List<?>) value;
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
