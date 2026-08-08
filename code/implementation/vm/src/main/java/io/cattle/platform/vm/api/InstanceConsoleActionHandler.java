package io.cattle.platform.vm.api;

import io.cattle.platform.api.action.ActionHandler;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.docker.api.model.HostAccess;
import io.cattle.platform.docker.util.DockerUtils;
import io.cattle.platform.host.model.HostApiAccess;
import io.cattle.platform.host.service.HostApiService;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.Map;

import jakarta.inject.Inject;


public class InstanceConsoleActionHandler implements ActionHandler {

    private final ConsoleActionSettings settings;

    @Inject
    HostApiService apiService;
    @Inject
    ObjectManager objectManager;

    public InstanceConsoleActionHandler() {
        this(ArchaiusConsoleActionSettings.create());
    }

    InstanceConsoleActionHandler(ConsoleActionSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings is required");
        }
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "instance.console";
    }

    @Override
    public Object perform(String name, Object obj, ApiRequest request) {
        if (!(obj instanceof Instance)) {
            return null;
        }

        Host host = null;
        Instance instance = null;

        if (obj instanceof Instance) {
            instance = (Instance)obj;
            host = DockerUtils.getHostFromContainer(objectManager, instance, null);
        }

        if (host == null) {
            return null;
        }

        String dockerId = DockerUtils.getDockerIdentifier(instance);
        Map<String, Object> data = CollectionUtils.asMap("container", dockerId);

        HostApiAccess apiAccess = apiService.getAccess(request, host.getId(), CollectionUtils.asMap("console", data), settings.consoleAgentPath());

        if (apiAccess == null) {
            return null;
        }

        return new HostAccess(apiAccess.getUrl(), apiAccess.getAuthenticationToken());
    }
}
