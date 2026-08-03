package io.cattle.platform.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

import org.junit.Test;

public class PortPreflightSchemaAuthorizationTest {

    private static final String[] INPUT_FIELDS = {
        "networkMode", "requestedHostId", "serviceId", "instanceId", "stackId", "global",
        "scale", "batchSize", "startFirst", "runtimeProbe", "ports"
    };
    private static final String[] PORT_FIELDS = {
        "bindAddress", "publicPort", "privatePort", "protocol", "networkMode"
    };
    private static final String[] RESULT_FIELDS = {
        "status", "checkedAt", "expiresAt", "inventoryRevision", "eligibleHostCount",
        "availableHostCount", "conflicts"
    };
    private static final String[] CONFLICT_FIELDS = {
        "severity", "source", "hostId", "hostName", "stackId", "stackName", "serviceId",
        "serviceName", "instanceId", "instanceName", "state", "bindAddress", "publicPort",
        "privatePort", "protocol", "reasonCode"
    };

    @Test
    public void projectAuthorizationKeepsPortPreflightActionSchemasVisible() throws Exception {
        Path root = repositoryRoot();
        Path userAuth = root.resolve("resources/content/schema/user/user-auth.json");
        Path projectAuth = root.resolve("resources/content/schema/project/project-auth.json");

        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        processor.setJsonMapper(new JacksonJsonMapper());
        processor.setResources(Arrays.asList(userAuth.toUri().toURL(), projectAuth.toUri().toURL()));
        processor.init();

        assertInputSchema(processor, "portPreflightInput", INPUT_FIELDS);
        assertInputSchema(processor, "portPreflightPort", PORT_FIELDS);
        assertOutputSchema(processor, "portPreflightResult", RESULT_FIELDS);
        assertOutputSchema(processor, "portPreflightConflict", CONFLICT_FIELDS);

        Map<String, Object> action = readJson(root.resolve(
                "code/iaas/api-logic/src/main/resources/schema/base/project.json.d/port-preflight.json"));
        Map<?, ?> actions = Map.class.cast(action.get("resourceActions"));
        Map<?, ?> preflight = Map.class.cast(actions.get("portpreflight"));
        assertEquals("portPreflightInput", preflight.get("input"));
        assertEquals("portPreflightResult", preflight.get("output"));
    }

    private static void assertInputSchema(AuthOverlayPostProcessor processor, String id, String[] fields) {
        SchemaImpl schema = schema(id, fields);

        assertSame(schema, processor.postProcessRegister(schema, null));
        processor.postProcess(schema, null);

        assertTrue(id + " must remain creatable in a project schema", schema.isCreate());
        for (String name : fields) {
            Field field = schema.getResourceFields().get(name);
            assertNotNull(id + "." + name + " must remain visible", field);
            assertTrue(id + "." + name + " must accept action input", field.isCreate());
        }
    }

    private static void assertOutputSchema(AuthOverlayPostProcessor processor, String id, String[] fields) {
        SchemaImpl schema = schema(id, fields);

        assertSame(schema, processor.postProcessRegister(schema, null));
        processor.postProcess(schema, null);

        assertFalse(id + " must be read-only", schema.isCreate());
        for (String name : fields) {
            assertNotNull(id + "." + name + " must remain visible", schema.getResourceFields().get(name));
        }
    }

    private static SchemaImpl schema(String id, String[] fields) {
        SchemaImpl schema = new SchemaImpl();
        schema.setId(id);
        for (String name : fields) {
            schema.getResourceFields().put(name, new FieldImpl());
        }
        return schema;
    }

    private static Map<String, Object> readJson(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return new JacksonJsonMapper().readValue(input);
        }
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
