package io.cattle.platform.host.api;

public interface HostApiSettings {

    String authHeader();

    String authHeaderValueFormat();

    String proxyHost();

    String proxyBackendPath();

}
