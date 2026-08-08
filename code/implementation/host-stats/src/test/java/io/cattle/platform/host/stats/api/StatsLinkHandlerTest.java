package io.cattle.platform.host.stats.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.constants.HostConstants;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.host.model.HostApiAccess;
import io.cattle.platform.host.service.HostApiService;
import io.cattle.platform.host.stats.utils.StatsConstants;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.Proxy;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.configuration.MapConfiguration;
import org.junit.Test;

public class StatsLinkHandlerTest {

    @Test
    public void keepsLegacyStatsLinkForHosts() throws Exception {
        try {
            Properties properties = new Properties();
            properties.setProperty("host.stats.path", "/v1/stats");
            ArchaiusUtil.initialize(new MapConfiguration(properties));

            StatsLinkHandler handler = new StatsLinkHandler();
            RecordingHostApiService hostApiService = new RecordingHostApiService();
            handler.setHostApiService(hostApiService);
            handler.setObjectManager(objectManager(hostWithId(42L)));
            ApiContext.newContext();

            Host host = hostWithId(42L);
            StatsAccess access = (StatsAccess) handler.link(StatsConstants.LINK_STATS, host, null);

            assertEquals("token-42", access.getToken());
            assertEquals("http://agent.example/v1/stats", access.getUrl());
            assertEquals(Long.valueOf(42L), hostApiService.hostId);
            assertArrayEquals(new String[] { "/v1/stats" }, hostApiService.resourcePathSegments);
            assertEquals("42", hostApiService.data.get("resourceId"));
            assertEquals(1, hostApiService.data.size());
        } finally {
            ApiContext.remove();
            ArchaiusUtil.initialize(new MapConfiguration(new Properties()));
        }
    }

    @Test
    public void legacyContainerStatsTokenIsBoundToTheRequestedContainer() throws Exception {
        try {
            Properties properties = new Properties();
            properties.setProperty("host.stats.path", "/v1/stats");
            ArchaiusUtil.initialize(new MapConfiguration(properties));

            Host host = hostWithId(42L);
            Instance instance = instanceWithId(7L);
            StatsLinkHandler handler = new StatsLinkHandler();
            RecordingHostApiService hostApiService = new RecordingHostApiService();
            handler.setHostApiService(hostApiService);
            handler.setObjectManager(objectManager(host));
            ApiContext.newContext();

            StatsAccess access = (StatsAccess) handler.link(StatsConstants.LINK_STATS, instance, null);

            assertEquals("token-42", access.getToken());
            assertArrayEquals(new String[] { "/v1/stats", "docker-id" }, hostApiService.resourcePathSegments);
            Map<?, ?> containerIds = (Map<?, ?>) hostApiService.data.get("containerIds");
            assertEquals("7", containerIds.get("docker-id"));
            assertEquals(1, containerIds.size());
            assertEquals(1, hostApiService.data.size());
        } finally {
            ApiContext.remove();
            ArchaiusUtil.initialize(new MapConfiguration(new Properties()));
        }
    }

    @Test
    public void advertisesLegacyStatsLinkWithoutJavaDeprecation() {
        StatsLinkHandler handler = new StatsLinkHandler();

        assertTrue(Arrays.asList(handler.getTypes()).contains(HostConstants.TYPE));
        assertTrue(handler.handles(HostConstants.TYPE, "1h1", StatsConstants.LINK_STATS, null));
    }

    private Host hostWithId(Long id) {
        return (Host) Proxy.newProxyInstance(Host.class.getClassLoader(), new Class<?>[] { Host.class },
                (proxy, method, args) -> {
                    if ("getId".equals(method.getName())) {
                        return id;
                    }
                    if ("getKind".equals(method.getName())) {
                        return "docker";
                    }
                    if ("toString".equals(method.getName())) {
                        return "Host[" + id + "]";
                    }
                    return null;
                });
    }

    private Instance instanceWithId(Long id) {
        return (Instance) Proxy.newProxyInstance(Instance.class.getClassLoader(), new Class<?>[] { Instance.class },
                (proxy, method, args) -> {
                    if ("getId".equals(method.getName())) {
                        return id;
                    }
                    if ("getExternalId".equals(method.getName())) {
                        return "docker-id";
                    }
                    if ("getUuid".equals(method.getName())) {
                        return "instance-uuid";
                    }
                    if ("toString".equals(method.getName())) {
                        return "Instance[" + id + "]";
                    }
                    return null;
                });
    }

    private ObjectManager objectManager(Host host) {
        return (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(), new Class<?>[] { ObjectManager.class },
                (proxy, method, args) -> {
                    if ("getType".equals(method.getName())) {
                        return args[0] instanceof Host ? HostConstants.TYPE : InstanceConstants.TYPE;
                    }
                    if ("mappedChildren".equals(method.getName())) {
                        return Collections.singletonList(host);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static class RecordingHostApiService implements HostApiService {
        Long hostId;
        Map<String, Object> data;
        String[] resourcePathSegments;

        @Override
        public HostApiAccess getAccess(ApiRequest apiRequest, Long hostId, Map<String, Object> data,
                String... resourcePathSegments) {
            this.hostId = hostId;
            this.data = data;
            this.resourcePathSegments = resourcePathSegments;
            return new HostApiAccess("http://agent.example/v1/stats", "token-" + hostId, Collections.emptyMap());
        }

        @Override
        public HostApiAccess getAccess(ApiRequest apiRequest, Long hostId, Map<String, Object> data, java.util.Date expiration,
                String... resourcePathSegments) {
            return getAccess(apiRequest, hostId, data, resourcePathSegments);
        }

        @Override
        public Map<String, PublicKey> getPublicKeys() {
            return Collections.emptyMap();
        }
    }
}
