package io.cattle.platform.allocator.dao.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class AllocatorDaoImplTest {

    @Test
    public void convertsAllocatedIpsThroughCheckedBoundary() {
        AllocatorDaoImpl dao = new AllocatorDaoImpl();
        Map<String, Object> allocatedIp = new LinkedHashMap<String, Object>();
        allocatedIp.put("allocatedIP", "10.42.0.10");
        allocatedIp.put("privatePort", Integer.valueOf(80));

        List<Map<String, Object>> result = dao.allocatedIpList(Arrays.<Object>asList(allocatedIp));

        assertEquals(1, result.size());
        assertEquals("10.42.0.10", result.get(0).get("allocatedIP"));
        assertEquals(Integer.valueOf(80), result.get(0).get("privatePort"));
    }

    @Test
    public void returnsNullForMissingAllocatedIps() {
        AllocatorDaoImpl dao = new AllocatorDaoImpl();

        assertNull(dao.allocatedIpList(null));
    }

    @Test(expected = ClassCastException.class)
    public void rejectsNonMapAllocatedIpEntries() {
        AllocatorDaoImpl dao = new AllocatorDaoImpl();

        dao.allocatedIpList(Arrays.<Object>asList("not-a-map"));
    }

    @Test(expected = ClassCastException.class)
    public void rejectsNonStringAllocatedIpKeys() {
        AllocatorDaoImpl dao = new AllocatorDaoImpl();
        Map<Object, Object> allocatedIp = new LinkedHashMap<Object, Object>();
        allocatedIp.put(Integer.valueOf(1), "bad-key");

        dao.allocatedIpList(Arrays.<Object>asList(allocatedIp));
    }
}
