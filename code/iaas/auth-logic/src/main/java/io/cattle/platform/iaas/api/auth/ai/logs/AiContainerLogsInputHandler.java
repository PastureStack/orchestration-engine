package io.cattle.platform.iaas.api.auth.ai.logs;

import io.cattle.platform.api.auth.ApiAuditPayload;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractResponseGenerator;
import java.util.Map;
import java.util.Set;

/** Capture only a closed-field violation, before the normal schema sanitizer drops unknown fields. */
public class AiContainerLogsInputHandler extends AbstractResponseGenerator {
    static final String REJECTED = AiContainerLogsInputHandler.class.getName() + ".rejected";
    @Override protected void generate(ApiRequest request) {
        if (!"aiResourceView".equals(request.getType()) || !"POST".equals(request.getMethod())
                || request.getId() != null || request.getAction() != null) return;
        if (request.getRequestObject() instanceof Map<?, ?> input && ("container_logs".equals(input.get("view")) || input.containsKey("tailLines"))) {
            ApiAuditPayload.boundedContainerLogs(request);
            boolean invalid = !Set.of("type", "resourceType", "resourceId", "view", "tailLines").containsAll(input.keySet());
            if (invalid) request.setAttribute(REJECTED, Boolean.TRUE);
        }
    }
}
