package io.cattle.platform.app;

import static org.junit.Assert.*;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.cattle.platform.schema.processor.NotWritablePostProcessor;
import io.cattle.platform.schema.processor.JsonFileOverlayPostProcessor;
import io.cattle.platform.schema.processor.SchemaOverlayImpl;
import io.github.ibuildthecloud.gdapi.model.Filter;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AuditKeySelectorSchemaAdmissionTest {
    @Test public void allThreeRoleOverlaysAdmitOnlyTheClosedInputWithoutAddingResourceMethods() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("resources/content/schema"))) root = root.getParent();
        assertNotNull(root);
        var mapper = new JacksonJsonMapper();
        var base = mapper.readValue(Files.readString(root.resolve("resources/content/schema/base/aiAuditSourcePageInput.json")), Map.class);
        assertEquals(List.of(), base.get("collectionMethods")); assertEquals(List.of(), base.get("resourceMethods"));
        Map<?, ?> fields = (Map<?, ?>) base.get("resourceFields");
        assertEquals(Map.of("type", "string", "create", true, "maxLength", 128), fields.get("keyId"));
        assertEquals(Map.of("type", "boolean", "create", true), fields.get("capabilitiesOnly"));
        for (String role : List.of("admin", "user", "project")) {
            var processor = new AuthOverlayPostProcessor(); processor.setJsonMapper(mapper);
            processor.setResources(List.of(root.resolve("resources/content/schema/"+role+"/"+role+"-auth.json").toUri().toURL())); processor.init();
            var schema = new SchemaImpl(); schema.setId("aiAuditSourcePageInput");
            for (Object field : fields.keySet()) schema.getResourceFields().put(field.toString(), new FieldImpl());
            assertNotNull(processor.postProcessRegister(schema, null)); processor.postProcess(schema, null);
            new NotWritablePostProcessor().postProcessRegister(schema, null);
            assertFalse(schema.isCreate()); assertFalse(schema.isUpdate()); assertFalse(schema.isDeletable());
            assertTrue(schema.getResourceFields().get("keyId").isCreate());
            assertFalse(schema.getResourceFields().get("keyId").isReadOnCreateOnly());
            assertTrue(schema.getResourceFields().get("capabilitiesOnly").isCreate());
            assertFalse(schema.getResourceFields().get("capabilitiesOnly").isReadOnCreateOnly());
            assertFalse(schema.getResourceFields().get("capabilitiesOnly").isUpdate());
            for (String forbidden : List.of("url", "sql", "keyPath", "actorId", "authorization", "fields"))
                assertFalse(schema.getResourceFields().containsKey(forbidden));
        }
        var audit = mapper.readValue(Files.readString(root.resolve("resources/content/schema/base/auditLog.json")), Map.class);
        assertEquals(Map.of("keyId", Map.of("modifiers", List.of("eq"))), audit.get("collectionFilters"));
        var nativeSchema = new SchemaImpl(); nativeSchema.setId("auditLog");
        for (String name : List.of("id", "accountId", "created"))
            nativeSchema.getCollectionFilters().put(name, new Filter(List.of("eq", "gte", "lte")));
        var originalFilters = new java.util.LinkedHashMap<>(nativeSchema.getCollectionFilters());
        var overlay = new JsonFileOverlayPostProcessor() {
            void merge(SchemaImpl schema) throws Exception {
                var change = new SchemaOverlayImpl();
                change.getCollectionFilters().put("keyId", new Filter(List.of("eq")));
                processMapData(schema, change, Map.of("collectionFilters", audit.get("collectionFilters")), "collectionFilters");
            }
        };
        overlay.merge(nativeSchema);
        assertEquals(4, nativeSchema.getCollectionFilters().size());
        for (var entry : originalFilters.entrySet()) assertSame(entry.getValue(), nativeSchema.getCollectionFilters().get(entry.getKey()));
        assertEquals(List.of("eq"), nativeSchema.getCollectionFilters().get("keyId").getModifiers());
    }
}
