package io.cattle.platform.process.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class DriverServiceActivateTest {

    @Test
    public void driverFieldsReturnsEmptyMapWhenFieldIsMissing() {
        assertTrue(DriverServiceActivate.driverFields(null).isEmpty());
    }

    @Test
    public void driverFieldsCopiesStringKeysInOrderAndPreservesValues() {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("name", "vxlan");
        fields.put("enabled", Boolean.TRUE);

        Map<String, Object> result = DriverServiceActivate.driverFields(fields);

        assertEquals(fields, result);
        assertEquals(new ArrayList<String>(fields.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void driverFieldsRejectsNonStringKeys() {
        Map<Object, Object> fields = new LinkedHashMap<Object, Object>();
        fields.put(42, "vxlan");

        DriverServiceActivate.driverFields(fields);
    }
}
