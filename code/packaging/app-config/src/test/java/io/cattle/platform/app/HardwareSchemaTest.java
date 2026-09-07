package io.cattle.platform.app;

import static org.junit.Assert.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Test;
import io.cattle.platform.core.addon.DeviceRequest;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;

public class HardwareSchemaTest {
    @Test
    public void nestedGpuCapabilitiesAndProjectWriteAuthorizationSurviveSchema() throws Exception {
        SchemaFactoryImpl factory = new SchemaFactoryImpl();
        Schema schema = factory.registerSchema(DeviceRequest.class);
        schema = factory.parseSchema(schema.getId());
        assertEquals("array[array[string]]", schema.getResourceFields().get("capabilities").getType());
        assertEquals("array[string]", schema.getResourceFields().get("deviceIds").getType());
        assertEquals("map[string]", schema.getResourceFields().get("options").getType());
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/user/user-auth.json"))) { root = root.getParent(); }
        assertNotNull(root);
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        processor.setJsonMapper(new JacksonJsonMapper());
        processor.setResources(Arrays.asList(root.resolve("resources/content/schema/user/user-auth.json").toUri().toURL(),
                root.resolve("resources/content/schema/project/project-auth.json").toUri().toURL()));
        processor.init();
        assertNotNull(processor.postProcessRegister((SchemaImpl) schema, factory));
        processor.postProcess((SchemaImpl) schema, factory);
        for (String name : Arrays.asList("driver", "count", "deviceIds", "capabilities", "options")) {
            Field field = schema.getResourceFields().get(name);
            assertNotNull(name, field); assertTrue(name, field.isCreate());
        }
        SchemaImpl container = new SchemaImpl(); container.setId("container");
        for (String name : Arrays.asList("runtime", "deviceRequests")) { container.getResourceFields().put(name, new FieldImpl()); }
        processor.postProcess(container, factory);
        for (Field field : container.getResourceFields().values()) { assertTrue(field.isCreate()); }
        assertEquals(2, container.getResourceFields().size());
    }
}
