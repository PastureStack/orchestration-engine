package io.cattle.platform.bootstrap.script;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.iaas.api.request.handler.ScriptsHandler;
import io.cattle.platform.token.impl.RSAKeyProvider;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.Certificate;

import jakarta.inject.Inject;

import org.apache.commons.io.IOUtils;

public class BootstrapScriptsHandler implements ScriptsHandler {

    private static final ConfigProperty<String> BOOTSTRAP_SOURCE = ArchaiusUtil.getStringProperty("bootstrap.source");
    private static final ConfigProperty<String> REQUIRED_IMAGE = ArchaiusUtil.getStringProperty("bootstrap.required.image");

    private static final String BOOTSTRAP = "bootstrap";

    @Inject
    RSAKeyProvider keyProvider;

    @Override
    public boolean handle(ApiRequest request) throws IOException {
        if (!BOOTSTRAP.equals(request.getId())) {
            return false;
        }

        byte[] content = getBootstrapSource(request);
        IOUtils.copy(new ByteArrayInputStream(content), request.getServletContext().getResponse().getOutputStream());

        return true;
    }

    protected byte[] getBootstrapSource(ApiRequest apiRequest) throws IOException {
        ClassLoader cl = BootstrapScriptsHandler.class.getClassLoader();
        Certificate cert = keyProvider.getCACertificate();
        byte[] pem = keyProvider.toBytes(cert);
        try (InputStream is = cl.getResourceAsStream(BOOTSTRAP_SOURCE.get())) {
            String content = IOUtils.toString(is, java.nio.charset.StandardCharsets.UTF_8);
            content = content.replace("REQUIRED_IMAGE=", String.format("REQUIRED_IMAGE=\"%s\"", REQUIRED_IMAGE.get()));
            content = content.replace("DETECTED_CATTLE_AGENT_IP=", String.format("DETECTED_CATTLE_AGENT_IP=\"%s\"", apiRequest.getClientIp()));
            content = content.replace("%CERT%", new String(pem, "UTF-8"));
            return content.getBytes("UTF-8");
        }
    }

}
