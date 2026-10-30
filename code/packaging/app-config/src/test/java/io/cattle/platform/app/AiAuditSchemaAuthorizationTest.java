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

public class AiAuditSchemaAuthorizationTest {
    @Test public void threeRealRolesExposeOnlyClosedQueryInputsAndReadonlySafeOutputs() throws Exception {
        for (String role : List.of("admin", "user", "project")) {
            SchemaImpl query = effective(role, "aiAuditQuery"); assertTrue(query.isCreate());
            for (String input : List.of("keyId", "environmentId", "from", "to", "lastMinutes", "decision", "outcome",
                    "operation", "targetType", "requestId", "httpStatus", "limit", "offset", "eventId"))
                assertTrue(role + ":" + input, query.getResourceFields().get(input).isCreate());
            for (String output : List.of("resourceType", "data", "total", "nextOffset", "range", "payloadPolicy")) {
                assertFalse(query.getResourceFields().get(output).isCreate());
                assertFalse(query.getResourceFields().get(output).isReadOnCreateOnly());
            }
            for (String forbidden : List.of("url", "method", "authorization", "cookie", "actorId", "credentialId", "fields"))
                assertFalse(query.getResourceFields().containsKey(forbidden));
            var readonly = new NotWritablePostProcessor(); readonly.postProcessRegister(query, null);
            assertTrue(query.isCreate()); assertFalse(query.isUpdate()); assertFalse(query.isDeletable());
        }
    }

    @Test public void nestedSourceInputsHaveNoRouteOrMutationAndKeepRealRoleFieldMetadata() throws Exception {
        Map<?, ?> base = definition("aiAuditSourcePageInput");
        assertEquals(List.of(), base.get("collectionMethods")); assertEquals(List.of(), base.get("resourceMethods"));
        for (String role : List.of("admin", "user", "project")) {
            SchemaImpl source = effective(role, "aiAuditSourcePageInput"); assertFalse(source.isCreate());
            new NotWritablePostProcessor().postProcessRegister(source, null);
            for (String input : List.of("limit", "marker", "projectId", "accountId", "from", "to", "keyKind")) {
                assertTrue(source.getResourceFields().get(input).isCreate());
                assertFalse(source.getResourceFields().get(input).isReadOnCreateOnly());
            }
            SchemaImpl view = effective(role, "aiResourceView"); assertTrue(view.getResourceFields().get("sourcePage").isCreate());
            assertFalse(view.getResourceFields().get("nativeAuthority").isCreate());
        }
    }

    private static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("resources/content/schema"))) root = root.getParent();
        assertNotNull(root); return root;
    }
    private static Map<?, ?> definition(String type) throws Exception {
        return new JacksonJsonMapper().readValue(Files.readString(root().resolve("resources/content/schema/base/" + type + ".json")), Map.class);
    }
    private static SchemaImpl effective(String role, String type) throws Exception {
        Path root = root(); AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        processor.setJsonMapper(new JacksonJsonMapper());
        processor.setResources(List.of(root.resolve("resources/content/schema/" + role + "/" + role + "-auth.json").toUri().toURL())); processor.init();
        SchemaImpl schema = new SchemaImpl(); schema.setId(type);
        for (Object field : ((Map<?, ?>) definition(type).get("resourceFields")).keySet()) schema.getResourceFields().put(field.toString(), new FieldImpl());
        assertNotNull(processor.postProcessRegister(schema, null)); processor.postProcess(schema, null); return schema;
    }
}
