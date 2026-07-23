package io.cattle.platform.ssh.common;

import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class SshKeyGenTest {

    @Test
    public void sshRsaTextFormatReadsDynamicConfigThroughWrapper() throws Exception {
        final String key = "ssh.key.text.format";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "ssh-rsa %s rancher-test");

            KeyPair pair = SshKeyGen.generateKeyPair(1024);
            String text = SshKeyGen.sshRsaTextFormat((RSAPublicKey) pair.getPublic());

            assertTrue(text.startsWith("ssh-rsa "));
            assertTrue(text.endsWith(" rancher-test"));
        } finally {
            clearProperty(key);
        }
    }

    @Test
    public void certificateExpirationReadsDynamicConfigThroughWrapper() throws Exception {
        final String key = "cert.expiry.days";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "1");

            KeyPair pair = SshKeyGen.generateKeyPair(1024);
            X509Certificate cert = SshKeyGen.createRootCACert(pair);
            long validityMillis = cert.getNotAfter().getTime() - cert.getNotBefore().getTime();

            assertTrue(validityMillis >= TimeUnit.HOURS.toMillis(23));
            assertTrue(validityMillis <= TimeUnit.HOURS.toMillis(25));
        } finally {
            clearProperty(key);
        }
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }
}
