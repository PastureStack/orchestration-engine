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
import java.util.Map;
import org.junit.Test;

public class AiResourceSchemaAuthorizationTest {
    @Test public void allRealRolesExposeOnlyTheTypedPlanInputAndSafeViewInput() throws Exception {
        for (String role : List.of("admin", "user", "project")) {
            for (String type : List.of("aiResourceView", "aiResourceChangePlan")) {
                SchemaImpl schema = effective(role, type);
                assertNotNull(role + ":" + type, schema); assertTrue(schema.isCreate());
                List<String> inputs = "aiResourceView".equals(type) ? List.of("resourceType", "resourceId", "query", "marker", "parentId")
                        : List.of("resourceType", "operation", "resourceId", "parameters");
                for (String field : inputs) assertTrue(role + ":" + type + ":" + field, schema.getResourceFields().get(field).isCreate());
                for (var entry : schema.getResourceFields().entrySet()) if (!inputs.contains(entry.getKey())) assertFalse(entry.getKey(),
                        entry.getValue().isCreate());
                assertFalse(schema.isUpdate()); assertFalse(schema.isDeletable());
            }
        }
    }

    @Test public void readonlyCanRequestASafeReadButCannotCreateAMutationPlan() throws Exception {
        var readonly = new NotWritablePostProcessor();
        SchemaImpl view = effective("project", "aiResourceView"); readonly.postProcessRegister(view, null);
        assertTrue(view.isCreate()); assertFalse(view.isUpdate());
        SchemaImpl plan = effective("project", "aiResourceChangePlan"); readonly.postProcessRegister(plan, null);
        assertFalse(plan.isCreate()); assertFalse(plan.getResourceFields().get("parameters").isCreate());
    }

    @Test public void syntheticTypesDoNotAddAnArbitraryTransportAndTheNewMutationFlagDefaultsOff() throws Exception {
        Path root = root();
        assertTrue(Files.readString(root.resolve("resources/content/cattle-global.properties")).contains("api.ai.resource.change.enabled=false"));
        Map<?, ?> definition = new JacksonJsonMapper().readValue(Files.readString(root.resolve("resources/content/schema/base/aiResourceChangePlan.json")), Map.class);
        Map<?, ?> fields = (Map<?, ?>) definition.get("resourceFields");
        for (String forbidden : List.of("url", "method", "headers", "requestBody", "snapshot", "credentialId", "actorId", "accountId"))
                assertFalse(forbidden, fields.containsKey(forbidden));
        assertEquals(List.of("GET", "POST"), definition.get("collectionMethods")); assertEquals(List.of("GET"), definition.get("resourceMethods"));
    }

    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("resources/content/schema"))) root = root.getParent();
        assertNotNull(root); return root;
    }

    private static SchemaImpl effective(String role, String type) throws Exception {
        Path root = root(); JacksonJsonMapper json = new JacksonJsonMapper();
        Map<?, ?> base = json.readValue(Files.readString(root.resolve("resources/content/schema/base/" + type + ".json")), Map.class);
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor(); processor.setJsonMapper(json);
        processor.setResources(List.of(root.resolve("resources/content/schema/" + role + "/" + role + "-auth.json").toUri().toURL())); processor.init();
        SchemaImpl schema = new SchemaImpl(); schema.setId(type);
        for (Object name : ((Map<?, ?>) base.get("resourceFields")).keySet()) schema.getResourceFields().put(name.toString(), new FieldImpl());
        if (processor.postProcessRegister(schema, null) == null) return null;
        processor.postProcess(schema, null); return schema;
    }
}
