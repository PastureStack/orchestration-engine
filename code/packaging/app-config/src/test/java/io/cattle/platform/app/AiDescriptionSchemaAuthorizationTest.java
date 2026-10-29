package io.cattle.platform.app;

import static org.junit.Assert.*;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.cattle.platform.schema.processor.NotWritablePostProcessor;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class AiDescriptionSchemaAuthorizationTest {
    @Test public void everyLiveAuthOverlayExposesTheConditionalCommand() throws Exception {
        for (String role : List.of("admin", "user", "project")) {
            SchemaImpl schema = effective(role);
            assertNotNull(role + " must not hide the command", schema);
            assertTrue(role, schema.isCreate());
            assertFalse(schema.isUpdate());
            assertFalse(schema.isDeletable());
            for (String input : List.of("serviceId", "description", "expectedDescription")) {
                assertTrue(role + ":" + input, schema.getResourceFields().get(input).isCreate());
            }
            for (String output : List.of("status", "verifiedResult")) {
                assertFalse(role + ":" + output, schema.getResourceFields().get(output).isCreate());
            }
        }
    }

    @Test public void readonlyRoleCannotCreateTheDescriptionCommand() throws Exception {
        SchemaImpl schema = effective("project");
        new NotWritablePostProcessor().postProcessRegister(schema, null);
        assertFalse(schema.isCreate());
        assertFalse(schema.getResourceFields().get("description").isCreate());
    }

    private SchemaImpl effective(String role) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("resources/content/schema"))) root = root.getParent();
        assertNotNull(root);
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        processor.setJsonMapper(new JacksonJsonMapper());
        processor.setResources(List.of(root.resolve("resources/content/schema/" + role + "/" + role + "-auth.json").toUri().toURL()));
        processor.init();
        SchemaImpl schema = new SchemaImpl();
        schema.setId("aiServiceDescriptionUpdate");
        for (String field : List.of("serviceId", "description", "expectedDescription", "status", "verifiedResult")) {
            schema.getResourceFields().put(field, new FieldImpl());
        }
        if (processor.postProcessRegister(schema, null) == null) return null;
        processor.postProcess(schema, null);
        return schema;
    }
}
