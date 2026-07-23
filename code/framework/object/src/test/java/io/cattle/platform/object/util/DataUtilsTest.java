package io.cattle.platform.object.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class DataUtilsTest {

    @Test
    public void createsWritableFieldsInBackingDataMap() {
        TestResource resource = new TestResource();

        Map<String, Object> fields = DataUtils.getWritableFields(resource);
        fields.put("name", "rancher");

        assertSame(fields, DataUtils.getWritableFields(resource));
        assertEquals("rancher", ((Map<?, ?>) resource.getData().get(DataUtils.FIELDS)).get("name"));
    }

    @Test
    public void convertsFieldListsThroughTargetType() {
        Map<String, Object> data = new HashMap<String, Object>();
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("ports", Arrays.asList("80", 443));
        data.put(DataUtils.FIELDS, fields);

        List<Long> ports = DataUtils.getFieldList(data, "ports", Long.class);

        assertEquals(Arrays.asList(80L, 443L), ports);
    }

    @Test
    public void returnsNullForMissingFieldList() {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put(DataUtils.FIELDS, new HashMap<String, Object>());

        assertNull(DataUtils.getFieldList(data, "ports", Long.class));
    }

    @Test
    public void convertsFieldFromRequestThroughTargetType() {
        ApiRequest request = new ApiRequest(null, null);
        Map<String, Object> requestObject = new HashMap<String, Object>();
        requestObject.put("scale", "3");
        request.setRequestObject(requestObject);

        assertEquals(Integer.valueOf(3), DataUtils.getFieldFromRequest(request, "scale", Integer.class));
    }

    @Test
    public void keepsAlreadyTypedObjectRequestField() {
        ApiRequest request = new ApiRequest(null, null);
        Map<String, Object> value = new HashMap<String, Object>();
        Map<String, Object> requestObject = new HashMap<String, Object>();
        requestObject.put("metadata", value);
        request.setRequestObject(requestObject);

        assertSame(value, DataUtils.getFieldFromRequest(request, "metadata", Object.class));
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
}
