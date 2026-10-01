package io.github.ibuildthecloud.gdapi.request.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public class BodyParserRequestHandlerTest {

    BodyParserRequestHandler handler;
    ApiRequest request;
    JacksonMapper mapper;

    @Before
    public void setUp() {
        handler = new BodyParserRequestHandler();
        handler.init();
        mapper = new JacksonMapper();
        mapper.init();
        handler.setJsonMarshaller(mapper);

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

    @Test
    public void parsesObjectUnicodeAndAdditionalFieldsWithRealMapper() throws Exception {
        ApiRequest bodyRequest = jsonRequest("{\"name\":\"繁體中文／測試🙂\","
                + "\"additionalField\":{\"enabled\":true,\"labels\":[\"甲\",\"乙\"]}}");

        handler.handle(bodyRequest);

        Map<?, ?> result = (Map<?, ?>)bodyRequest.getRequestObject();
        assertEquals("繁體中文／測試🙂", result.get("name"));
        assertEquals("query-value", result.get("queryOnly"));
        Map<?, ?> additional = (Map<?, ?>)result.get("additionalField");
        assertEquals(Boolean.TRUE, additional.get("enabled"));
        assertEquals(Arrays.asList("甲", "乙"), additional.get("labels"));
    }

    @Test
    public void parsesListWithRealMapperAndMergesObjectItems() throws Exception {
        ApiRequest bodyRequest = jsonRequest("[{\"name\":\"first\"},\"ignored\","
                + "{\"name\":\"second\",\"count\":2}]");

        handler.handle(bodyRequest);

        List<?> result = (List<?>)bodyRequest.getRequestObject();
        assertEquals(2, result.size());
        assertEquals("first", ((Map<?, ?>)result.get(0)).get("name"));
        assertEquals("second", ((Map<?, ?>)result.get(1)).get("name"));
        assertEquals(2, ((Map<?, ?>)result.get(1)).get("count"));
        assertEquals("query-value", ((Map<?, ?>)result.get(1)).get("queryOnly"));
    }

    @Test
    public void typedMapperIgnoresUnknownFieldsAndRoundTripsUnicode() throws Exception {
        NameOnly value = mapper.readValue("{\"name\":\"名稱／✓\",\"futureField\":true}"
                .getBytes(StandardCharsets.UTF_8), NameOnly.class);
        assertEquals("名稱／✓", value.name);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        mapper.writeValue(output, value);
        Map<?, ?> result = mapper.readValue(output.toByteArray(), Map.class);
        assertEquals("名稱／✓", result.get("name"));
        assertFalse(result.containsKey("futureField"));
    }

    @Test
    public void malformedJsonReturnsInvalidBodyContentBadRequest() throws Exception {
        for (String malformed : Arrays.asList("{\"name\":", "[{\"name\":\"broken\"}")) {
            try {
                handler.handle(jsonRequest(malformed));
                fail("Malformed JSON must not be accepted");
            } catch (ClientVisibleException error) {
                assertEquals(ResponseCodes.BAD_REQUEST, error.getStatus());
                assertEquals(ValidationErrorCodes.INVALID_BODY_CONTENT, error.getCode());
            }
        }
    }

    private ApiRequest jsonRequest(String json) {
        final byte[] content = json.getBytes(StandardCharsets.UTF_8);
        ApiRequest bodyRequest = new ApiRequest(null, null) {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(content);
            }
        };
        bodyRequest.setMethod("POST");
        bodyRequest.setRequestParams(request.getRequestParams());
        return bodyRequest;
    }

    public static class NameOnly {
        public String name;
    }
}
