package io.cattle.platform.docker.machine.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.net.URI;

import org.junit.Test;

public class LocalReloadRequestTest {

    @Test
    public void normalizesSupportedLoopbackServiceUrl() {
        URI normalized = LocalReloadRequest.normalizeLoopback(
                URI.create("http://localhost:8090/v1-auth"), "/reload");

        assertEquals("http://127.0.0.1:8090/v1-auth/reload", normalized.toString());
    }

    @Test
    public void preservesTlsAndQueryForLoopbackHealthRequest() {
        URI normalized = LocalReloadRequest.normalizeLoopback(
                URI.create("https://[::1]:9443/api?ready=true"), "");

        assertEquals("https://127.0.0.1:9443/api?ready=true", normalized.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRemoteReloadTarget() {
        LocalReloadRequest.normalizeLoopback(URI.create("http://169.254.169.254/latest/meta-data"), "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUserInfoThatCouldObscureAuthority() {
        LocalReloadRequest.normalizeLoopback(URI.create("http://localhost@evil.example/reload"), "");
    }

    @Test
    public void readinessCheckFailsClosedForRemoteTarget() {
        assertFalse(LocalReloadRequest.isGetSuccessful(URI.create("http://example.com/")));
    }
}
