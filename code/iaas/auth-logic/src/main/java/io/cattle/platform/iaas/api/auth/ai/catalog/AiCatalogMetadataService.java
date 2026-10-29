package io.cattle.platform.iaas.api.auth.ai.catalog;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** New typed platform metadata read, bounded by the original current-project owner. */
public class AiCatalogMetadataService {
    static final List<String> FIELDS = List.of("name", "description", "catalogId", "templateBase", "templateKey", "catalogScope",
            "defaultVersion", "maintainer", "license");
    static final List<String> VERSION_FIELDS = List.of("version", "minimumRancherVersion", "maximumRancherVersion", "upgradeFrom");
    @Inject AiKeyAuthorization keys;
    @Inject ApiAuthenticator authenticator;
    @Inject ObjectManager objects;
    @Inject AiCatalogMetadataGateway gateway;
    @Inject List<ApiKeyAuditSink> auditSinks;
    Clock clock = Clock.systemUTC();
    final ApiKeyPolicyEvaluator evaluator = new ApiKeyPolicyEvaluator();

    public Map<String, Object> read(ApiRequest request) {
        AiCatalogMetadataQuery query;
        Policy actor = null;
        try {
            query = AiCatalogMetadataQuery.parse(request);
            actor = authorize(request);
            audit(request, actor, query.templateId(), true, "CatalogMetadataReadAllowed");
        } catch (ClientVisibleException denied) {
            if (!"CatalogMetadataAuditUnavailable".equals(denied.getCode()))
                audit(request, actor == null ? ApiUtils.getPolicy() : actor, null, false, denied.getCode());
            throw AiCatalogMetadataQuery.error(denied.getStatus(), denied.getCode());
        }
        String projectId = String.valueOf(ApiContext.getContext().getIdFormatter().formatId("account", actor.getAccountId()));
        Map<String, Object> raw = gateway.read(projectId, query);
        Map<String, Object> projected = project(raw, query);
        projected.put("id", "aicatalog-" + UUID.randomUUID());
        projected.put("resourceTextIsUntrusted", true);
        return projected;
    }

