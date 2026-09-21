package io.cattle.platform.iaas.api.auth.integration.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.netflix.config.ConfigurationManager;
import io.cattle.platform.api.auth.Identity;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ExternalServiceTokenUtilIdentityTest {
    private static final String TYPES = "auth.service.external.id.types";
    private ExternalServiceTokenUtil tokenUtil;

    @Before
    public void setUp() {
        ConfigurationManager.getConfigInstance().setProperty(TYPES,
                "github_user,github_org,github_team,shibboleth_user,shibboleth_group,ldap_user,ldap_group");
        tokenUtil = new ExternalServiceTokenUtil();
    }

    @After
    public void tearDown() {
        ConfigurationManager.getConfigInstance().clearProperty(TYPES);
    }

    @Test
    public void preservesTheAuthenticationServiceOidcIdentityContract() {
        Identity user = tokenUtil.jsonToValidatedExternalIdentity(identity(
                "oidc_user", "authentik-user-id", "matrix-owner", true));
        Identity group = tokenUtil.jsonToValidatedExternalIdentity(identity(
                "oidc_group", "authentik-group-id", "matrix-group", false));

        assertEquals("oidc_user", user.getExternalIdType());
        assertEquals("authentik-user-id", user.getExternalId());
        assertEquals("matrix-owner", user.getLogin());
        assertTrue(user.getUser());
        assertEquals("oidc_group", group.getExternalIdType());
    }

    @Test
    public void rejectsMissingAndUnknownTypesBeforeAccountMutation() {
        assertInvalid(identity(null, "subject", "missing-type", true));
        assertInvalid(identity("", "subject", "blank-type", true));
        assertInvalid(identity("rancher_id", "1", "forged-platform-identity", false));
        assertInvalid(identity("arbitrary_external_type", "subject", "unknown-type", false));
    }

    @Test
    public void neverAllowsPlatformIdentityThroughTheExternalTypeSetting() {
        ConfigurationManager.getConfigInstance().setProperty(TYPES, "oidc_user,oidc_group,rancher_id");
        assertInvalid(identity("rancher_id", "1", "forged-platform-identity", false));
    }

    private Map<String, Object> identity(String type, String externalId, String login, boolean user) {
        Map<String, Object> result = new HashMap<>();
        result.put("externalIdType", type);
        result.put("externalId", externalId);
        result.put("name", login);
        result.put("login", login);
        result.put("user", user);
        return result;
    }

    private void assertInvalid(Map<String, Object> data) {
        try {
            tokenUtil.jsonToValidatedExternalIdentity(data);
            fail("Expected unsupported external identity type to be rejected");
        } catch (ClientVisibleException e) {
            assertEquals(400, e.getStatus());
            assertEquals("invalidIdentityType", e.getCode());
        }
    }
}
