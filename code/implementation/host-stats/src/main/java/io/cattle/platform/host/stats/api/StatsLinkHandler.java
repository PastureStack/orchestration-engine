package io.cattle.platform.host.stats.api;

import io.cattle.platform.api.link.LinkHandler;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.constants.HostConstants;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.docker.util.DockerUtils;
import io.cattle.platform.host.model.HostApiAccess;
import io.cattle.platform.host.service.HostApiService;
import io.cattle.platform.host.stats.utils.StatsConstants;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

/**
 * Legacy `stats` link kept for backward compatibility with older Rancher 1.6
 * clients while newer callers use the host/container stats links.
 */
public class StatsLinkHandler implements LinkHandler {

    private static final ConfigProperty<String> HOST_STATS_PATH = ArchaiusUtil.getStringProperty("host.stats.path");

    HostApiService hostApiService;
    ObjectManager objectManager;

    @Override
    public String[] getTypes() {
        List<String> types = new ArrayList<>(InstanceConstants.CONTAINER_LIKE);
        types.add(HostConstants.TYPE);
        return types.toArray(new String[types.size()]);
    }

    @Override
    public boolean handles(String type, String id, String link, ApiRequest request) {
        return StatsConstants.LINK_STATS.equals(link);
    }

    @Override
    public Object link(String name, Object obj, ApiRequest request) throws IOException {
        Host host = null;
        Instance instance = null;

        if (obj instanceof Instance) {
            instance = (Instance) obj;
            host = DockerUtils.getHostFromContainer(objectManager, instance);
        } else if (obj instanceof Host) {
            host = (Host) obj;
        }

        if (host == null) {
            return null;
        }

        String[] pathSegments;
        Map<String, Object> payload = new HashMap<>();
        if (instance != null) {
            String dockerId = DockerUtils.getDockerIdentifier(instance);
            if (dockerId == null || dockerId.length() == 0) {
                return null;
            }
            Map<String, Object> containerIds = new HashMap<>();
            Object formattedId = ApiContext.getContext().getIdFormatter()
                    .formatId(objectManager.getType(instance), instance.getId());
            if (formattedId == null) {
                return null;
            }
            containerIds.put(dockerId, String.valueOf(formattedId));
            payload.put("containerIds", containerIds);
            pathSegments = new String[] { HOST_STATS_PATH.get(), dockerId };
        } else {
            Object formattedId = ApiContext.getContext().getIdFormatter()
                    .formatId(objectManager.getType(host), host.getId());
            if (formattedId == null) {
                return null;
            }
            payload.put("resourceId", String.valueOf(formattedId));
            pathSegments = new String[] { HOST_STATS_PATH.get() };
        }

        HostApiAccess apiAccess = hostApiService.getAccess(request, host.getId(), payload, pathSegments);
        if (apiAccess == null) {
            return null;
        }

        StatsAccess statsAccess = new StatsAccess();
        statsAccess.setToken(apiAccess.getAuthenticationToken());
        statsAccess.setUrl(apiAccess.getUrl());

        return statsAccess;
    }

    public HostApiService getHostApiService() {
        return hostApiService;
    }

    @Inject
    public void setHostApiService(HostApiService hostApiService) {
        this.hostApiService = hostApiService;
    }

    public ObjectManager getObjectManager() {
        return objectManager;
    }

    @Inject
    public void setObjectManager(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }

}
