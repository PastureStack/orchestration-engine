package io.cattle.platform.docker.api;

import io.cattle.platform.api.action.ActionHandler;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.docker.api.model.HostAccess;
import io.cattle.platform.host.model.HostApiAccess;
import io.cattle.platform.host.service.HostApiService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.HashMap;

import jakarta.inject.Inject;

public class DockerSocketProxyActionHandler implements ActionHandler {

    private final DockerActionSettings settings;

    HostApiService apiService;
    ObjectManager objectManager;

    public DockerSocketProxyActionHandler() {
        this(ArchaiusDockerActionSettings.create());
    }

    DockerSocketProxyActionHandler(DockerActionSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings is required");
        }
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "host.dockersocket";
    }

    @Override
    public Object perform(String name, Object obj, ApiRequest request) {
        if (obj == null) {
            return null;
        }

        Host host = (Host)obj;

        HostApiAccess apiAccess = apiService.getAccess(request, host.getId(), new HashMap<String, Object>(), settings.hostSocketProxyPath());

        if (apiAccess == null) {
            return null;
        }

        HostAccess access = new HostAccess(apiAccess.getUrl(), apiAccess.getAuthenticationToken());
        return access;
    }

    public HostApiService getApiService() {
        return apiService;
    }

    @Inject
    public void setApiService(HostApiService apiService) {
        this.apiService = apiService;
    }

    public ObjectManager getObjectManager() {
        return objectManager;
    }

    @Inject
    public void setObjectManager(ObjectManager objectManager) {
        this.objectManager = objectManager;
    }
}
