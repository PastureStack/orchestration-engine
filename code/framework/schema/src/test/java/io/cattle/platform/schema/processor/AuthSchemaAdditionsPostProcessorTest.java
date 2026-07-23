package io.cattle.platform.schema.processor;

import static org.junit.Assert.assertEquals;

import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class AuthSchemaAdditionsPostProcessorTest {

    @Test
    public void externalIdTypesReadDynamicListThroughWrapper() {
        final String key = "auth.service.external.id.types";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "ldap,github");

            SchemaImpl schema = new SchemaImpl();
            schema.setId("projectMember");
            FieldImpl field = new FieldImpl();
            field.setOptions(new ArrayList<String>());
            schema.getResourceFields().put("externalIdType", field);

            new AuthSchemaAdditionsPostProcessor().postProcess(schema, null);

            assertEquals(Arrays.asList("ldap", "github"), field.getOptions());
        } finally {
            if (ConfigurationManager.getConfigInstance().containsKey(key)) {
                ConfigurationManager.getConfigInstance().clearProperty(key);
            }
        }
    }
}
