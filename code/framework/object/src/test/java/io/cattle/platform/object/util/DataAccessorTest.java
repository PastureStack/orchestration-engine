package io.cattle.platform.object.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.util.type.UnmodifiableMap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class DataAccessorTest {

    @Test
    public void convertsScalarFieldThroughTargetType() {
        TestResource resource = resourceWithField("scale", "3");

        assertEquals(Integer.valueOf(3), DataAccessor.field(resource, "scale", Integer.class));
    }

    @Test
    public void preservesAlreadyTypedObjectField() {
        Map<String, Object> metadata = new HashMap<String, Object>();
        TestResource resource = resourceWithField("metadata", metadata);

        assertSame(metadata, DataAccessor.field(resource, "metadata", Object.class));
    }

    @Test
    public void returnsNullForMissingField() {
        TestResource resource = new TestResource();

        assertNull(DataAccessor.field(resource, "scale", Integer.class));
    }

    @Test
    public void missingBooleanFieldKeepsLegacyFalseDefault() {
        TestResource resource = new TestResource();

        assertEquals(Boolean.FALSE, DataAccessor.fieldBoolean(resource, "enabled"));
        assertEquals(Boolean.FALSE, DataAccessor.fields(resource).withKey("enabled").as(Boolean.class));
    }

    @Test
    public void readDataReturnsUnmodifiableMapView() {
        TestResource resource = new TestResource();
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("name", "demo");
        resource.setData(data);

        Map<String, Object> read = DataAccessor.getData(resource, true);

        assertEquals("demo", read.get("name"));
        assertSame(data, resource.getData());
        try {
            read.put("other", "value");
        } catch (UnsupportedOperationException e) {
            return;
        }
        throw new AssertionError("read map must be unmodifiable");
    }

    @Test
    public void writeDataKeepsExistingBackingMap() {
        TestResource resource = new TestResource();
        Map<String, Object> data = new HashMap<String, Object>();
        resource.setData(data);

        DataAccessor.fields(resource).withKey("name").set("demo");

        assertSame(data, resource.getData());
        assertEquals("demo", DataAccessor.field(resource, "name", String.class));
    }

    @Test
    public void writeDataCreatesMapWhenMissing() {
        TestResource resource = new TestResource();

        DataAccessor.fields(resource).withKey("name").set("demo");

        assertEquals("demo", DataAccessor.field(resource, "name", String.class));
    }

    @Test
    public void writeDataCopiesUnmodifiableMapBeforeMutation() {
        TestResource resource = new TestResource();
        TestUnmodifiableMap data = new TestUnmodifiableMap();
        data.put("existing", "value");
        resource.setData(data);

        DataAccessor.fields(resource).withKey("name").set("demo");

        assertTrue(resource.getData() instanceof HashMap);
        assertEquals("value", resource.getData().get("existing"));
        assertEquals("demo", DataAccessor.field(resource, "name", String.class));
    }

    private static TestResource resourceWithField(String key, Object value) {
        TestResource resource = new TestResource();
        Map<String, Object> data = new HashMap<String, Object>();
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put(key, value);
        data.put(DataUtils.FIELDS, fields);
        resource.setData(data);
        return resource;
    }

    public static class TestResource {
        private Map<String, Object> data;

        public Map<String, Object> getData() {
            return data;
        }

        public void setData(Map<String, Object> data) {
            this.data = data;
        }
    }

    private static class TestUnmodifiableMap implements UnmodifiableMap<String, Object> {
        private final Map<String, Object> values = new HashMap<String, Object>();

        @Override
        public Map<String, Object> getModifiableCopy() {
            return new HashMap<String, Object>(values);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return values.containsValue(value);
        }

        @Override
        public Object get(Object key) {
            return values.get(key);
        }

        @Override
        public Object put(String key, Object value) {
            return values.put(key, value);
        }

        @Override
        public Object remove(Object key) {
            return values.remove(key);
        }

        @Override
        public void putAll(Map<? extends String, ? extends Object> map) {
            values.putAll(map);
        }

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public Set<String> keySet() {
            return values.keySet();
        }

        @Override
        public Collection<Object> values() {
            return values.values();
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return values.entrySet();
        }
    }
}
