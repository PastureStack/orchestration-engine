package io.cattle.platform.db.jooq.converter.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;

import org.junit.Test;

import io.cattle.platform.db.jooq.converter.DataConverter;
import io.cattle.platform.json.JacksonJsonMapper;

public class JsonUnmodifiableMapTest {

    @Test
    public void converterReturnsUnmodifiableStringObjectMap() {
        DataConverter converter = new DataConverter();
        Map<String, Object> data = converter.from("{\"name\":\"demo\",\"nested\":{\"enabled\":true}}");

        assertEquals("demo", data.get("name"));
        assertTrue(data.get("nested") instanceof Map);

        try {
            data.put("extra", Boolean.TRUE);
            fail("Expected JSON data map to stay unmodifiable until copied");
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void modifiableCopyCanChangeWithoutChangingSource() {
        DataConverter converter = new DataConverter();
        JsonUnmodifiableMap data = JsonUnmodifiableMap.class.cast(converter.from("{\"name\":\"demo\"}"));

        Map<String, Object> copy = data.getModifiableCopy();
        copy.put("name", "changed");
        copy.put("extra", Boolean.TRUE);

        assertEquals("changed", copy.get("name"));
        assertEquals(Boolean.TRUE, copy.get("extra"));
        assertEquals("demo", data.get("name"));
    }

    @Test
    public void templatePlaceholderKeepsLegacyStringRepresentation() {
        JsonUnmodifiableMap data = new JsonUnmodifiableMap(new JacksonJsonMapper(), "%SECRET%");

        assertEquals("%SECRET%", data.toString());
        assertTrue(data.isEmpty());

        try {
            data.put("extra", Boolean.TRUE);
            fail("Expected template placeholder map to stay unmodifiable");
        } catch (UnsupportedOperationException expected) {
        }
    }
}
