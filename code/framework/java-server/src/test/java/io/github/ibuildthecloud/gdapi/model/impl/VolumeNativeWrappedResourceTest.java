package io.github.ibuildthecloud.gdapi.model.impl;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class VolumeNativeWrappedResourceTest {
    private final IdFormatter ids = new IdFormatter() {
        public Object formatId(String type, Object id) { return id; }
        public String parseId(String id) { return id; }
        public IdFormatter withSchemaFactory(SchemaFactory factory) { return this; }
    };

    private Map<String, Object> render(boolean declared, Object actual, boolean present) {
        SchemaImpl schema = new SchemaImpl();
        schema.setId("volume");
        if (declared) {
            FieldImpl flag = new FieldImpl();
            flag.setType("boolean");
            flag.setDefault(Boolean.FALSE);
            schema.getResourceFields().put("isNative", flag);
        }
        Map<String, Object> values = new HashMap<String, Object>();
        if (present) values.put("isNative", actual);
        return new WrappedResource(ids, new SchemaFactoryImpl(), schema, null, values, null, "GET").getFields();
    }

    @Test
    public void formatterRetainsTheActualServerTrueAndFalseClassification() {
        assertEquals(Boolean.TRUE, render(true, Boolean.TRUE, true).get("isNative"));
        assertEquals(Boolean.FALSE, render(true, Boolean.FALSE, true).get("isNative"));
    }

    @Test
    public void onlyAnExplicitSchemaContractCanProvideTheServerDefault() {
        assertEquals(Boolean.FALSE, render(true, null, false).get("isNative"));
        assertFalse(render(false, Boolean.FALSE, true).containsKey("isNative"));
        assertFalse(render(false, Boolean.TRUE, true).containsKey("isNative"));
    }
}
