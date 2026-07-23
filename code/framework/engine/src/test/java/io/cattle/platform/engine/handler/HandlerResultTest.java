package io.cattle.platform.engine.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class HandlerResultTest {

    @Test
    public void mapConstructorKeepsObjectKeysAndValues() {
        Object key = new Object();
        Map<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put(key, "value");

        HandlerResult result = new HandlerResult(data);

        assertEquals("value", result.getData().get(key));
    }

    @Test
    public void mapConstructorTreatsNullAsEmptyData() {
        HandlerResult result = new HandlerResult((Map<?, Object>) null);

        assertTrue(result.getData().isEmpty());
    }

    @Test
    public void dataMapRemainsUnmodifiable() {
        Map<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put("name", "initial");

        HandlerResult result = new HandlerResult(data);

        try {
            result.getData().put("name", "changed");
            fail("Expected HandlerResult data to remain unmodifiable");
        } catch (UnsupportedOperationException expected) {
            assertEquals("initial", result.getData().get("name"));
        }
    }
}
