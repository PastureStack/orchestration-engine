package io.github.ibuildthecloud.gdapi.request.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public class BodyParserRequestHandlerTest {

    BodyParserRequestHandler handler;
    ApiRequest request;

    @Before
    public void setUp() {
        handler = new BodyParserRequestHandler();
        handler.init();

        request = new ApiRequest(null, null);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("name", new String[] { "from-query" });
        params.put("queryOnly", new String[] { "query-value" });
        request.setRequestParams(params);
    }

    @Test
    public void mergeMapKeepsRequestParamsAndLetsBodyOverride() {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("name", "from-body");
        body.put("bodyOnly", "body-value");

        Map<String, Object> result = handler.mergeMap(body, request);

        assertEquals("from-body", result.get("name"));
        assertEquals("query-value", result.get("queryOnly"));
        assertEquals("body-value", result.get("bodyOnly"));
    }

    @Test
    public void mergeListRecursivelyMergesAllowedMapItems() {
        Map<String, Object> first = new HashMap<String, Object>();
        first.put("name", "first");
        Map<String, Object> second = new HashMap<String, Object>();
        second.put("name", "second");

        Object merged = handler.merge(Arrays.asList(first, "ignored", second), request);

        List<?> result = (List<?>)merged;
        assertEquals(2, result.size());
        assertEquals("first", ((Map<?, ?>)result.get(0)).get("name"));
        assertEquals("second", ((Map<?, ?>)result.get(1)).get("name"));
        assertFalse(result.contains("ignored"));
    }
}
