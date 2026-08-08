package io.github.ibuildthecloud.gdapi.request;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class ApiRequestTest {

    @Test
    public void proxyRequestObjectWritesMutationsBackToRequestObject() {
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(input);

        MutablePayload payload = request.proxyRequestObject(MutablePayload.class);
        payload.setHealthcheckInstanceId(Long.valueOf(17));

        assertEquals(Long.valueOf(17), request.proxyRequestObject(MutablePayload.class).getHealthcheckInstanceId());
        assertEquals(Long.valueOf(17), Map.class.cast(request.getRequestObject()).get("healthcheckInstanceId"));
    }

    private interface MutablePayload {
        Long getHealthcheckInstanceId();

        void setHealthcheckInstanceId(Long id);
    }
}
