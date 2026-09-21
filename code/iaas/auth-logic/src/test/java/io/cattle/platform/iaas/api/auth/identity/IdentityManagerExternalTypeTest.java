package io.cattle.platform.iaas.api.auth.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.netflix.config.ConfigurationManager;
import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.iaas.api.auth.integration.external.ExternalServiceAuthProvider;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class IdentityManagerExternalTypeTest {
    private static final String TYPES = "auth.service.external.id.types";
    private IdentityManager manager;

    @Before
    public void setUp() {
        ConfigurationManager.getConfigInstance().setProperty(TYPES, "oidc_user,oidc_group");
        manager = new IdentityManager();
        manager.setIdentityProviders(Collections.emptyMap());
        manager.externalAuthProvider = externalProvider(true);
    }

    @After
    public void tearDown() {
        ConfigurationManager.getConfigInstance().clearProperty(TYPES);
    }

    @Test
    public void acceptsSupportedOidcUserAndGroupTypes() {
        for (String type : new String[] {"oidc_user", "oidc_group"}) {
            Identity identity = new Identity(type, "subject");
            assertEquals(identity, manager.projectMemberToIdentity(identity));
            assertEquals(identity, manager.untransform(identity, true));
        }
    }

    @Test
    public void acceptsOidcTypesWhenAnOlderDatabaseOverrideOmitsThem() {
        ConfigurationManager.getConfigInstance().setProperty(TYPES,
                "github_user,github_org,github_team,shibboleth_user,shibboleth_group,ldap_user,ldap_group");

        for (String type : new String[] {"oidc_user", "oidc_group"}) {
            Identity identity = new Identity(type, "subject");
            assertEquals(identity, manager.projectMemberToIdentity(identity));
            assertEquals(identity, manager.untransform(identity, true));
        }
    }

    @Test
    public void rejectsUnknownExternalTypeEvenWhenProviderIsConfigured() {
        Identity identity = new Identity("arbitrary_external_type", "subject");
        assertInvalidType(() -> manager.projectMemberToIdentity(identity));
        assertInvalidType(() -> manager.untransform(identity, true));
    }

    @Test
    public void externalTokenBoundaryDoesNotRecheckAStaleProviderFlag() {
        manager.externalAuthProvider = externalProvider(false);

        for (String type : new String[] {"oidc_user", "oidc_group"}) {
            Identity identity = new Identity(type, "subject");
            assertEquals(identity, manager.untransformExternalTokenIdentity(identity));
            assertInvalidType(() -> manager.untransform(identity, true));
        }
    }

    @Test
    public void externalTokenBoundaryStillRejectsUnknownTypes() {
        manager.externalAuthProvider = externalProvider(false);
        assertInvalidType(() -> manager.untransformExternalTokenIdentity(
                new Identity("arbitrary_external_type", "subject")));
    }

    private void assertInvalidType(Runnable action) {
        try {
            action.run();
            fail("Expected unsupported external identity type to be rejected");
        } catch (ClientVisibleException e) {
            assertEquals(400, e.getStatus());
            assertEquals("invalidIdentityType", e.getCode());
        }
    }

    private ExternalServiceAuthProvider externalProvider(final boolean configured) {
        return new ExternalServiceAuthProvider() {
            @Override
            public boolean isConfigured() {
                return configured;
            }

            @Override
            public Identity transform(Identity identity) {
                return identity;
            }

            @Override
            public Identity untransform(Identity identity) {
                return identity;
            }
        };
    }
}
