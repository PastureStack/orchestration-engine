package io.cattle.platform.host.api;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class HostApiSettingsTest {

    @After
    public void cleanup() {
        clear("host.api.auth.header");
        clear("host.api.auth.header.value");
        clear("host.api.proxy.host");
        clear("host.api.proxy.backend.path");
        System.clearProperty("host.api.proxy.mode");
    }

    @Test
    public void archaiusSettingsReadDynamicHostApiValues() {
        ConfigurationManager.getConfigInstance().setProperty("host.api.auth.header", "X-Host-Auth-A");
        ConfigurationManager.getConfigInstance().setProperty("host.api.auth.header.value", "Bearer %s");
        ConfigurationManager.getConfigInstance().setProperty("host.api.proxy.host", "proxy-a.example:9443");
        ConfigurationManager.getConfigInstance().setProperty("host.api.proxy.backend.path", "/backend-a");

        HostApiSettings settings = ArchaiusHostApiSettings.create();

        assertEquals("X-Host-Auth-A", settings.authHeader());
        assertEquals("Bearer %s", settings.authHeaderValueFormat());
        assertEquals("proxy-a.example:9443", settings.proxyHost());
        assertEquals("/backend-a", settings.proxyBackendPath());

        ConfigurationManager.getConfigInstance().setProperty("host.api.proxy.host", "proxy-b.example:9443");

        assertEquals("proxy-b.example:9443", settings.proxyHost());
    }

    @Test
    public void hostApiUtilsCompatibilityFieldsReadThroughSettings() throws Exception {
        ConfigurationManager.getConfigInstance().setProperty("host.api.proxy.host", "compat-a.example:9443");
        ConfigurationManager.getConfigInstance().setProperty("host.api.proxy.backend.path", "/compat-a");

        assertEquals("compat-a.example:9443", compatibilityProperty("HOST_API_PROXY_HOST").get());
        assertEquals("/compat-a", compatibilityProperty("HOST_API_PROXY_BACKEND").get());

        ConfigurationManager.getConfigInstance().setProperty("host.api.proxy.backend.path", "/compat-b");

        assertEquals("/compat-b", HostApiUtils.getHostApiProxyBackendPath());
    }

    @Test
    public void injectedSettingsDriveProxyTokenUrl() {
        System.setProperty("host.api.proxy.mode", "ha");

        TestHostApiProxyTokenManager manager = new TestHostApiProxyTokenManager(settings("X-Host-Auth", "Bearer %s",
                "proxy.example:9443", "/host-api-proxy"));
        HostApiProxyTokenImpl token = (HostApiProxyTokenImpl) manager.create(request("https://rancher.example/v1",
                "reported-1"));

        assertEquals("reported-1", token.getReportedUuid());
        assertEquals("token-reported-1", token.getToken());
        assertEquals("wss://proxy.example:9443/host-api-proxy", token.getUrl());
    }

    @Test(expected = IllegalArgumentException.class)
    public void proxyTokenManagerRejectsNullSettings() {
        new HostApiProxyTokenManager(null);
    }

    private static ApiRequest request(String responseUrlBase, String reportedUuid) {
        ApiRequest request = new ApiRequest(null, null);
        request.setResponseUrlBase(responseUrlBase);
        Map<String, Object> requestObject = new HashMap<String, Object>();
        requestObject.put("reportedUuid", reportedUuid);
        request.setRequestObject(requestObject);
        return request;
    }

    private static HostApiSettings settings(final String authHeader, final String authHeaderValueFormat,
            final String proxyHost, final String proxyBackendPath) {
        return new HostApiSettings() {
            @Override
            public String authHeader() {
                return authHeader;
            }

            @Override
            public String authHeaderValueFormat() {
                return authHeaderValueFormat;
            }

            @Override
            public String proxyHost() {
                return proxyHost;
            }

            @Override
            public String proxyBackendPath() {
                return proxyBackendPath;
            }
        };
    }

    private static ConfigProperty<?> compatibilityProperty(String fieldName) throws Exception {
        Field field = HostApiUtils.class.getField(fieldName);
        return ConfigProperty.class.cast(field.get(null));
    }

    private static void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static final class TestHostApiProxyTokenManager extends HostApiProxyTokenManager {
        TestHostApiProxyTokenManager(HostApiSettings settings) {
            super(settings);
        }

        Object create(ApiRequest request) {
            return createInternal("hostApiProxyToken", request);
        }

        @Override
        protected String getToken(String reportedUuid) {
            return "token-" + reportedUuid;
        }

        @Override
        protected void validate(HostApiProxyToken proxyToken) {
        }
    }
}
