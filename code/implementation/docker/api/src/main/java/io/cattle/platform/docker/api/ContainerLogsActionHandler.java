package io.cattle.platform.docker.api;

import io.cattle.platform.api.action.ActionHandler;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.docker.api.model.ContainerLogs;
import io.cattle.platform.docker.api.model.HostAccess;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.docker.util.DockerUtils;
import io.cattle.platform.host.model.HostApiAccess;
import io.cattle.platform.host.service.HostApiService;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.Map;

import jakarta.inject.Inject;

public class ContainerLogsActionHandler implements ActionHandler {

    private final DockerActionSettings settings;

    HostApiService apiService;
    ObjectManager objectManager;

    public ContainerLogsActionHandler() {
        this(ArchaiusDockerActionSettings.create());
    }

    ContainerLogsActionHandler(DockerActionSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings is required");
        }
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "instance.logs";
    }

    @Override
    public Object perform(String name, Object obj, ApiRequest request) {

        Host host = null;
        Instance container = null;

        if (obj instanceof Instance) {
            container = (Instance) obj;
            host = DockerUtils.getHostFromContainer(objectManager, container, null);
        }

        if (host == null) {
            return null;
        }

        ContainerLogs logs = request.proxyRequestObject(ContainerLogs.class);

        String dockerId = DockerUtils.getDockerIdentifier(container);
        Map<String, Object> data = CollectionUtils.asMap(DockerInstanceConstants.DOCKER_CONTAINER, dockerId, "Lines", logs.getLines(), "Follow",
                logs.getFollow(), "Timestamps", logs.getTimestamps(), "Since", logs.getSince());

        HostApiAccess apiAccess = apiService.getAccess(request, host.getId(), CollectionUtils.asMap("logs", data), settings.hostLogsPath());

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
