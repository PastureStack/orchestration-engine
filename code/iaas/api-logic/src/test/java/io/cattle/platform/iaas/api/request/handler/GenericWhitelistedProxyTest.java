package io.cattle.platform.iaas.api.request.handler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import org.junit.Test;

public class GenericWhitelistedProxyTest {

    @Test
    public void doesNotParseFormBodiesUnlessLegacyParseFormFlagIsSet() {
        assertFalse(GenericWhitelistedProxy.shouldParseFormContent(false,
                "application/x-www-form-urlencoded"));
        assertFalse(GenericWhitelistedProxy.shouldParseFormContent(false,
                "application/x-www-form-urlencoded; charset=UTF-8"));
    }

    @Test
    public void parsesFormBodiesWhenLegacyParseFormFlagIsSet() {
        assertTrue(GenericWhitelistedProxy.shouldParseFormContent(true,
                "application/x-www-form-urlencoded"));
        assertTrue(GenericWhitelistedProxy.shouldParseFormContent(true,
                "application/x-www-form-urlencoded; charset=UTF-8"));
    }

    @Test
    public void ignoresNonFormContentTypes() {
        assertFalse(GenericWhitelistedProxy.shouldParseFormContent(true, null));
        assertFalse(GenericWhitelistedProxy.shouldParseFormContent(true, ""));
        assertFalse(GenericWhitelistedProxy.shouldParseFormContent(true, "application/json"));
        assertFalse(GenericWhitelistedProxy.shouldParseFormContent(true, "application/octet-stream"));
    }

    @Test
    public void followsRedirectsOnlyWhenExplicitlyEnabled() {
        assertFalse(GenericWhitelistedProxy.shouldFollowRedirects(null));
        assertFalse(GenericWhitelistedProxy.shouldFollowRedirects(Boolean.FALSE));
        assertFalse(GenericWhitelistedProxy.shouldFollowRedirects("true"));
        assertTrue(GenericWhitelistedProxy.shouldFollowRedirects(Boolean.TRUE));
    }

    @Test
    public void onlyAllowsHttpAndHttpsProxySchemes() {
        assertTrue(GenericWhitelistedProxy.isProxyableScheme("http"));
        assertTrue(GenericWhitelistedProxy.isProxyableScheme("https"));
        assertTrue(GenericWhitelistedProxy.isProxyableScheme("HTTPS"));
        assertFalse(GenericWhitelistedProxy.isProxyableScheme(null));
        assertFalse(GenericWhitelistedProxy.isProxyableScheme(""));
        assertFalse(GenericWhitelistedProxy.isProxyableScheme("httpx"));
        assertFalse(GenericWhitelistedProxy.isProxyableScheme("file"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingProxySettings() {
        new GenericWhitelistedProxy("proxy", null);
    }

    @Test
    public void usesInjectedWhitelistForExactHostMatches() {
        GenericWhitelistedProxy proxy = new GenericWhitelistedProxy("proxy",
                settings("registry.example:5000", "metadata.example"));

        assertTrue(proxy.isWhitelisted("registry.example:5000"));
        assertTrue(proxy.isWhitelisted("metadata.example"));
        assertFalse(proxy.isWhitelisted("untrusted.example"));
    }

    @Test
    public void usesInjectedWhitelistForWildcardHostMatches() {
        GenericWhitelistedProxy proxy = new GenericWhitelistedProxy("proxy",
                settings("*.rancher.internal"));

        assertTrue(proxy.isWhitelisted("api.rancher.internal"));
        assertTrue(proxy.isWhitelisted("nested.api.rancher.internal"));
        assertFalse(proxy.isWhitelisted("rancher.internal"));
        assertFalse(proxy.isWhitelisted("api.rancher.internal.evil.example"));
    }

    @Test
    public void authorizeAllowsMatchingRolesFromWildcardRequiredSet() {
        Set<Object> requiredRoles = new HashSet<>();
        requiredRoles.add("admin");
        requiredRoles.add(Integer.valueOf(1));

        GenericWhitelistedProxy.authorize("GET", requiredRoles, Collections.singleton("admin"), null);
    }

    @Test
    public void authorizeSkipsRoleCheckWhenMethodDoesNotMatch() {
        GenericWhitelistedProxy.authorize("GET", Collections.singleton("admin"), Collections.<String>emptySet(),
                Collections.singleton("POST"));
    }

    @Test(expected = ClientVisibleException.class)
    public void authorizeRejectsMissingRoleForMatchingMethod() {
        GenericWhitelistedProxy.authorize("POST", Collections.singleton("admin"), Collections.<String>emptySet(),
                Collections.singleton("POST"));
    }

    private static ProxySettings settings(String... whitelist) {
        return new TestProxySettings(Arrays.asList(whitelist));
    }

    private static final class TestProxySettings implements ProxySettings {
        private final List<String> whitelist;

        TestProxySettings(List<String> whitelist) {
            this.whitelist = whitelist;
        }

        @Override
        public boolean allowProxy() {
            return true;
        }

        @Override
        public List<String> whitelist() {
            return whitelist;
        }

        @Override
        public long connectTimeoutMillis() {
            return 1000;
        }

        @Override
        public long requestTimeoutMillis() {
            return 1000;
        }
    }
}
