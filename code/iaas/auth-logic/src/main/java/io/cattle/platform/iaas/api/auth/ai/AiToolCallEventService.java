package io.cattle.platform.iaas.api.auth.ai;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ClientToolAuditEvent;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AiToolCallEventService {
    @Inject ObjectManager objects;
    @Inject List<ApiKeyAuditSink> auditSinks;
    @Inject AiKeyAuthorization keyAuthorization;

    public Map<String, Object> report(ApiRequest request) {
        Policy actor = actor(request);
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        if (!Set.of("command", "phase", "status", "reason", "correlationId", "type").containsAll(input.keySet())) throw error(422, "AiToolEventInvalid");
        ClientToolAuditEvent event = new ClientToolAuditEvent("aitool-" + UUID.randomUUID(),
                text(input.get("command")), text(input.get("phase")), text(input.get("status")),
                text(input.get("reason")), text(input.get("correlationId")));
        if (!event.valid()) throw error(422, "AiToolEventInvalid");
        if (auditSinks == null || auditSinks.isEmpty()) throw error(503, "AiToolAuditUnavailable");
        try {
            for (ApiKeyAuditSink sink : auditSinks) sink.recordClientToolEvent(request, actor, event);
        } catch (RuntimeException unavailable) {
            throw error(503, "AiToolAuditUnavailable");
        }
        // The ACK promises only durable metadata, not success of the reported command.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", event.requestId()); response.put("requestId", event.requestId());
        response.put("accepted", true); response.put("evidenceSource", "client_reported");
        return response;
    }

    protected Policy actor(ApiRequest request) {
        Policy policy = ApiKeyCredentialContext.get(request) == null ? ApiUtils.getPolicy() : keyAuthorization.actor(request);
        if (policy == null) throw error(403, "AiToolCookieSessionRequired");
        Account actor = objects.loadResource(Account.class, policy.getAuthenticatedAsAccountId());
        Account project = objects.loadResource(Account.class, policy.getAccountId());
        if (actor == null || actor.getRemoved() != null || !"active".equals(actor.getState())
                || project == null || project.getRemoved() != null || !"active".equals(project.getState())
                || !"project".equals(project.getKind())) throw error(403, "PermissionDenied");
        return policy;
    }
    private static String text(Object value) { return value instanceof String ? (String) value : null; }
    private static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
