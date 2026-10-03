package io.cattle.platform.api.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiUtilsCreateOnlyTest {

    private SchemaImpl schema;
    private final TestBean bean = new TestBean();

    @Before
    public void createContextAndSchema() throws Exception {
        ApiContext.newContext().setApiRequest(new ApiRequest(null, null));
        schema = new SchemaImpl();
        schema.setId("apiKey");
        FieldImpl receipt = field("getCreationReceipt");
        receipt.setReadOnCreateOnly(true);
        schema.getResourceFields().put("creationReceipt", receipt);
        schema.getResourceFields().put("secretValue", field("getSecretValue"));
        schema.getResourceFields().put("name", field("getName"));
    }

    @After
    public void removeContext() {
        ApiContext.remove();
    }

    @Test
    public void trueCreateAllowsBeanAndAdditionalFieldValuesInBothRoots() {
        for (String version : Arrays.asList("v1", "v2-beta")) {
            assertFields(render(request(version, "POST", null), null), "unit-bean-receipt");
            Map<String, Object> extra = extras();
            assertFields(render(request(version, "POST", null), extra), "unit-additional-receipt");
            assertEquals("The caller's map must not be consumed", extras(), extra);
        }
    }

    @Test
    public void postActionsRedactBothValueSourcesWithoutRedactingUnflaggedNames() {
        for (String version : Arrays.asList("v1", "v2-beta")) {
            for (String action : Arrays.asList("deactivate", "activate", "remove", "update", "")) {
                assertFields(render(request(version, "POST", action), null), null);
                Map<String, Object> extra = extras();
                assertFields(render(request(version, "POST", action), extra), null);
                assertEquals(extras(), extra);
            }
        }
        assertEquals("The underlying bean is not scrubbed or mutated", "unit-bean-receipt", bean.getCreationReceipt());
    }

    @Test
    public void nullRequestFailsClosedForBothValueSources() {
        assertFields(render(null, null), null);
        Map<String, Object> extra = extras();
        assertFields(render(null, extra), null);
        assertEquals(extras(), extra);
    }

    @Test
    public void readUpdateAndDeleteCannotUseTheCreateException() {
        for (String version : Arrays.asList("v1", "v2-beta")) {
            for (String method : Arrays.asList("GET", "PUT", "DELETE")) {
                assertFields(render(request(version, method, null), null), null);
                assertFields(render(request(version, method, null), extras()), null);
            }
        }
    }

    private FieldImpl field(String getter) throws Exception {
        FieldImpl field = new FieldImpl();
        field.setType("string");
        field.setReadMethod(TestBean.class.getMethod(getter));
        return field;
    }

    private ApiRequest request(String version, String method, String action) {
        ApiRequest request = new ApiRequest(null, null);
        request.setVersion(version);
        request.setMethod(method);
        request.setAction(action);
        return request;
    }

    private Resource render(ApiRequest request, Map<String, Object> extra) {
        return ApiUtils.createResourceWithAttachments(null, request,
                ApiContext.getContext().getIdFormatter(), new SchemaFactoryImpl(), schema, bean, extra);
    }

    private Map<String, Object> extras() {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("creationReceipt", "unit-additional-receipt");
        return fields;
    }

    private void assertFields(Resource resource, String receipt) {
        if (receipt == null) {
            assertNull(resource.getFields().get("creationReceipt"));
        } else {
            assertEquals(receipt, resource.getFields().get("creationReceipt"));
        }
        assertEquals("unit-ordinary-field", resource.getFields().get("secretValue"));
        assertEquals("ordinary-name", resource.getFields().get("name"));
    }

    public static class TestBean {
        public Long getId() {
            return 73L;
        }

        public Map<String, Object> getData() {
            return Collections.emptyMap();
        }

        public String getCreationReceipt() {
            return "unit-bean-receipt";
        }

        public String getSecretValue() {
            return "unit-ordinary-field";
        }

        public String getName() {
            return "ordinary-name";
        }
    }
}
