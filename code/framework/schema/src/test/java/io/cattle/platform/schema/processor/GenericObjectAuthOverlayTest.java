package io.cattle.platform.schema.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.model.impl.WrappedResource;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** The generic storage API must not bypass a plugin's low-role projection. */
public class GenericObjectAuthOverlayTest {
    private final Path schemas = Paths.get("../../../resources/content/schema");
    private final SchemaImpl parent = base("genericObject", null);
    private final SchemaFactory factory = (SchemaFactory) Proxy.newProxyInstance(
            SchemaFactory.class.getClassLoader(), new Class<?>[] {SchemaFactory.class},
            (proxy, method, args) -> "getSchema".equals(method.getName())
                    && "genericObject".equals(args[0]) ? parent : null);
    private final IdFormatter ids = (IdFormatter) Proxy.newProxyInstance(
            IdFormatter.class.getClassLoader(), new Class<?>[] {IdFormatter.class},
            (proxy, method, args) -> "formatId".equals(method.getName()) ? args[1] : null);

    private SchemaImpl base(String type, String parentType) {
        SchemaImpl schema = new SchemaImpl();
        schema.setId(type);
        schema.setParent(parentType);
        for (String name : Arrays.asList("name", "kind", "accountId", "state", "key", "resourceData",
                "status", "image", "labels", "mode")) {
            FieldImpl field = new FieldImpl();
            field.setName(name);
            field.setType("resourceData".equals(name) ? "map[json]" : "string");
            schema.getResourceFields().put(name, field);
        }
        return schema;
    }

    private void overlay(SchemaImpl schema, String... files) throws Exception {
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        for (String file : files) {
            Map<?, ?> document = new ObjectMapper().readValue(schemas.resolve(file).toFile(), Map.class);
            processor.load((Map<?, ?>) document.get("authorize"));
        }
        processor.postProcess(schema, factory);
    }

    private SchemaImpl projected(String type, String role) throws Exception {
        SchemaImpl schema = base(type, "genericObject".equals(type) ? null : "genericObject");
        // The low-role processor is applied after the parent project factory,
        // not merged into the parent's permissions table.
        overlay(schema, "user/user-auth.json", "project/project-auth.json");
        if (role != null) {
            overlay(schema, role + "/" + role + ".json");
        }
        return schema;
    }

    private Map<String, Object> response(SchemaImpl schema) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("name", "receiver-fixture");
        fields.put("kind", "webhookReceiver");
        fields.put("accountId", "project-fixture");
        fields.put("state", "active");
        fields.put("status", "complete");
        fields.put("image", "example.invalid/test:1");
        fields.put("labels", "test-label");
        fields.put("mode", "pull");
        fields.put("key", "test-capability");
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("url", "https://example.invalid/endpoint?key=test-capability");
        data.put("config", "plugin-owned-configuration");
        fields.put("resourceData", data);
        return new WrappedResource(ids, factory, schema, null, fields, null, "GET").getFields();
    }

    private void assertLowRole(SchemaImpl schema) {
        assertFalse(schema.getResourceFields().containsKey("key"));
        assertFalse(schema.getResourceFields().containsKey("resourceData"));
        Map<String, Object> result = response(schema);
        assertFalse(result.containsKey("key"));
        assertFalse(result.containsKey("resourceData"));
        assertEquals("receiver-fixture", result.get("name"));
        assertEquals("webhookReceiver", result.get("kind"));
        assertEquals("project-fixture", result.get("accountId"));
        assertEquals("active", result.get("state"));
    }

    @Test
    public void lowRolesHideCapabilitiesAtTheSharedResponseBoundary() throws Exception {
        for (String role : Arrays.asList("read-user", "restricted-user")) {
            assertLowRole(projected("genericObject", role));
        }
    }

    @Test
    public void inheritedStorageFieldsCannotReintroduceCapabilities() throws Exception {
        for (String role : Arrays.asList("read-user", "restricted-user")) {
            assertLowRole(projected("pluginObject", role));
            assertLowRole(projected("register", role));
        }
    }

    @Test
    public void privilegedProjectClientsKeepPluginStorage() throws Exception {
        SchemaImpl schema = projected("genericObject", null);
        assertTrue(schema.getResourceFields().containsKey("key"));
        assertTrue(schema.getResourceFields().containsKey("resourceData"));
        Map<String, Object> result = response(schema);
        assertEquals("test-capability", result.get("key"));
        assertTrue(result.get("resourceData") instanceof Map);
    }

    @Test
    public void pullTaskStorageRemainsPrivateWithoutRemovingTypedStatus() throws Exception {
        for (String role : Arrays.asList("read-user", "restricted-user")) {
            SchemaImpl schema = projected("pullTask", role);
            assertFalse(schema.getResourceFields().containsKey("key"));
            assertFalse(schema.getResourceFields().containsKey("resourceData"));
            assertTrue(schema.getResourceFields().containsKey("kind"));
            assertTrue(schema.getResourceFields().containsKey("state"));
            Map<String, Object> result = response(schema);
            assertEquals("complete", result.get("status"));
            assertEquals("example.invalid/test:1", result.get("image"));
            assertEquals("test-label", result.get("labels"));
            assertEquals("pull", result.get("mode"));
        }
    }
}
