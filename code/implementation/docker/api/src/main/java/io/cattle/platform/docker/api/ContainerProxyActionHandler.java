package io.cattle.platform.docker.api;

import io.cattle.platform.api.action.ActionHandler;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.util.SystemLabels;
import io.cattle.platform.docker.api.model.ContainerProxy;
import io.cattle.platform.docker.api.model.HostAccess;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.docker.util.DockerUtils;
import io.cattle.platform.host.model.HostApiAccess;
import io.cattle.platform.host.service.HostApiService;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.ObjectUtils;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;

public class ContainerProxyActionHandler implements ActionHandler {

    private static final Set<String> VALID_SCHEMES = new HashSet<>(Arrays.asList("http", "https"));

    private final DockerActionSettings settings;

    @Inject
    HostApiService apiService;
    @Inject
    ObjectManager objectManager;

    public ContainerProxyActionHandler() {
        this(ArchaiusDockerActionSettings.create());
    }

    ContainerProxyActionHandler(DockerActionSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings is required");
        }
        this.settings = settings;
    }

    @Override
    public String getName() {
        return "instance.proxy";
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

        ContainerProxy proxy = request.proxyRequestObject(ContainerProxy.class);

        String dockerId = DockerUtils.getDockerIdentifier(container);
        String ipAddress = DataAccessor.fieldString(container, InstanceConstants.FIELD_PRIMARY_IP_ADDRESS);
        if (ipAddress == null) {
            return null;
        }

        Map<String, Object> labels = DataAccessor.fieldMap(container, InstanceConstants.FIELD_LABELS);
        String port = ObjectUtils.toString(labels.get(SystemLabels.LABEL_PROXY_PORT));
        String scheme = ObjectUtils.toString(labels.get(SystemLabels.LABEL_PROXY_SCHEME));
        if (!VALID_SCHEMES.contains(scheme)) {
            scheme = null;
        }

        Map<String, Object> data = CollectionUtils.asMap(DockerInstanceConstants.DOCKER_CONTAINER, dockerId,
                "scheme", StringUtils.isBlank(scheme) ? proxy.getScheme() : scheme,
                "address", ipAddress + ":" + (StringUtils.isBlank(port) ? proxy.getPort() : port));

        Date expiration = new Date(System.currentTimeMillis() + settings.hostProxyJwtExpirationSeconds() * 1000);

        HostApiAccess apiAccess = apiService.getAccess(request, host.getId(), CollectionUtils.asMap("proxy", data),
                expiration, settings.hostProxyPath());

        if (apiAccess == null) {
            return null;
        }

        HostAccess access = new HostAccess(apiAccess.getUrl().replaceFirst("ws", "http"), apiAccess.getAuthenticationToken());
        return access;
    }

}
