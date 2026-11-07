package io.cattle.platform.api.auth;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Date;
import java.util.Map;

/** Server-only provenance for one fixed native logs action; never an HTTP capability. */
public final class ApiContainerLogsRead {
    private static final String ATTRIBUTE = ApiContainerLogsRead.class.getName();
    private record Proof(ApiRequest request, String id, Object body, long expires) { }
    private ApiContainerLogsRead() { }

    public static void bind(ApiRequest request, long expires) {
        if (!shape(request) || expires <= System.currentTimeMillis() || expires - System.currentTimeMillis() > 5000)
            throw new IllegalArgumentException("Invalid bounded logs action");
        request.setAttribute(ATTRIBUTE, new Proof(request, request.getId(), Map.copyOf((Map<?, ?>) request.getRequestObject()), expires));
    }
    public static boolean trusted(ApiRequest request) {
        return request != null && request.getAttribute(ATTRIBUTE) instanceof Proof proof && proof.request() == request
                && proof.expires() > System.currentTimeMillis() && proof.id().equals(request.getId())
                && proof.body().equals(request.getRequestObject()) && shape(request);
    }
    public static Date expiration(ApiRequest request) {
        if (request == null || request.getAttribute(ATTRIBUTE) == null) return null;
        if (!trusted(request)) throw new IllegalStateException("Bounded logs authority changed");
        return new Date(((Proof) request.getAttribute(ATTRIBUTE)).expires());
    }
    private static boolean shape(ApiRequest request) {
        return request != null && "container".equals(request.getType()) && "POST".equals(request.getMethod())
                && "logs".equals(request.getAction()) && request.getLink() == null && request.getId() != null
                && request.getRequestObject() instanceof Map<?, ?> body
                && body.keySet().equals(java.util.Set.of("lines", "follow", "timestamps", "since"))
                && body.get("lines") instanceof Integer lines && lines >= 1 && lines <= 200
                && Boolean.FALSE.equals(body.get("follow")) && Boolean.TRUE.equals(body.get("timestamps")) && "".equals(body.get("since"));
    }
}
