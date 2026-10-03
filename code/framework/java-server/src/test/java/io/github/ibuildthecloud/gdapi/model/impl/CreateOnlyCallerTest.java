package io.github.ibuildthecloud.gdapi.model.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import io.github.ibuildthecloud.gdapi.response.JsonResponseWriter;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CreateOnlyCallerTest {

    private SchemaFactoryImpl factory;
    private Schema schema;
    private final TestBean bean = new TestBean();

    @Before
    public void createContextAndBeanSchema() {
        ApiContext.newContext();
        factory = new SchemaFactoryImpl();
        factory.setIncludeDefaultTypes(false);
        factory.setTypes(Collections.<Class<?>>singletonList(TestBean.class));
        factory.init();
        schema = factory.getSchema(TestBean.class);
        FieldImpl receipt = (FieldImpl) schema.getResourceFields().get("creationReceipt");
        assertNotNull("Use the actual bean getter, not additionalFields", receipt.getReadMethod());
        receipt.setReadOnCreateOnly(true);
    }

    @After
    public void removeContext() {
        ApiContext.remove();
    }

    @Test
    public void managerConstructResourceUsesTheRequestActionNotOnlyPostMethod() {
        ExposedManager manager = new ExposedManager();
        for (String version : Arrays.asList("v1", "v2-beta")) {
            assertFields(manager.render(factory, schema, bean, request(version, "POST", null)), true);
            for (String action : Arrays.asList("deactivate", "activate", "remove", "update", "")) {
                assertFields(manager.render(factory, schema, bean, request(version, "POST", action)), false);
            }
            for (String method : Arrays.asList("GET", "PUT", "DELETE")) {
                assertFields(manager.render(factory, schema, bean, request(version, method, null)), false);
            }
        }
        assertEquals("unit-bean-receipt", bean.getCreationReceipt());
    }

    @Test
    public void responseWriterCreateResourceUsesTheActualContextRequest() {
        ExposedWriter writer = new ExposedWriter();
        for (String version : Arrays.asList("v1", "v2-beta")) {
            ApiContext.getContext().setApiRequest(request(version, "POST", null));
            assertFields(writer.render(factory, bean), true);
            for (String action : Arrays.asList("deactivate", "activate", "remove", "update", "")) {
                ApiContext.getContext().setApiRequest(request(version, "POST", action));
                assertFields(writer.render(factory, bean), false);
            }
            for (String method : Arrays.asList("GET", "PUT", "DELETE")) {
                ApiContext.getContext().setApiRequest(request(version, method, null));
                assertFields(writer.render(factory, bean), false);
            }
        }
        assertEquals("unit-bean-receipt", bean.getCreationReceipt());
    }

    private ApiRequest request(String version, String method, String action) {
        ApiRequest request = new ApiRequest(null, null);
        request.setVersion(version);
        request.setMethod(method);
        request.setAction(action);
        return request;
    }

    private void assertFields(Resource resource, boolean creation) {
        Map<String, Object> fields = resource.getFields();
        if (creation) {
            assertEquals("unit-bean-receipt", fields.get("creationReceipt"));
        } else {
            assertNull(fields.get("creationReceipt"));
        }
        // Redaction follows the schema's o flag, not a hard-coded secret field name.
        assertEquals("unit-ordinary-field", fields.get("secretValue"));
        assertEquals("ordinary-name", fields.get("name"));
    }

    public static class TestBean {
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

    private static class ExposedManager extends AbstractNoOpResourceManager {
        @Override
        public Class<?>[] getTypeClasses() {
            return new Class<?>[0];
        }

        Resource render(SchemaFactory factory, Schema schema, Object obj, ApiRequest request) {
            return super.constructResource(ApiContext.getContext().getIdFormatter(), factory, schema, obj, request);
        }
    }

    private static class ExposedWriter extends JsonResponseWriter {
        Resource render(SchemaFactory factory, Object obj) {
            return super.createResource(factory, obj);
        }
    }
}
