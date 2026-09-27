package io.cattle.platform.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class ProjectTemplatePublicSchemaAuthorizationTest {

    @Test
    public void userAndProjectCanReadPublicStateWithoutChangingIt() throws Exception {
        Path root = repositoryRoot();
        Path userAuth = root.resolve("resources/content/schema/user/user-auth.json");
        Path projectAuth = root.resolve("resources/content/schema/project/project-auth.json");

        assertPublicField(readAuth(userAuth));
        assertPublicField(readAuth(userAuth, projectAuth));
    }

    @Test
    public void adminCanStillSetPublicState() throws Exception {
        Path root = repositoryRoot();
        AuthOverlayPostProcessor processor = readAuth(
                root.resolve("resources/content/schema/user/user-auth.json"),
                root.resolve("resources/content/schema/admin/admin-auth.json"));
        SchemaImpl schema = projectTemplateSchema();

        assertSame(schema, processor.postProcessRegister(schema, null));
        processor.postProcess(schema, null);

        Field field = schema.getResourceFields().get("isPublic");
        assertNotNull(field);
        assertTrue(field.isCreate());
        assertTrue(field.isUpdate());
    }

    private static void assertPublicField(AuthOverlayPostProcessor processor) {
        SchemaImpl schema = projectTemplateSchema();

        assertSame(schema, processor.postProcessRegister(schema, null));
        processor.postProcess(schema, null);

        Field field = schema.getResourceFields().get("isPublic");
        assertNotNull("projectTemplate.isPublic must remain visible", field);
        assertFalse("non-admin must not set public state on create", field.isCreate());
        assertFalse("non-admin must not update public state", field.isUpdate());
    }

    private static SchemaImpl projectTemplateSchema() {
        SchemaImpl schema = new SchemaImpl();
        schema.setId("projectTemplate");
        schema.getResourceFields().put("isPublic", new FieldImpl());
        return schema;
    }

    private static AuthOverlayPostProcessor readAuth(Path... paths) throws Exception {
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        processor.setJsonMapper(new JacksonJsonMapper());
        List<URL> resources = new ArrayList<URL>();
        for (Path path : paths) {
            resources.add(path.toUri().toURL());
        }
        processor.setResources(resources);
        processor.init();
        return processor;
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("resources/content/schema/user/user-auth.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate the repository root from the test working directory");
    }
}
