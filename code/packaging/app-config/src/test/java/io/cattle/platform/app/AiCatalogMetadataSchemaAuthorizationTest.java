package io.cattle.platform.app;

import static org.junit.Assert.*;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.cattle.platform.schema.processor.NotWritablePostProcessor;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class AiCatalogMetadataSchemaAuthorizationTest {
    @Test public void realRoleOverlaysPermitOnlyFixedMetadataInputsAndReadonlyPost() throws Exception {
        Path root = root(); JacksonJsonMapper json = new JacksonJsonMapper();
        Map<?, ?> definition = json.readValue(Files.readString(root.resolve("resources/content/schema/base/aiCatalogMetadataView.json")), Map.class);
        assertEquals(List.of("POST"), definition.get("collectionMethods")); assertEquals(List.of(), definition.get("resourceMethods"));
        Map<?, ?> fields = (Map<?, ?>) definition.get("resourceFields");
        assertEquals(Set.of("query", "marker", "templateId", "versions", "versionId", "resourceType", "data", "nextMarker", "resourceTextIsUntrusted"), fields.keySet());
        for (String role : List.of("admin", "user", "project")) {
            AuthOverlayPostProcessor overlay = new AuthOverlayPostProcessor(); overlay.setJsonMapper(json);
            overlay.setResources(List.of(root.resolve("resources/content/schema/" + role + "/" + role + "-auth.json").toUri().toURL())); overlay.init();
            SchemaImpl schema = new SchemaImpl(); schema.setId("aiCatalogMetadataView");
            for (Object field : fields.keySet()) schema.getResourceFields().put(field.toString(), new FieldImpl());
            assertNotNull(overlay.postProcessRegister(schema, null)); overlay.postProcess(schema, null);
            assertTrue(schema.isCreate()); assertFalse(schema.isUpdate()); assertFalse(schema.isDeletable());
            for (String field : List.of("query", "marker", "templateId", "versions", "versionId")) assertTrue(field, schema.getResourceFields().get(field).isCreate());
            for (String field : List.of("data", "nextMarker", "resourceType", "resourceTextIsUntrusted")) assertFalse(field, schema.getResourceFields().get(field).isCreate());
            new NotWritablePostProcessor().postProcessRegister(schema, null); assertTrue(schema.isCreate()); assertFalse(schema.isUpdate());
        }
    }

    @Test public void springWiringRegistersTheTypedManagerAndNoArbitraryProxyInput() throws Exception {
        String config = Files.readString(root().resolve("code/packaging/app-config/src/main/java/io/cattle/platform/app/IaasApiConfig.java"));
        for (String name : List.of("AiCatalogMetadataGateway()", "AiCatalogMetadataService()", "AiCatalogMetadataResourceManager()", "AiCatalogMetadataView.class"))
            assertTrue(name, config.contains(name));
    }

    static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("resources/content/schema"))) path = path.getParent();
        assertNotNull(path); return path;
    }
}
