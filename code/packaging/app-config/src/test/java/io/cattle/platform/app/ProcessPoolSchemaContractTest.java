package io.cattle.platform.app;

import static org.junit.Assert.*;

import io.cattle.platform.api.schema.FileSchemaFactory;
import io.cattle.platform.core.addon.ProcessPool;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.JsonFileOverlayPostProcessor;
import io.cattle.platform.schema.processor.SchemaOverlayImpl;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.model.impl.WrappedResource;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ProcessPoolSchemaContractTest {
    @Test public void shippingReadonlyAdditionPreservesFrozenNativeMethodsNullIdNamesAndCounters() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/v1/admin.ser"))) root = root.getParent();
        assertNotNull(root); SchemaImpl frozen;
        try (var input = new ObjectInputStream(Files.newInputStream(root.resolve("resources/content/schema/v1/admin.ser")))) {
            frozen = (SchemaImpl) ((List<?>) input.readObject()).stream().map(Schema.class::cast)
                    .filter(schema -> "processPool".equals(schema.getId())).findFirst().orElseThrow();
        }
        for (var entry : frozen.getResourceFields().entrySet()) {
            try { ((FieldImpl) entry.getValue()).setReadMethod(ProcessPool.class.getMethod("get" + Character.toUpperCase(entry.getKey().charAt(0))
                    + entry.getKey().substring(1))); }
            catch (NoSuchMethodException absentNativeId) { assertEquals("id", entry.getKey()); }
        }
        ProcessPool pool = new ProcessPool(); pool.setName(""); pool.setPoolKey("ActualOwnerBean"); pool.setActiveTasks(3);
        Map<String, Object> nativeBefore = new LinkedHashMap<>(new WrappedResource(new IdentityFormatter(), null, frozen, pool, "GET").getFields());
        List<String> collections = List.copyOf(frozen.getCollectionMethods()), resources = List.copyOf(frozen.getResourceMethods());
        JacksonMapper http = new JacksonMapper(); http.init();
        byte[] declaration = Files.readAllBytes(root.resolve("resources/content/schema/base/processPool.json"));
        SchemaImpl addition = new SchemaImpl(); addition.setId("processPool");
        new Overlay().apply(addition, http.readValue(declaration, SchemaOverlayImpl.class), new JacksonJsonMapper().readValue(declaration));
        ((FieldImpl) addition.getResourceFields().get("poolKey")).setReadMethod(ProcessPool.class.getMethod("getPoolKey"));
        assertEquals(true, addition.getResourceFields().get("poolKey").getAttributes().get("extendFrozenSchema"));
        new Extension().merge(frozen, addition);
        assertEquals(collections, frozen.getCollectionMethods()); assertEquals(resources, frozen.getResourceMethods());
        assertFalse(frozen.getResourceFields().get("poolKey").isCreate()); assertFalse(frozen.getResourceFields().get("poolKey").isUpdate());
        WrappedResource nativeAfter = new WrappedResource(new IdentityFormatter(), null, frozen, pool, "GET");
        assertNull(nativeAfter.getId()); assertEquals("ActualOwnerBean", nativeAfter.getFields().get("poolKey"));
        Map<String, Object> originalFields = new LinkedHashMap<>(nativeAfter.getFields()); originalFields.remove("poolKey");
        assertEquals(nativeBefore, originalFields); assertEquals("", originalFields.get("name")); assertEquals(3, originalFields.get("activeTasks"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); http.writeValue(bytes, nativeAfter);
        Map<?, ?> wire = http.readValue(bytes.toByteArray(), Map.class);
        assertNull(wire.get("id")); assertEquals("", wire.get("name")); assertEquals("ActualOwnerBean", wire.get("poolKey"));
    }

    private static class Extension extends FileSchemaFactory {
        void merge(Schema frozen, Schema core) { mergeOptInFields(frozen, core); }
    }
    private static class Overlay extends JsonFileOverlayPostProcessor {
        void apply(SchemaImpl target, SchemaOverlayImpl source, Map<String, Object> raw) throws Exception { processSchema(target, source, raw); }
    }
}
