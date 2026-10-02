package io.cattle.platform.schema.processor;

import static org.junit.Assert.*;

import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class VolumeNativeSchemaAuthorizationTest {
    @Test
    public void currentRoleOverlaysExposeNativeClassificationWithoutGrantingMutation() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/user/user-auth.json"))) {
            root = root.getParent();
        }
        assertNotNull(root);
        // MemberSchema inherits Project; its optional member-auth resource
        // does not exist in this distribution. Use the actual shipped paths.
        for (List<String> overlays : Arrays.asList(
                Arrays.asList("user/user-auth.json"),
                Arrays.asList("user/user-auth.json", "admin/admin-auth.json"),
                Arrays.asList("user/user-auth.json", "project/project-auth.json"),
                Arrays.asList("user/user-auth.json", "project/project-auth.json", "owner/owner-auth.json"),
                Arrays.asList("user/user-auth.json", "project/project-auth.json", "restricted-user/restricted-user.json"))) {
            List<URL> resources = new ArrayList<URL>();
            for (String overlay : overlays) {
                resources.add(root.resolve("resources/content/schema/" + overlay).toUri().toURL());
            }
            AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
            processor.setJsonMapper(new JacksonJsonMapper());
            processor.setResources(resources);
            processor.init();
            SchemaImpl schema = new SchemaImpl();
            schema.setId("volume");
            FieldImpl flag = new FieldImpl();
            flag.setType("boolean");
            flag.setDefault(Boolean.FALSE);
            schema.getResourceFields().put("isNative", flag);
            assertSame(overlays.toString(), schema, processor.postProcessRegister(schema, null));
            processor.postProcess(schema, null);
            Field field = schema.getResourceFields().get("isNative");
            assertNotNull(overlays.toString(), field);
            assertFalse(overlays.toString(), field.isCreate());
            assertFalse(overlays.toString(), field.isUpdate());
            assertFalse(overlays.toString(), field.isReadOnCreateOnly());
        }
    }

    @Test
    public void readonlyPipelinePreservesCrudWhileRetainingServerOwnedNativeReadField() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/user/user-auth.json"))) {
            root = root.getParent();
        }
        assertNotNull(root);
        SchemaFactory factory = new SchemaFactoryImpl();
        List<String> originalCollectionMethods = null, originalResourceMethods = null;
        for (boolean exposeNative : new boolean[] {false, true}) {
            SchemaImpl schema = new SchemaImpl();
            schema.setId("volume");
            if (exposeNative) {
                FieldImpl flag = new FieldImpl();
                flag.setType("boolean");
                flag.setDefault(Boolean.FALSE);
                flag.setCreate(true);
                flag.setUpdate(true);
                schema.getResourceFields().put("isNative", flag);
            }
            AuthOverlayPostProcessor project = overlay(root, "user/user-auth.json", "project/project-auth.json");
            assertSame(schema, project.postProcessRegister(schema, factory));
            project.postProcess(schema, factory);
            assertEquals(Arrays.asList("GET", "POST"), schema.getCollectionMethods());
            assertEquals(Arrays.asList("GET", "PUT", "DELETE"), schema.getResourceMethods());

            // Match ReadOnlySchema: inherit Project, register NotWritable
            // before read-user, then post-process in the same order.
            NotWritablePostProcessor notWritable = new NotWritablePostProcessor();
            AuthOverlayPostProcessor readUser = overlay(root, "read-user/read-user.json");
            assertSame(schema, notWritable.postProcessRegister(schema, factory));
            assertSame(schema, readUser.postProcessRegister(schema, factory));
            notWritable.postProcess(schema, factory);
            readUser.postProcess(schema, factory);
            assertFalse(schema.isCreate());
            assertFalse(schema.isUpdate());
            assertFalse(schema.isDeletable());
            assertEquals(Arrays.asList("GET"), schema.getCollectionMethods());
            assertEquals(Arrays.asList("GET"), schema.getResourceMethods());
            if (!exposeNative) {
                originalCollectionMethods = schema.getCollectionMethods();
                originalResourceMethods = schema.getResourceMethods();
            } else {
                assertEquals(originalCollectionMethods, schema.getCollectionMethods());
                assertEquals(originalResourceMethods, schema.getResourceMethods());
                Field flag = schema.getResourceFields().get("isNative");
                assertNotNull(flag);
                assertFalse(flag.isCreate());
                assertFalse(flag.isUpdate());
                assertFalse(flag.isReadOnCreateOnly());
            }
        }
    }

    private AuthOverlayPostProcessor overlay(Path root, String... paths) throws Exception {
        List<URL> resources = new ArrayList<URL>();
        for (String path : paths) {
            resources.add(root.resolve("resources/content/schema/" + path).toUri().toURL());
        }
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        processor.setJsonMapper(new JacksonJsonMapper());
        processor.setResources(resources);
        processor.init();
        return processor;
    }
}
