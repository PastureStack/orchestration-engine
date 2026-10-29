package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A safe question projection with a private, owner-bound source snapshot. */
public class AiCatalogLaunchOptionsService {
    @Inject AiCatalogLaunchAccess access;
    @Inject AiCatalogLaunchGateway gateway;
    @Inject AiCatalogLaunchOptionsStore snapshots;
    @Inject List<ApiKeyAuditSink> auditSinks;
    @Inject AiCatalogLaunchPlanStore plans;

    public Map<String, Object> read(ApiRequest request) {
        return plans.transaction(() -> readInTransaction(request));
    }

    private Map<String, Object> readInTransaction(ApiRequest request) {
        Policy actor = null;
        try {
            actor = access.actor(request);
            Map<String, Object> binding = binding(actor, request);
            var current = access.bound(binding, request);
            var schema = current.current().schemas().getSchema(OPTIONS);
            if (schema == null || schema.getCollectionMethods() == null || !schema.getCollectionMethods().contains("POST"))
                throw error(403, "OwnerPermissionDenied");
            access.catalog(current);
            Map<String, Object> input = closed(request.getRequestObject(), Set.of("type", "templateId", "versionId"));
            if (input.get("type") != null && !OPTIONS.equals(input.get("type"))) throw error(422, "CatalogLaunchInputInvalid");
            String template = id(input.get("templateId"), TEMPLATE), version = id(input.get("versionId"), VERSION);
            Map<String, Object> material = gateway.material(access.project(actor), template, version);
            List<Map<String, Object>> questions = questions(material.get("questionSchema"));
            String ref = snapshots.put(AiCatalogLaunchOptionsStore.binding(request, actor), template, version, (String) material.get("digest"), questions);
            audit(request, actor, true, "CatalogLaunchOptionsReadAllowed");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", "aicatalogoptions-" + UUID.randomUUID()); result.put("type", OPTIONS); result.put("questions", questions);
            result.put("optionsRef", ref); result.put("launchEnabled", enabled()); result.put("resourceTextIsUntrusted", true);
            result.put("requiredOperations", List.of("read", "create", "update"));
            return result;
        } catch (ClientVisibleException denied) {
            if (!"CatalogLaunchAuditUnavailable".equals(denied.getCode())) audit(request, actor == null ? ApiUtils.getPolicy() : actor, false,
                    denied.getCode());
            throw error(denied.getStatus(), denied.getCode());
        }
    }

    static Map<String, Object> binding(Policy actor, ApiRequest request) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("actorId", actor.getAuthenticatedAsAccountId()); binding.put("accountId", actor.getAccountId());
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key != null) {
            binding.put("credentialId", key.credentialId()); binding.put("credentialRevision", key.revision()); binding.put("credentialKind", key.kind());
        }
        return binding;
    }

    static boolean enabled() { return Boolean.TRUE.equals(ArchaiusUtil.getBooleanProperty("api.ai.catalog.launch.enabled").get()); }

    private void audit(ApiRequest request, Policy actor, boolean allowed, String reason) {
        if (auditSinks == null || auditSinks.isEmpty()) throw error(503, "CatalogLaunchAuditUnavailable");
        try { for (ApiKeyAuditSink sink : auditSinks) sink.recordCatalogLaunchDecision(request, actor, "read", null, null, allowed, reason); }
        catch (RuntimeException unavailable) { throw error(503, "CatalogLaunchAuditUnavailable"); }
    }
}
