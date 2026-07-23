package io.cattle.platform.docker.api;

public interface DockerActionSettings {

    String hostProxyPath();

    long hostProxyJwtExpirationSeconds();

    String hostLogsPath();

    String hostSocketProxyPath();

    String execAgentPath();

}
