package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.server.context.ServerContext;
import io.cattle.platform.server.context.ServerContext.BaseProtocol;

import java.net.URI;

final class LocalCattleApi {

    private LocalCattleApi() {
    }

    static String url() {
        return ServerContext.getLocalhostUrl(BaseProtocol.HTTP)
                .replace("://localhost", "://127.0.0.1")
                .replace("://[::1]", "://127.0.0.1");
    }

    static URI uri() {
        return URI.create(url());
    }

    static URI pingUri() {
        URI api = uri();
        return URI.create(api.getScheme() + "://" + api.getAuthority() + "/ping");
    }

    static boolean isReady() {
        return LocalReloadRequest.isGetSuccessful(pingUri());
    }
}