    Policy authorize(ApiRequest request) {
        // Existing helper reloads the actual credential, revision, kind, expiry,
        // revocation and owner membership. It never exchanges the Key for JWT.
        Policy owner = keys.actor(request);
        if (owner == null || owner.getAuthenticatedAsAccountId() <= 0 || owner.getAccountId() <= 0)
            throw AiCatalogMetadataQuery.error(403, "OwnerPermissionDenied");
        var current = authenticator.currentAuthorization(owner.getAuthenticatedAsAccountId(), owner.getAccountId(), request);
        Account project = objects.loadResource(Account.class, current.policy().getAccountId());
        Schema schema = current.schemas().getSchema(AiCatalogMetadataQuery.TYPE);
        if (project == null || project.getRemoved() != null || !"active".equals(project.getState()) || !"project".equals(project.getKind())
                || schema == null || schema.getCollectionMethods() == null || !schema.getCollectionMethods().contains("POST"))
            throw AiCatalogMetadataQuery.error(403, "OwnerPermissionDenied");
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null) return current.policy();
        ApiKeyPolicy policy = key.policy();
        if (policy != null && policy.getPurpose() == ApiKeyPolicy.Purpose.AI_BRIDGE) {
            ApiKeyAiBridgeGuard.requireEnabled(policy);
            String revision = request.getServletContext() == null ? null
                    : request.getServletContext().getRequest().getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER);
            if (!Long.toString(key.revision()).equals(revision)) throw AiCatalogMetadataQuery.error(403, "ApiKeyPolicyChanged");
        }
        if (policy != null) {
            var decision = evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, "read", true,
                    List.of(ApiKeyPolicyEvaluator.Target.platformResource("catalogTemplate", "catalog-metadata"))), clock.instant());
            // Fine-grained selectors need pre-pagination row filtering on the
            // logical identity. Until wired, fail closed rather than ignoring a
            // row-specific DENY or borrowing project ancestry.
            if ((decision.allowed() || decision.reason() == ApiKeyPolicyEvaluator.Reason.SCOPE_DENIED)
                    && policy.getMode() != ApiKeyPolicy.Mode.FULL && policy.getRules().stream().anyMatch(rule -> rule.operations().contains("read")
                    && rule.scope().kind() == ApiKeyPolicy.ScopeKind.RESOURCE
                    && Set.of("catalogTemplate", "catalogTemplateVersion", "catalog").contains(rule.scope().resourceType())))
                throw AiCatalogMetadataQuery.error(422, "CatalogMetadataFineGrainedUnsupported");
            if (!decision.allowed()) throw AiCatalogMetadataQuery.error(403, decision.reason().getCode());
        }
        return current.policy();
    }

    private void audit(ApiRequest request, Policy actor, String logicalId, boolean allowed, String reason) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null) return; // Ordinary Cookie access remains under the existing API audit pipeline.
        if (auditSinks == null || auditSinks.isEmpty()) throw AiCatalogMetadataQuery.error(503, "CatalogMetadataAuditUnavailable");
        ApiRequest decision = new ApiRequest(request);
        decision.setRequestServletContext(request.getServletContext());
        decision.setType("catalogTemplate"); decision.setMethod("GET"); decision.setAction(null); decision.setLink(null); decision.setId(logicalId);
        decision.setRequestObject(null); decision.setRequestParams(new LinkedHashMap<>()); decision.setConditions(new LinkedHashMap<>());
        ApiKeyCredentialContext.attach(decision, key);
        decision.setAttribute("apiKey.audit.operation", "read");
        decision.setAttribute("apiKey.audit.targetType", "catalogTemplate"); decision.setAttribute("apiKey.audit.targetId", logicalId);
        decision.setAttribute("apiKey.audit.requestId", UUID.randomUUID().toString());
        decision.setAttribute("apiKey.audit.decision", allowed ? "ALLOW" : "DENY"); decision.setAttribute("apiKey.audit.reason", reason);
        try { for (ApiKeyAuditSink sink : auditSinks) sink.recordDecision(decision, actor); }
        catch (RuntimeException unavailable) { throw AiCatalogMetadataQuery.error(503, "CatalogMetadataAuditUnavailable"); }
    }

    static Map<String, Object> project(Map<String, Object> raw, AiCatalogMetadataQuery query) {
        String resourceType = query.versions() ? "catalogTemplateVersion" : "catalogTemplate";
        List<String> fields = query.versions() ? VERSION_FIELDS : FIELDS;
        if (raw == null || !resourceType.equals(raw.get("resourceType")) || !(raw.get("data") instanceof List<?> source) || source.size() > 50)
            throw AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
        List<Map<String, Object>> rows = new ArrayList<>(); Set<String> ids = new LinkedHashSet<>();
        for (Object value : source) {
            if (!(value instanceof Map<?, ?> row) || !(row.get("id") instanceof String id)
                    || !id.matches(query.versions() ? AiCatalogMetadataQuery.VERSION_ID : AiCatalogMetadataQuery.LOGICAL_ID)
                    || !resourceType.equals(row.get("type")) || !ids.add(id)) throw AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
            Map<String, Object> safe = new LinkedHashMap<>(); safe.put("id", id); safe.put("type", resourceType);
            Set<String> truncated = new LinkedHashSet<>();
            Object reported = row.get("truncatedFields");
            if (reported != null) {
                if (!(reported instanceof List<?> names) || names.size() > fields.size()
                        || names.stream().anyMatch(field -> !(field instanceof String) || !fields.contains(field)))
                    throw AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
                for (Object field : (List<?>) reported) truncated.add((String) field);
            }
            for (String field : fields) {
                Object content = row.get(field); if (content == null) continue;
                if (!(content instanceof String text)) throw AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
                int limit = "description".equals(field) ? 1024 : 512;
                safe.put(field, text.substring(0, Math.min(limit, text.length())));
                if (text.length() > limit) truncated.add(field);
            }
            if (query.versions()) {
                if (!query.templateId().equals(row.get("templateId")) || !(safe.get("version") instanceof String))
                    throw AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
                safe.put("templateId", query.templateId()); Object revision = row.get("revision");
                if (revision != null && (!(revision instanceof Number number) || number.longValue() < 0
                        || number.longValue() > 9007199254740991L || number.doubleValue() != number.longValue()))
                    throw AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
                safe.put("revision", revision);
            } else if (!(safe.get("name") instanceof String) || !(row.get("templateKey") instanceof String key)
                    || !safeTemplateKey(key)
                    || !Set.of("global", "project").contains(String.valueOf(safe.get("catalogScope"))))
                    throw AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
            if (!truncated.isEmpty()) safe.put("truncatedFields", List.copyOf(truncated));
            rows.add(safe);
        }
        Object marker = raw.get("nextMarker");
        if (marker != null && (!(marker instanceof String text) || !text.matches(AiCatalogMetadataQuery.CURSOR) || rows.isEmpty()
                || marker.equals(query.marker()))) throw AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
        String detailId = query.versions() ? query.versionId() : query.templateId();
        if (detailId != null && (rows.size() != 1 || !detailId.equals(rows.getFirst().get("id")) || marker != null))
            throw AiCatalogMetadataQuery.error(503, "CatalogMetadataUnavailable");
        Map<String, Object> result = new LinkedHashMap<>(); result.put("resourceType", resourceType);
        result.put("data", rows); result.put("nextMarker", marker); return result;
    }

    static boolean safeTemplateKey(String key) {
        return !key.isEmpty() && !key.equals(".") && !key.equals("..") && key.length() <= 512
                && key.chars().noneMatch(value -> value < 0x20 || value == 0x7f || value == '/' || value == '\\' || value == ':');
    }
}
