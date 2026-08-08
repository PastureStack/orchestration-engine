package io.cattle.platform.app;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.extension.impl.ExtensionManagerImpl;
import io.cattle.platform.framework.encryption.handler.impl.TransformationServiceImpl;
import io.cattle.platform.framework.encryption.impl.Aes256Encrypter;
import io.cattle.platform.framework.encryption.impl.Sha256Hasher;
import io.github.ibuildthecloud.gdapi.util.TransformationService;

import org.junit.Test;

public class SystemServicesConfigTest {

    @Test
    public void transformationServiceKeepsBuiltInTransformersBeforeExtensionManagerStart() {
        SystemServicesConfig config = new SystemServicesConfig();
        ExtensionManagerImpl em = new ExtensionManagerImpl();
        Sha256Hasher sha256Hasher = config.sha256Hasher(em);
        Aes256Encrypter aes256Encrypter = config.aes256Encrypter(em);
        TransformationService service = config.transformationService(em, sha256Hasher, aes256Encrypter);
        TransformationServiceImpl impl = TransformationServiceImpl.class.cast(service);

        sha256Hasher.init();
        String passwordHash = "SHA256:" + sha256Hasher.transform("rc16-password");

        assertSame(sha256Hasher, impl.getTransformers().get("SHA256"));
        assertSame(aes256Encrypter, impl.getTransformers().get("AES256"));
        assertTrue(service.compare("rc16-password", passwordHash));
    }
}
