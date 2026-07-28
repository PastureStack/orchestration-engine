package io.cattle.platform.api.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class ProxyRequestTest {

    @Test
    public void proxiesRequestObjectGettersAndSetters() {
        ApiRequest request = new ApiRequest(null, null);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("name", "initial");
        request.setRequestObject(body);

        RequestBody proxy = ProxyRequest.proxy(request, RequestBody.class);

        assertEquals("initial", proxy.getName());
        assertNull(proxy.getMissing());

        proxy.setName("updated");
        proxy.setCount(7);

        assertEquals("updated", body.get("name"));
        assertEquals(7, body.get("count"));
    }

    private interface RequestBody {
        String getName();

        String getMissing();

        void setName(String name);

        void setCount(Integer count);
    }
}
