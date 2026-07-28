package io.cattle.platform.schema.processor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class JsonFileOverlayPostProcessorTest {

    @Test
    public void processMapDataUsesCheckedStringKeyCopies() throws Exception {
        JsonFileOverlayPostProcessor processor = new JsonFileOverlayPostProcessor();
        SchemaImpl schema = new SchemaImpl();
        FieldImpl existing = new FieldImpl();
        FieldImpl stale = new FieldImpl();
        schema.getResourceFields().put("existing", existing);
        schema.getResourceFields().put("stale", stale);

        SchemaOverlayImpl overlay = new SchemaOverlayImpl();
        overlay.setResourceFieldsExplicit(true);
        FieldImpl existingOverlay = new FieldImpl();
        FieldImpl addedOverlay = new FieldImpl();
        overlay.getResourceFields().put("existing", existingOverlay);
        overlay.getResourceFields().put("added", addedOverlay);

        Map<String, Object> existingData = new HashMap<String, Object>();
        existingData.put("description", "updated existing");
        Map<String, Object> addedData = new HashMap<String, Object>();
        addedData.put("description", "new field");
        Map<String, Object> rawResourceFields = new HashMap<String, Object>();
        rawResourceFields.put("existing", existingData);
        rawResourceFields.put("added", addedData);
        Map<String, Object> rawSchema = new HashMap<String, Object>();
        rawSchema.put("resourceFields", rawResourceFields);

        processor.processMapData(schema, overlay, rawSchema, "resourceFields");

        assertFalse(schema.getResourceFields().containsKey("stale"));
        assertSame(existing, schema.getResourceFields().get("existing"));
        assertSame(addedOverlay, schema.getResourceFields().get("added"));
        assertEquals("updated existing", existing.getDescription());
        assertEquals("new field", addedOverlay.getDescription());
    }
}
