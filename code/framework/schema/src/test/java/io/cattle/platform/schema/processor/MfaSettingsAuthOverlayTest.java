package io.cattle.platform.schema.processor;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import org.junit.Test;

/** Exercise the shipped user + admin overlays in their actual runtime order. */
public class MfaSettingsAuthOverlayTest {
    private final Path schemas = Paths.get("../../../resources/content/schema");
    private final SchemaFactory factory = (SchemaFactory) Proxy.newProxyInstance(
            SchemaFactory.class.getClassLoader(), new Class<?>[] {SchemaFactory.class},
            (proxy, method, args) -> null);

    private SchemaImpl base() throws Exception {
        return base("mfaSettings");
    }

    private SchemaImpl base(String type) throws Exception {
        JacksonMapper mapper = new JacksonMapper();
        mapper.init();
        SchemaImpl schema = mapper.readValue(
                Files.readAllBytes(schemas.resolve("base/" + type + ".json")), SchemaImpl.class);
        schema.setId(type);
        return schema;
    }

    private AuthOverlayPostProcessor overlay(String... roles) throws Exception {
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        for (String role : roles) {
            Map<?, ?> document = new ObjectMapper().readValue(
                    schemas.resolve(role + "/" + role + "-auth.json").toFile(), Map.class);
            processor.load((Map<?, ?>) document.get("authorize"));
        }
        return processor;
    }

    @Test
    public void administratorCanUpdateEveryPolicyWithoutCreatingOrDeletingSettings() throws Exception {
        SchemaImpl expected = base();
        SchemaImpl actual = base();
        AuthOverlayPostProcessor processor = overlay("user", "admin");
        assertSame(actual, processor.postProcessRegister(actual, factory));
        processor.postProcess(actual, factory);
        assertEquals(Arrays.asList("GET"), actual.getCollectionMethods());
        assertEquals(new HashSet<String>(Arrays.asList("GET", "PUT")),
                new HashSet<String>(actual.getResourceMethods()));
        assertEquals(37, actual.getResourceFields().size());
        assertEquals(expected.getResourceFields().keySet(), actual.getResourceFields().keySet());
        for (Map.Entry<String, Field> entry : expected.getResourceFields().entrySet()) {
            Field field = actual.getResourceFields().get(entry.getKey());
            assertFalse(entry.getKey(), field.isCreate());
            assertEquals(entry.getKey(), entry.getValue().isUpdate(), field.isUpdate());
            assertEquals(entry.getKey(), entry.getValue().getType(), field.getType());
        }
        for (String name : Arrays.asList("smtpPassword", "smtpClearPassword", "securityConfirmation")) {
            assertTrue(name, actual.getResourceFields().get(name).isReadOnCreateOnly());
        }
    }

    @Test
    public void ordinaryUserCannotAccessGlobalSettings() throws Exception {
        assertNull(overlay("user").postProcessRegister(base(), factory));
    }

    @Test
    public void userAndAdministratorKeepEveryStepUpInputAndReadOnlyResult() throws Exception {
        for (String[] roles : new String[][] {{"user"}, {"user", "admin"}}) {
            SchemaImpl schema = base("mfaOperation");
            SchemaImpl expected = base("mfaOperation");
            overlay(roles).postProcess(schema, factory);
            assertEquals(Arrays.toString(roles), expected.getResourceFields().keySet(),
                    schema.getResourceFields().keySet());
            for (String name : Arrays.asList("method", "recoveryCode", "securityConfirmation")) {
                assertTrue(name, schema.getResourceFields().get(name).isCreate());
                assertFalse(name, schema.getResourceFields().get(name).isUpdate());
            }
            for (String name : Arrays.asList("methods", "webAuthnOptions", "recoveryEmailMasked", "emailCodeSent")) {
                assertFalse(name, schema.getResourceFields().get(name).isCreate());
                assertFalse(name, schema.getResourceFields().get(name).isUpdate());
            }
        }
    }

    @Test
    public void adjacentIdentityAndFactorSchemasRetainTheirDeclaredFields() throws Exception {
        for (String type : Arrays.asList("authIdentityOperation", "authIdentityLink", "mfaStatus", "mfaFactor")) {
            SchemaImpl schema = base(type);
            SchemaImpl expected = base(type);
            overlay("user", "admin").postProcess(schema, factory);
            assertEquals(type, expected.getResourceFields().keySet(), schema.getResourceFields().keySet());
        }
    }
}
