package io.github.ibuildthecloud.gdapi.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class RequestUtilsTest {

    @Test
    public void toMapPreservesStringKeysAndIterationOrder() {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("first", Long.valueOf(1));
        input.put("second", "two");

        Map<String, Object> result = RequestUtils.toMap(input);

        assertEquals(Arrays.asList("first", "second"), new ArrayList<String>(result.keySet()));
        assertEquals(Long.valueOf(1), result.get("first"));
        assertEquals("two", result.get("second"));
    }

    @Test
    public void toMapReturnsEmptyModifiableMapForNullAndNonMapInputs() {
        Map<String, Object> nullResult = RequestUtils.toMap(null);
        Map<String, Object> stringResult = RequestUtils.toMap("not-a-map");

        nullResult.put("nullInput", Boolean.TRUE);
        stringResult.put("stringInput", Boolean.TRUE);

        assertEquals(Boolean.TRUE, nullResult.get("nullInput"));
        assertEquals(Boolean.TRUE, stringResult.get("stringInput"));
    }

    @Test(expected = ClassCastException.class)
    public void toMapRejectsNonStringKeys() {
        Map<Object, Object> input = new LinkedHashMap<Object, Object>();
        input.put(Long.valueOf(1), "not-a-string-key");

        RequestUtils.toMap(input);
    }

}
