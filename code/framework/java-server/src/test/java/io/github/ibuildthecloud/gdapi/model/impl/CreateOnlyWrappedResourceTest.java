package io.github.ibuildthecloud.gdapi.model.impl;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.RequestUtils;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class CreateOnlyWrappedResourceTest {
    private final IdFormatter ids = new IdFormatter() {
        public Object formatId(String type, Object id) { return id; }
        public String parseId(String id) { return id; }
        public IdFormatter withSchemaFactory(SchemaFactory factory) { return this; }
    };

    private SchemaImpl schema(boolean createOnly) {
        SchemaImpl schema = new SchemaImpl();
        schema.setId("apiKey");
        FieldImpl field = new FieldImpl();
        field.setType("string");
        field.setReadOnCreateOnly(createOnly);
        schema.getResourceFields().put("secretValue", field);
        FieldImpl name = new FieldImpl();
        name.setType("string");
        schema.getResourceFields().put("name", name);
        return schema;
    }

    private Map<String, Object> values() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("secretValue", "unit-test-sentinel");
        values.put("name", "ordinary-name");
        return values;
    }

    private Map<String, Object> render(String method, String action, String version, boolean createOnly) {
        ApiRequest request = new ApiRequest(null, null);
        request.setMethod(method);
        request.setAction(action);
        request.setVersion(version);
        return new WrappedResource(ids, new SchemaFactoryImpl(), schema(createOnly), null, values(), null,
                request.getMethod(), RequestUtils.isCreateRequest(request)).getFields();
    }

    @Test
    public void trueCreationRetainsTheFirstSecretInBothApiVersions() {
        for (String version : new String[] {"v1", "v2-beta"}) {
            assertEquals("unit-test-sentinel", render("POST", null, version, true).get("secretValue"));
        }
    }

    @Test
    public void postActionsCannotRevealCreateOnlyValues() {
        for (String version : new String[] {"v1", "v2-beta"}) {
            for (String action : new String[] {"deactivate", "activate", "remove", "update", "", "unknown"}) {
                Map<String, Object> result = render("POST", action, version, true);
                assertNull(result.get("secretValue"));
                assertEquals("ordinary-name", result.get("name"));
            }
        }
    }

    @Test
    public void readUpdateDeleteAndUnknownMethodsStillRedact() {
        for (String method : new String[] {"GET", "PUT", "DELETE", "HEAD", "OPTIONS", "UNKNOWN", null}) {
            assertNull(render(method, null, "v2-beta", true).get("secretValue"));
            // A caller cannot make a non-POST request expose creation fields.
            assertNull(new WrappedResource(ids, new SchemaFactoryImpl(), schema(true), null, values(), null,
                    method, true).getFields().get("secretValue"));
        }
    }

    @Test
    public void rolesWithoutCreateOnlyRestrictionKeepTheirExistingReadContract() {
        for (String method : new String[] {"GET", "PUT", "DELETE", "POST"}) {
            assertEquals("unit-test-sentinel", render(method, "deactivate", "v1", false).get("secretValue"));
        }
    }

    @Test
    public void methodOnlyLegacyConstructorsFailClosedInsteadOfGuessingCreation() {
        assertNull(new WrappedResource(ids, new SchemaFactoryImpl(), schema(true), null, values(), null,
                "POST").getFields().get("secretValue"));
        assertNull(new WrappedResource(ids, new SchemaFactoryImpl(), schema(true), null, "POST")
                .getFields().get("secretValue"));
    }

    @Test
    public void priorityFieldsCannotBypassTheCreateOnlyBoundary() {
        Map<String, Object> result = new WrappedResource(ids, new SchemaFactoryImpl(), schema(true), null,
                values(), java.util.Collections.singleton("secretValue"), "POST", false).getFields();
        assertNull(result.get("secretValue"));
        assertEquals("ordinary-name", result.get("name"));
    }
}
