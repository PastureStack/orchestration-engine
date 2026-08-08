package io.cattle.platform.object.postinit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.cattle.platform.object.util.DataUtils;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ObjectDataPostInstantiationHandlerTest {

    private final ObjectDataPostInstantiationHandler handler = new ObjectDataPostInstantiationHandler();

    @Test
    public void mergesExistingInputAndUnknownFieldsIntoData() {
        TestResource resource = new TestResource();
        resource.setName("existing-name");
        Map<String, Object> existingData = new HashMap<String, Object>();
        existingData.put("existing", "value");
        existingData.put(DataUtils.OPTIONS, map("existingOption", "true"));
        resource.setData(existingData);

        Map<String, Object> inputData = new HashMap<String, Object>();
        inputData.put("incoming", "value");
        inputData.put(DataUtils.OPTIONS, map("inputOption", "true"));

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(DataUtils.DATA, inputData);
        properties.put("name", "new-name");
        properties.put("unmodeled", "field-value");

        TestResource result = handler.postProcess(resource, TestResource.class, properties);

        assertSame(resource, result);
        assertEquals("value", result.getData().get("existing"));
        assertEquals("value", result.getData().get("incoming"));
        assertNull(mapValue(result.getData(), DataUtils.OPTIONS, "existingOption"));
        assertEquals("true", mapValue(result.getData(), DataUtils.OPTIONS, "inputOption"));
        assertEquals("field-value", mapValue(result.getData(), DataUtils.FIELDS, "unmodeled"));
        assertFalse(((Map<?, ?>) result.getData().get(DataUtils.FIELDS)).containsKey("name"));
    }

    @Test
    public void ignoresNonMapDataInputLikeLegacyEmptyMapBoundary() {
        TestResource resource = new TestResource();
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(DataUtils.DATA, "not-a-map");
        properties.put("unmodeled", "field-value");

        handler.postProcess(resource, TestResource.class, properties);

        assertEquals("field-value", mapValue(resource.getData(), DataUtils.FIELDS, "unmodeled"));
        assertFalse(resource.getData().containsKey(DataUtils.DATA));
    }

    @Test
    public void skipsObjectsWithoutDataMapProperty() {
        NoDataResource resource = new NoDataResource();

        assertSame(resource, handler.postProcess(resource, NoDataResource.class, new HashMap<String, Object>()));
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put(key, value);
        return result;
    }

    private static Object mapValue(Map<String, Object> data, String mapKey, String valueKey) {
        return ((Map<?, ?>) data.get(mapKey)).get(valueKey);
    }

    public static class TestResource {
        private Map<String, Object> data;
        private String name;

        public Map<String, Object> getData() {
            return data;
        }

        public void setData(Map<String, Object> data) {
            this.data = data;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class NoDataResource {
    }
}
