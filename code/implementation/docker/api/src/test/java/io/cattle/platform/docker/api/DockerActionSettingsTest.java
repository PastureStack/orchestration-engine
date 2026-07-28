package io.cattle.platform.docker.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.util.SystemLabels;
import io.cattle.platform.docker.api.model.HostAccess;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.host.model.HostApiAccess;
import io.cattle.platform.host.service.HostApiService;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.Proxy;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class DockerActionSettingsTest {

    @After
    public void cleanup() {
        clear("host.proxy.path");
        clear("host.proxy.jwt.expiration.seconds");
        clear("host.logs.path");
        clear("host.socketproxy.path");
        clear("exec.agent.path");
        ApiContext.remove();
    }

    @Test
    public void archaiusSettingsReadDynamicHostActionValues() {
        ConfigurationManager.getConfigInstance().setProperty("host.proxy.path", "/proxy-a/");
        ConfigurationManager.getConfigInstance().setProperty("host.proxy.jwt.expiration.seconds", "45");
        ConfigurationManager.getConfigInstance().setProperty("host.logs.path", "/logs-a/");
        ConfigurationManager.getConfigInstance().setProperty("host.socketproxy.path", "/socket-a/");
        ConfigurationManager.getConfigInstance().setProperty("exec.agent.path", "/exec-a/");

        DockerActionSettings settings = ArchaiusDockerActionSettings.create();

        assertEquals("/proxy-a/", settings.hostProxyPath());
        assertEquals(45, settings.hostProxyJwtExpirationSeconds());
        assertEquals("/logs-a/", settings.hostLogsPath());
        assertEquals("/socket-a/", settings.hostSocketProxyPath());
        assertEquals("/exec-a/", settings.execAgentPath());

        ConfigurationManager.getConfigInstance().setProperty("host.logs.path", "/logs-b/");

        assertEquals("/logs-b/", settings.hostLogsPath());
    }

    @Test
    public void injectedSettingsDriveHostAccessPaths() {
        DockerActionSettings settings = settings();

        assertEquals("/logs-test/", performLogs(settings).paths[0]);
        assertEquals("/exec-test/", performExec(settings).paths[0]);
        assertEquals("/socket-test/", performSocket(settings).paths[0]);
        assertEquals("/proxy-test/", performProxy(settings).paths[0]);
    }

    @Test
    public void containerProxyPreservesAccessPayloadAndExpiration() {
        long before = System.currentTimeMillis();

        CapturingHostApiService service = performProxy(settings());

        assertEquals(Long.valueOf(42L), service.hostId);
        assertNotNull(service.expiration);
        assertTrue(service.expiration.getTime() >= before + 60_000L);

        Map<?, ?> proxy = capturedData(service, "proxy");
        assertEquals("docker-id", proxy.get(DockerInstanceConstants.DOCKER_CONTAINER));
        assertEquals("https", proxy.get("scheme"));
        assertEquals("10.1.2.3:9443", proxy.get("address"));
    }

    @Test
    public void nullSettingsAreRejected() {
        expectIllegalArgument(() -> new ContainerLogsActionHandler(null));
        expectIllegalArgument(() -> new ExecActionHandler(null));
        expectIllegalArgument(() -> new DockerSocketProxyActionHandler(null));
        expectIllegalArgument(() -> new ContainerProxyActionHandler(null));
    }

    @Test
    public void socketHandlerReturnsNullForMissingHostObject() {
        DockerSocketProxyActionHandler handler = new DockerSocketProxyActionHandler(settings());
        handler.setApiService(new CapturingHostApiService("ws://agent/socket", "socket-token"));

        assertNull(handler.perform("host.dockersocket", null, request(Collections.<String, Object>emptyMap())));
    }

    private static CapturingHostApiService performLogs(DockerActionSettings settings) {
        CapturingHostApiService service = new CapturingHostApiService("ws://agent/logs", "logs-token");
        ContainerLogsActionHandler handler = new ContainerLogsActionHandler(settings);
        handler.setApiService(service);
        handler.setObjectManager(objectManager(host()));

        HostAccess access = (HostAccess) handler.perform("instance.logs", instance(), request(map("lines", 25,
                "follow", false, "timestamps", true, "since", "2026-05-06T00:00:00Z")));

        assertEquals("ws://agent/logs", access.getUrl());
        assertEquals("logs-token", access.getToken());
        Map<?, ?> logs = capturedData(service, "logs");
        assertEquals("docker-id", logs.get(DockerInstanceConstants.DOCKER_CONTAINER));
        assertEquals(25, logs.get("Lines"));
        assertEquals(false, logs.get("Follow"));
        assertEquals(true, logs.get("Timestamps"));
        assertEquals("2026-05-06T00:00:00Z", logs.get("Since"));

        return service;
    }

    private static CapturingHostApiService performExec(DockerActionSettings settings) {
        CapturingHostApiService service = new CapturingHostApiService("ws://agent/exec", "exec-token");
        ExecActionHandler handler = new ExecActionHandler(settings);
        handler.setApiService(service);
        handler.setObjectManager(objectManager(host()));

        HostAccess access = (HostAccess) handler.perform("instance.execute", instance(), request(map("attachStdin", true,
                "attachStdout", true, "tty", false, "command", Arrays.asList("sh", "-c", "id"))));

        assertEquals("ws://agent/exec", access.getUrl());
        assertEquals("exec-token", access.getToken());
        Map<?, ?> exec = capturedData(service, "exec");
        assertEquals("docker-id", exec.get(DockerInstanceConstants.DOCKER_CONTAINER));
        assertEquals(true, exec.get(DockerInstanceConstants.DOCKER_ATTACH_STDIN));
        assertEquals(true, exec.get(DockerInstanceConstants.DOCKER_ATTACH_STDOUT));
        assertEquals(false, exec.get(DockerInstanceConstants.DOCKER_TTY));
        assertEquals(Arrays.asList("sh", "-c", "id"), exec.get(DockerInstanceConstants.DOCKER_CMD));

        return service;
    }

    private static CapturingHostApiService performSocket(DockerActionSettings settings) {
        CapturingHostApiService service = new CapturingHostApiService("ws://agent/socket", "socket-token");
        DockerSocketProxyActionHandler handler = new DockerSocketProxyActionHandler(settings);
        handler.setApiService(service);

        HostAccess access = (HostAccess) handler.perform("host.dockersocket", host(), request(Collections.<String, Object>emptyMap()));

        assertEquals("ws://agent/socket", access.getUrl());
        assertEquals("socket-token", access.getToken());
        assertTrue(service.data.isEmpty());

        return service;
    }

    private static CapturingHostApiService performProxy(DockerActionSettings settings) {
        CapturingHostApiService service = new CapturingHostApiService("ws://agent/proxy", "proxy-token");
        ContainerProxyActionHandler handler = new ContainerProxyActionHandler(settings);
        handler.apiService = service;
        handler.objectManager = objectManager(host());

        HostAccess access = (HostAccess) handler.perform("instance.proxy", proxyInstance(), request(Collections.<String, Object>emptyMap()));

        assertEquals("http://agent/proxy", access.getUrl());
        assertEquals("proxy-token", access.getToken());

        return service;
    }

    private static InstanceRecord instance() {
        InstanceRecord instance = new InstanceRecord();
        instance.setUuid("instance-uuid");
        instance.setExternalId("docker-id");
        instance.setData(new HashMap<String, Object>());
        return instance;
    }

    private static InstanceRecord proxyInstance() {
        InstanceRecord instance = instance();
        DataAccessor.setField(instance, InstanceConstants.FIELD_PRIMARY_IP_ADDRESS, "10.1.2.3");
        DataAccessor.setField(instance, InstanceConstants.FIELD_LABELS, map(SystemLabels.LABEL_PROXY_PORT, "9443",
                SystemLabels.LABEL_PROXY_SCHEME, "https"));
        return instance;
    }

    private static HostRecord host() {
        HostRecord host = new HostRecord();
        host.setId(42L);
        host.setUuid("host-uuid");
        host.setKind("docker");
        return host;
    }

    private static ObjectManager objectManager(final Host host) {
        return (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(), new Class<?>[] { ObjectManager.class },
                (proxy, method, args) -> {
                    if ("mappedChildren".equals(method.getName())) {
                        return Collections.singletonList(host);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ApiRequest request(Map<String, Object> requestObject) {
        ApiContext.newContext();
        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(requestObject);
        return request;
    }

    private static DockerActionSettings settings() {
        return new DockerActionSettings() {
            @Override
            public String hostProxyPath() {
                return "/proxy-test/";
            }

            @Override
            public long hostProxyJwtExpirationSeconds() {
                return 60;
            }

            @Override
            public String hostLogsPath() {
                return "/logs-test/";
            }

            @Override
            public String hostSocketProxyPath() {
                return "/socket-test/";
            }

            @Override
            public String execAgentPath() {
                return "/exec-test/";
            }
        };
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], values[i + 1]);
        }
        return result;
    }

    private static Map<?, ?> capturedData(CapturingHostApiService service, String key) {
        Object value = service.data.get(key);
        assertTrue(value instanceof Map<?, ?>);
        return (Map<?, ?>) value;
    }

    private static void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static void expectIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static final class CapturingHostApiService implements HostApiService {
        private final HostApiAccess access;
        private Long hostId;
        private Map<String, Object> data;
        private Date expiration;
        private String[] paths;

        CapturingHostApiService(String url, String token) {
            access = new HostApiAccess(url, token, Collections.<String, String>emptyMap());
        }

        @Override
        public HostApiAccess getAccess(ApiRequest apiRequest, Long hostId, Map<String, Object> data, Date expiration,
                String... resourcePathSegments) {
            this.hostId = hostId;
            this.data = data;
            this.expiration = expiration;
            this.paths = resourcePathSegments;
            return access;
        }

        @Override
        public HostApiAccess getAccess(ApiRequest apiRequest, Long hostId, Map<String, Object> data, String... resourcePathSegments) {
            return getAccess(apiRequest, hostId, data, null, resourcePathSegments);
        }

        @Override
        public Map<String, PublicKey> getPublicKeys() {
            return Collections.emptyMap();
        }
    }
}
