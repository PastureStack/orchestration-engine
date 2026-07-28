package io.cattle.platform.docker.api;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

public class ArchaiusDockerActionSettings implements DockerActionSettings {

    private final ConfigProperty<String> hostProxyPath = ArchaiusUtil.getStringProperty("host.proxy.path");
    private final ConfigProperty<Long> hostProxyJwtExpirationSeconds = ArchaiusUtil.getLongProperty("host.proxy.jwt.expiration.seconds");
    private final ConfigProperty<String> hostLogsPath = ArchaiusUtil.getStringProperty("host.logs.path");
    private final ConfigProperty<String> hostSocketProxyPath = ArchaiusUtil.getStringProperty("host.socketproxy.path");
    private final ConfigProperty<String> execAgentPath = ArchaiusUtil.getStringProperty("exec.agent.path");

    public static DockerActionSettings create() {
        return new ArchaiusDockerActionSettings();
    }

    @Override
    public String hostProxyPath() {
        return hostProxyPath.get();
    }

    @Override
    public long hostProxyJwtExpirationSeconds() {
        return hostProxyJwtExpirationSeconds.get();
    }

    @Override
    public String hostLogsPath() {
        return hostLogsPath.get();
    }

    @Override
    public String hostSocketProxyPath() {
        return hostSocketProxyPath.get();
    }

    @Override
    public String execAgentPath() {
        return execAgentPath.get();
    }

}
