package io.cattle.platform.schema.processor;

import static org.junit.Assert.assertEquals;

import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.InputStream;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class AuthSchemaAdditionsPostProcessorTest {

    @Test
    public void shippedDefaultsExposeOnlyReviewedExternalIdentityTypesIncludingOidc() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(
                Paths.get("../../../resources/content/cattle-global.properties"))) {
            properties.load(input);
        }
        List<String> types = Arrays.asList(
                properties.getProperty("auth.service.external.id.types").split(","));

        assertEquals(Arrays.asList("github_user", "github_org", "github_team",
                "shibboleth_user", "shibboleth_group", "ldap_user", "ldap_group",
                "oidc_user", "oidc_group"), types);
    }

    @Test
    public void externalIdTypesReadDynamicListThroughWrapper() {
        final String key = "auth.service.external.id.types";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "ldap,github,ldap");

            SchemaImpl schema = new SchemaImpl();
            schema.setId("projectMember");
            FieldImpl field = new FieldImpl();
            field.setOptions(new ArrayList<String>(Arrays.asList("rancher_id", "ldap")));
            schema.getResourceFields().put("externalIdType", field);

            new AuthSchemaAdditionsPostProcessor().postProcess(schema, null);

            assertEquals(Arrays.asList("rancher_id", "ldap", "github"), field.getOptions());
        } finally {
            if (ConfigurationManager.getConfigInstance().containsKey(key)) {
                ConfigurationManager.getConfigInstance().clearProperty(key);
            }
        }
    }
}
