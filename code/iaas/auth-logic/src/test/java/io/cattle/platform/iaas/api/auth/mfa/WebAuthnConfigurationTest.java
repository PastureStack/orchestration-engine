package io.cattle.platform.iaas.api.auth.mfa;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import org.junit.Test;

public class WebAuthnConfigurationTest {

    private final WebAuthnService service = new WebAuthnService();

    @Test
    public void acceptsExactHttpsOriginAndRelyingParty() {
        service.validateConfiguration(policy("console.example.test", "https://console.example.test"));
        service.validateConfiguration(policy("example.test", "https://console.example.test:8443"));
    }

    @Test
    public void acceptsHttpOnlyForLoopbackDevelopment() {
        service.validateConfiguration(policy("localhost", "http://localhost:8080"));
        service.validateConfiguration(policy("127.0.0.1", "http://127.0.0.1:8080"));
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsInsecureLanOrigin() {
        service.validateConfiguration(policy("192.0.2.10", "http://192.0.2.10:8080"));
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsUnrelatedRelyingParty() {
        service.validateConfiguration(policy("other.example.test", "https://console.example.test"));
    }

    @Test(expected = ClientVisibleException.class)
    public void rejectsPublicSuffixAsRelyingParty() {
        service.validateConfiguration(policy("co.uk", "https://console.co.uk"));
    }

    private MfaPolicy policy(String rpId, String origin) {
        return new MfaPolicy("optional", 5, rpId, origin, "PastureStack", "PastureStack");
    }
}
