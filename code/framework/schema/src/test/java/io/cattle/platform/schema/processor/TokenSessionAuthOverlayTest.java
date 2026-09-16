package io.cattle.platform.schema.processor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.Test;

/** Verifies the shipped token overlay keeps the session ownership input. */
public class TokenSessionAuthOverlayTest {
    private final Path schemas = Paths.get("../../../resources/content/schema");
    private final SchemaFactory factory = (SchemaFactory) Proxy.newProxyInstance(
            SchemaFactory.class.getClassLoader(), new Class<?>[] {SchemaFactory.class},
            (proxy, method, args) -> null);

    @Test
    public void clientSessionIdSurvivesAuthorizationAsCreateOnlyInput() throws Exception {
        SchemaImpl schema = new SchemaImpl();
        schema.setId("token");
        schema.getResourceFields().put("clientSessionId", new FieldImpl());
        schema.getResourceFields().put("jwt", new FieldImpl());

        Map<?, ?> document = new ObjectMapper().readValue(
                schemas.resolve("token/token-auth.json").toFile(), Map.class);
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        processor.load((Map<?, ?>) document.get("authorize"));
        processor.postProcess(schema, factory);

        Field clientSessionId = schema.getResourceFields().get("clientSessionId");
        assertNotNull(clientSessionId);
        assertTrue(clientSessionId.isCreate());
        assertFalse(clientSessionId.isUpdate());
        assertTrue(clientSessionId.isReadOnCreateOnly());
        assertFalse(schema.getResourceFields().get("jwt").isCreate());
    }
}
