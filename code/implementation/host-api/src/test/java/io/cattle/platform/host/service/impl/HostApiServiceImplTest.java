package io.cattle.platform.host.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.core.constants.HostConstants;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.host.api.HostApiSettings;
import io.cattle.platform.host.model.HostApiAccess;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.token.TokenException;
import io.cattle.platform.token.TokenService;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.Proxy;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

public class HostApiServiceImplTest {

    @After
    public void cleanup() {
        System.clearProperty("host.api.proxy.mode");
    }

    @Test
    public void injectedSettingsDriveAuthHeadersAndHaProxyUrl() {
        System.setProperty("host.api.proxy.mode", "ha");

        Host host = host("host-uuid", data(DataUtils.FIELDS, data(HostConstants.FIELD_REPORTED_UUID, "reported-host-uuid")));
        CapturingTokenService tokenService = new CapturingTokenService("token-1");
        HostApiServiceImpl service = new HostApiServiceImpl(settings("X-Host-Auth", "Bearer %s",
                "proxy.example:9443", "/host-api-proxy"));
        service.setObjectManager(objectManager(host));
        service.setTokenService(tokenService);

        Date expiration = new Date(System.currentTimeMillis() + 60000L);
        HostApiAccess access = service.getAccess(request("https://rancher.example/v1"), 42L, data("scope", "logs"),
                expiration, "/v1/logs/");

        assertEquals("wss://proxy.example:9443/v1/logs/", access.getUrl());
        assertEquals("token-1", access.getAuthenticationToken());
        assertEquals("Bearer token-1", access.getHeaders().get("X-Host-Auth"));
        assertEquals("logs", tokenService.payload.get("scope"));
        assertEquals("reported-host-uuid", tokenService.payload.get("hostUuid"));
        assertSame(expiration, tokenService.expiration);
    }

    @Test
    public void fallsBackToEmbeddedProxyUrlWhenHaProxyHostIsBlank() {
        System.setProperty("host.api.proxy.mode", "ha");

        HostApiServiceImpl service = new HostApiServiceImpl(settings("X-Host-Auth", "Token %s", "", "/host-api-proxy"));
        service.setObjectManager(objectManager(host()));
        service.setTokenService(new CapturingTokenService("token-2"));

        HostApiAccess access = service.getAccess(request("http://rancher.example/v1"), 42L, data("scope", "socket"),
                "dockersocket");

        assertEquals("ws://rancher.example/v1/dockersocket", access.getUrl());
        assertEquals("Token token-2", access.getHeaders().get("X-Host-Auth"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSettings() {
        new HostApiServiceImpl(null);
    }

    private static Host host() {
        return host("host-uuid", new HashMap<String, Object>());
    }

    private static Host host(final String uuid, final Map<String, Object> data) {
        return (Host) Proxy.newProxyInstance(Host.class.getClassLoader(), new Class<?>[] { Host.class },
                (proxy, method, args) -> {
                    if ("getId".equals(method.getName())) {
                        return 42L;
                    }
                    if ("getUuid".equals(method.getName())) {
                        return uuid;
                    }
                    if ("getData".equals(method.getName())) {
                        return data;
                    }
                    if ("setData".equals(method.getName())) {
                        data.clear();
                        if (args != null && args.length > 0 && args[0] instanceof Map) {
                            Map<?, ?> newData = Map.class.cast(args[0]);
                            for (Map.Entry<?, ?> entry : newData.entrySet()) {
                                data.put(String.class.cast(entry.getKey()), entry.getValue());
                            }
                        }
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ObjectManager objectManager(final Host host) {
        return (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(), new Class<?>[] { ObjectManager.class },
                (proxy, method, args) -> {
                    if ("loadResource".equals(method.getName())) {
                        return host;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ApiRequest request(String responseUrlBase) {
        ApiRequest request = new ApiRequest(null, null);
        request.setResponseUrlBase(responseUrlBase);
        return request;
    }

    private static Map<String, Object> data(String key, Object value) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put(key, value);
        return data;
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

    private static final class CapturingTokenService implements TokenService {
        private final String token;
        private Map<String, Object> payload;
        private Date expiration;

        CapturingTokenService(String token) {
            this.token = token;
        }

        @Override
        public String generateToken(Map<String, Object> payload) {
            this.payload = payload;
            this.expiration = null;
            return token;
        }

        @Override
        public String generateToken(Map<String, Object> payload, Date expireDate) {
            this.payload = payload;
            this.expiration = expireDate;
            return token;
        }

        @Override
        public String generateEncryptedToken(Map<String, Object> payload) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String generateEncryptedToken(Map<String, Object> payload, Date expireDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> getJsonPayload(String token, boolean encrypted) throws TokenException {
            throw new UnsupportedOperationException();
        }
    }
}
