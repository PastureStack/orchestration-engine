package io.cattle.platform.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class JacksonJsonMapperTest {

    @Test
    public void readsObjectMapsFromAllUntypedInputs() throws Exception {
        JacksonJsonMapper mapper = new JacksonJsonMapper();
        String json = "{\"name\":\"demo\",\"nested\":{\"enabled\":true},\"items\":[1,2]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        assertMapShape(mapper.readValue(json));
        assertMapShape(mapper.readValue(bytes));
        assertMapShape(mapper.readValue(new ByteArrayInputStream(bytes)));
    }

    @Test
    public void convertValueReturnsSameInstanceWhenAlreadyAssignable() {
        JacksonJsonMapper mapper = new JacksonJsonMapper();
        AlreadyTyped value = new AlreadyTyped("existing");

        AlreadyTyped converted = mapper.convertValue(value, AlreadyTyped.class);

        assertSame(value, converted);
    }

    @Test
    public void writeValueAsMapUsesTypedMapReference() {
        JacksonJsonMapper mapper = new JacksonJsonMapper();
        AlreadyTyped value = new AlreadyTyped("existing");

        Map<String, Object> converted = mapper.writeValueAsMap(value);

        assertEquals("existing", converted.get("value"));
        assertNull(mapper.writeValueAsMap(null));
    }

    @Test
    public void listHelpersAvoidRawCollectionCallSites() throws Exception {
        JacksonJsonMapper mapper = new JacksonJsonMapper();
        String json = "[\"one\",\"two\"]";

        List<String> fromString = mapper.readListValue(json, String.class);
        List<String> fromStream = mapper.readListValue(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), String.class);
        List<String> converted = mapper.convertListValue(fromString, String.class);

        assertEquals("one", fromString.get(0));
        assertEquals("two", fromStream.get(1));
        assertSame(fromString.get(0), converted.get(0));
    }

    private static void assertMapShape(Map<String, Object> value) {
        assertEquals("demo", value.get("name"));
        assertTrue(value.get("nested") instanceof Map);
        assertEquals(Boolean.TRUE, Map.class.cast(value.get("nested")).get("enabled"));
        assertTrue(value.get("items") instanceof List);
    }

    private static class AlreadyTyped {
        private final String value;

        AlreadyTyped(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
