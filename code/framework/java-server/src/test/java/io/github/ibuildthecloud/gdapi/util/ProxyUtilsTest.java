package io.github.ibuildthecloud.gdapi.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ProxyUtilsTest {

    public enum TestState {
        ACTIVE
    }

    public interface TestProxy {
        Integer getCount();

        int getPrimitiveCount();

        TestState getState();

        String getName();

        void setName(String name);
    }

    @Test
    public void castsDynamicProxyToRequestedInterface() {
        Map<String, Object> values = new HashMap<>();

        TestProxy proxy = ProxyUtils.proxy(values, TestProxy.class);

        assertSame(TestProxy.class, proxy.getClass().getInterfaces()[0]);
    }

    @Test
    public void convertsLongValuesForIntegerGetters() {
        Map<String, Object> values = new HashMap<>();
        values.put("count", Long.valueOf(42));
        values.put("primitiveCount", Long.valueOf(7));

        TestProxy proxy = ProxyUtils.proxy(values, TestProxy.class);

        assertEquals(Integer.valueOf(42), proxy.getCount());
        assertEquals(7, proxy.getPrimitiveCount());
    }

    @Test
    public void convertsStringValuesForEnumGetters() {
        Map<String, Object> values = new HashMap<>();
        values.put("state", "ACTIVE");

        TestProxy proxy = ProxyUtils.proxy(values, TestProxy.class);

        assertEquals(TestState.ACTIVE, proxy.getState());
    }

    @Test
    public void writesSetterValuesBackToBackingMap() {
        Map<String, Object> values = new HashMap<>();
        TestProxy proxy = ProxyUtils.proxy(values, TestProxy.class);

        proxy.setName("rancher");

        assertEquals("rancher", values.get("name"));
        assertEquals("rancher", proxy.getName());
    }
}
