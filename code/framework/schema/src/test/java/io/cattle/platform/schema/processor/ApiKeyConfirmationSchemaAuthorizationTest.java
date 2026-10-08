package io.cattle.platform.schema.processor;

import static org.junit.Assert.*;

import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ApiKeyConfirmationSchemaAuthorizationTest {
    @Test public void realV2RoleOverlaysRetainOneTimeConfirmationWriteMetadata() throws Exception {
        Path root = repository();
        for (List<String> overlays : List.of(
                List.of("user/user-auth.json"),
                List.of("user/user-auth.json", "admin/admin-auth.json"),
                List.of("user/user-auth.json", "project/project-auth.json"),
                List.of("user/user-auth.json", "project/project-auth.json", "owner/owner-auth.json"),
                List.of("user/user-auth.json", "project/project-auth.json", "restricted-user/restricted-user.json"))) {
            for (String type : List.of("apiKey", "apiKeyRestricted")) {
                SchemaImpl schema = key(type);
                AuthOverlayPostProcessor auth = overlay(root, overlays);
                assertSame(schema, auth.postProcessRegister(schema, new SchemaFactoryImpl()));
                auth.postProcess(schema, new SchemaFactoryImpl());
                Field field = schema.getResourceFields().get("securityConfirmation");
                assertNotNull(overlays + ":" + type, field);
                assertEquals("password", field.getType());
                assertTrue(field.isCreate()); assertTrue(field.isUpdate());
                assertTrue("GET must not expose the confirmation input", field.isReadOnCreateOnly());
                assertFalse("List must not expose the confirmation input", field.isIncludeInList());
            }
        }
    }

    @Test public void readonlyStillHasNoMutationPermission() throws Exception {
        Path root = repository();
        for (String type : List.of("apiKey", "apiKeyRestricted")) {
            SchemaImpl schema = key(type);
            SchemaFactoryImpl factory = new SchemaFactoryImpl();
            overlay(root, List.of("user/user-auth.json", "project/project-auth.json")).postProcess(schema, factory);
            NotWritablePostProcessor readonly = new NotWritablePostProcessor();
            readonly.postProcessRegister(schema, factory);
            overlay(root, List.of("read-user/read-user.json")).postProcess(schema, factory);
            Field field = schema.getResourceFields().get("securityConfirmation");
            assertNotNull(field); assertFalse(field.isCreate()); assertFalse(field.isUpdate());
            assertEquals(List.of("GET"), schema.getCollectionMethods());
            assertEquals(List.of("GET"), schema.getResourceMethods());
        }
    }

    private SchemaImpl key(String type) {
        SchemaImpl schema = new SchemaImpl(); schema.setId(type); schema.setCreate(true); schema.setUpdate(true);
        FieldImpl field = new FieldImpl(); field.setType("password"); field.setCreate(true); field.setUpdate(true);
        field.setIncludeInList(false);
        schema.getResourceFields().put("securityConfirmation", field);
        return schema;
    }
    private Path repository() {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/user/user-auth.json"))) root = root.getParent();
        assertNotNull("Shipped role overlays are required", root); return root;
    }
    private AuthOverlayPostProcessor overlay(Path root, List<String> paths) throws Exception {
        List<URL> resources = new ArrayList<>();
        for (String path : paths) resources.add(root.resolve("resources/content/schema/" + path).toUri().toURL());
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        processor.setJsonMapper(new JacksonJsonMapper()); processor.setResources(resources); processor.init(); return processor;
    }
}
