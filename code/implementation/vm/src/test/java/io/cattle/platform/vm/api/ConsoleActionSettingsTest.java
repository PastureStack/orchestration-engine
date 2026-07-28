package io.cattle.platform.vm.api;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.docker.api.model.HostAccess;
import io.cattle.platform.host.model.HostApiAccess;
import io.cattle.platform.host.service.HostApiService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.Proxy;
import java.security.PublicKey;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class ConsoleActionSettingsTest {

    @After
    public void cleanup() {
        clear("console.agent.path");
        ApiContext.remove();
    }

    @Test
    public void archaiusSettingsReadDynamicConsolePath() {
        ConfigurationManager.getConfigInstance().setProperty("console.agent.path", "/console-a/");

        ConsoleActionSettings settings = ArchaiusConsoleActionSettings.create();

        assertEquals("/console-a/", settings.consoleAgentPath());

        ConfigurationManager.getConfigInstance().setProperty("console.agent.path", "/console-b/");

        assertEquals("/console-b/", settings.consoleAgentPath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullSettingsAreRejected() {
        new InstanceConsoleActionHandler(null);
    }

    @Test
    public void injectedSettingsDriveConsoleAccessPathAndPayload() {
        CapturingHostApiService service = new CapturingHostApiService("ws://agent/console", "console-token");
        InstanceConsoleActionHandler handler = new InstanceConsoleActionHandler(() -> "/console-test/");
        handler.apiService = service;
        handler.objectManager = objectManager(host());

        HostAccess access = (HostAccess) handler.perform("instance.console", instance(), request());

        assertEquals("ws://agent/console", access.getUrl());
        assertEquals("console-token", access.getToken());
        assertEquals(Long.valueOf(42L), service.hostId);
        assertEquals("/console-test/", service.paths[0]);
        Map<?, ?> console = Map.class.cast(service.data.get("console"));
        assertEquals("docker-id", console.get("container"));
    }

    private static InstanceRecord instance() {
        InstanceRecord instance = new InstanceRecord();
        instance.setUuid("instance-uuid");
        instance.setExternalId("docker-id");
        instance.setData(new HashMap<String, Object>());
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

    private static ApiRequest request() {
        ApiContext.newContext();
        return new ApiRequest(null, null);
    }

    private static void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static final class CapturingHostApiService implements HostApiService {
        private final HostApiAccess access;
        private Long hostId;
        private Map<String, Object> data;
        private String[] paths;

        CapturingHostApiService(String url, String token) {
            access = new HostApiAccess(url, token, Collections.<String, String>emptyMap());
        }

        @Override
        public HostApiAccess getAccess(ApiRequest apiRequest, Long hostId, Map<String, Object> data, Date expiration,
                String... resourcePathSegments) {
            this.hostId = hostId;
            this.data = data;
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
