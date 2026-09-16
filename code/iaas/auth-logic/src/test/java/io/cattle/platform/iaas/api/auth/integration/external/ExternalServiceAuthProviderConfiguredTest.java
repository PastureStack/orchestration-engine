package io.cattle.platform.iaas.api.auth.integration.external;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.netflix.config.ConfigurationManager;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import java.util.Arrays;
import org.junit.After;
import org.junit.Test;

public class ExternalServiceAuthProviderConfiguredTest {
    private static final String EXTERNAL = ServiceAuthConstants.EXTERNAL_AUTH_PROVIDER_SETTING;
    private static final String PROVIDER = SecurityConstants.AUTH_PROVIDER_SETTING;

    @After
    public void tearDown() {
        for (String key : Arrays.asList(EXTERNAL, PROVIDER)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    @Test
    public void restoresConfiguredStateFromDynamicSettings() {
        ExternalServiceAuthProvider provider = new ExternalServiceAuthProvider();

        ConfigurationManager.getConfigInstance().setProperty(PROVIDER, "oidcconfig");
        ConfigurationManager.getConfigInstance().setProperty(EXTERNAL, true);
        assertTrue(provider.isConfigured());

        ConfigurationManager.getConfigInstance().setProperty(EXTERNAL, false);
        assertFalse(provider.isConfigured());

        ConfigurationManager.getConfigInstance().setProperty(EXTERNAL, true);
        ConfigurationManager.getConfigInstance().setProperty(PROVIDER, SecurityConstants.NO_PROVIDER);
        assertFalse(provider.isConfigured());
    }
}
