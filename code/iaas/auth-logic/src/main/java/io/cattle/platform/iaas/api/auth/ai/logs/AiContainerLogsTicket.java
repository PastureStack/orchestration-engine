package io.cattle.platform.iaas.api.auth.ai.logs;

import io.cattle.platform.docker.api.model.HostAccess;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request-local single-use IPC capability. The native JWT never enters a public response. */
final class AiContainerLogsTicket {
    private final ApiRequest request;
    private final long actor, project, expires;
    private final String containerUuid, requestId, token;
    private final int lines;
    private final AtomicBoolean consumed = new AtomicBoolean();

    AiContainerLogsTicket(ApiRequest request, long actor, long project, String containerUuid, String requestId,
            int lines, long expires, HostAccess access) {
        if (request == null || actor <= 0 || project <= 0 || containerUuid == null
                || !containerUuid.matches("[0-9a-fA-F-]{36}") || requestId == null || !requestId.matches("ailogs-[0-9a-f-]{36}")
                || lines < 1 || lines > 200 || expires <= System.currentTimeMillis()
                || expires - System.currentTimeMillis() > 5000 || access == null || access.getToken() == null
                || access.getToken().isBlank() || access.getToken().length() > 32768) throw unavailable();
        try {
            URI uri = new URI(access.getUrl());
            if (!Set.of("ws", "wss").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || !"/v1/logs/".equals(uri.getRawPath())) throw unavailable();
        } catch (java.net.URISyntaxException invalid) { throw unavailable(); }
        this.request = request; this.actor = actor; this.project = project; this.containerUuid = containerUuid;
        this.requestId = requestId; this.lines = lines; this.expires = expires; token = access.getToken();
    }

    Map<String, Object> consume(ApiRequest expectedRequest, long expectedActor, long expectedProject, String expectedUuid) {
        // Even an invalid consumption attempt burns this one request-local capability.
        if (!consumed.compareAndSet(false, true) || request != expectedRequest || actor != expectedActor
                || project != expectedProject || !containerUuid.equals(expectedUuid) || expires <= System.currentTimeMillis()) throw unavailable();
        return Map.of("requestId", requestId, "actorId", actor, "projectId", project, "containerUuid", containerUuid,
                "operation", "logs", "expiresAtMillis", expires, "tailLines", lines, "token", token);
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Bounded logs ticket unavailable"); }
}
