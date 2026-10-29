package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** A durable, private-answer workflow dedicated to normal Catalog Stack creation. */
public class AiCatalogLaunchPlanService {
    @Inject AiCatalogLaunchPlanStore store;
    @Inject AiCatalogLaunchAccess access;
    @Inject AiCatalogLaunchOptionsStore options;
    @Inject AiCatalogLaunchGateway gateway;
    @Inject AiCatalogLaunchMaterializer materializer;
    @Inject AiCatalogLaunchGraph graphs;
    @Inject AiCatalogLaunchPrivate privateData;
    @Inject AiCatalogLaunchBinding binding;
    @Inject AiCatalogLaunchObservation observation;
    @Inject LockManager locks;
    @Inject List<ApiKeyAuditSink> auditSinks;
    Clock clock = Clock.systemUTC();

    public Map<String, Object> preview(ApiRequest request) {
        return store.transaction(() -> previewInTransaction(request));
    }

    private Map<String, Object> previewInTransaction(ApiRequest request) {
        String id = "aicataloglaunch-" + UUID.randomUUID();
        return audited(request, "preview", id, () -> {
            enabled(); Policy actor = access.actor(request);
            Map<String, Object> input = input(request, Set.of("type", "templateId", "versionId", "name", "description", "startOnCreate",
                    "answers", "referenceBindings", "optionsRef"));
            String template = id(input.get("templateId"), TEMPLATE), version = id(input.get("versionId"), VERSION);
            String name = text(input.get("name"), 1, 63);
            if (!name.matches("[A-Za-z0-9-]{1,63}") || !(input.get("startOnCreate") instanceof Boolean)) throw error(422, "CatalogLaunchInputInvalid");
            Map<String, Object> plan = AiCatalogLaunchOptionsService.binding(actor, request);
            plan.put("id", id); plan.put("templateId", template); plan.put("versionId", version); plan.put("name", name);
            plan.put("description", input.get("description") == null ? null : text(input.get("description"), 0, 1024));
            plan.put("startOnCreate", input.get("startOnCreate")); plan.put("status", "needs_user"); plan.put("confirmationState", "pending");
            plan.put("verifiedResult", false); plan.put("expiresAt", clock.instant().plusSeconds(900).toString());
            var authority = authority(plan, request);
            var snapshot = options.get(id(input.get("optionsRef"), "acopts-[0-9a-f-]{36}"),
                    AiCatalogLaunchOptionsStore.binding(request, actor), template, version);
            Map<String, Object> material = gateway.material(access.project(actor), template, version);
            if (!snapshot.digest().equals(material.get("digest")) ||
                    !graphs.digest(snapshot.questions()).equals(graphs.digest(questions(material.get("questionSchema")))))
                throw error(409, "CatalogLaunchSourceChanged");
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("digest", material.get("digest")); values.put("safeAnswers", primitives(input.getOrDefault("answers", Map.of())));
            values.put("questionSchemaDigest", graphs.digest(snapshot.questions()));
            values.put("humanAnswers", new LinkedHashMap<>()); values.put("referenceBindings", primitives(input.getOrDefault("referenceBindings", Map.of())));
            rebuild(request, plan, values, material, authority);
            return locked("actor-" + actor.getAuthenticatedAsAccountId(), () -> store.transaction(() -> {
                if (store.pendingCount(actor.getAuthenticatedAsAccountId(), clock.millis()) >= 64) throw error(409, "ResourceChangeQuotaExceeded");
                plan.put("privateEnvelope", privateData.seal(plan, values));
                audit(request, "preview", plan, true, "CatalogLaunchPreviewCreated"); store.insert(plan); return output(plan);
            }));
        });
    }

    public Map<String, Object> get(String id, ApiRequest request) {
        return audited(request, "read", id, () -> locked(id, () -> store.transaction(() -> {
            Map<String, Object> plan = owned(id, request); authority(plan, request);
            if (Set.of("dispatching", "accepted", "running", "unknown").contains(plan.get("status"))) observation.refresh(plan, request);
            else if (plan.get("internalStackId") != null) observation.visible(plan, request);
            else if ("needs_user".equals(plan.get("status")) && expired(plan)) {
                plan.put("status", "failed"); plan.put("reason", "CatalogLaunchPlanExpired"); store.save(plan);
            } else if ("needs_user".equals(plan.get("status"))) {
                try {
                    Map<String, Object> values = privateData.open(plan);
                    Map<String, Object> material = gateway.material(access.project(ApiUtils.getPolicy()),
                            (String) plan.get("templateId"), (String) plan.get("versionId"));
                    source(values, material);
                } catch (ClientVisibleException changed) {
                    if (!"CatalogLaunchSourceChanged".equals(changed.getCode())) throw changed;
                    plan.put("status", "failed"); plan.put("reason", "CatalogLaunchSourceChanged"); store.save(plan);
                }
            }
            audit(request, "read", plan, true, "CatalogLaunchReadAllowed"); return output(plan);
        })));
    }

    public List<Map<String, Object>> pending(ApiRequest request) {
        try {
            human(request); Policy actor = access.actor(request);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> plan : store.pending(actor.getAuthenticatedAsAccountId(), actor.getAccountId(), clock.millis())) {
                try { authority(plan, request); }
                catch (ClientVisibleException denied) {
                    if (denied.getStatus() != 403 && denied.getStatus() != 404) throw denied;
                    audit(request, "read", plan, false, denied.getCode()); continue;
                }
                if (Set.of("pending", "approved").contains(plan.get("confirmationState")) && plan.get("internalStackId") == null) result.add(output(plan));
            }
            audit(request, "read", new LinkedHashMap<>(), true, "CatalogLaunchPendingPlansRead"); return result;
        } catch (ClientVisibleException denied) {
            if (!"CatalogLaunchAuditUnavailable".equals(denied.getCode())) audit(request, "read", new LinkedHashMap<>(), false, denied.getCode());
            throw error(denied.getStatus(), denied.getCode());
        }
    }

    public Map<String, Object> action(String id, ApiRequest request) {
        String action = request.getAction();
        return audited(request, action, id, () -> locked(id, () -> store.transaction(() -> {
            enabled(); Map<String, Object> plan = owned(id, request);
            if (!Set.of("completeInputs", "confirm", "apply", "cancel").contains(action == null ? "" : action)) throw error(422, "CatalogLaunchInputInvalid");
            if (!"apply".equals(action)) human(request);
            var authority = authority(plan, request);
            if (!"needs_user".equals(plan.get("status"))) {
                if ("apply".equals(action) && "approved".equals(plan.get("confirmationState"))) {
                    observation.visible(plan, request); audit(request, action, plan, true, "CatalogLaunchAlreadyDispatched"); return output(plan);
                }
                throw error(409, "CatalogLaunchSourceChanged");
            }
            if (expired(plan)) throw error(409, "CatalogLaunchPlanExpired");
            if ("cancel".equals(action)) {
                input(request, Set.of()); plan.put("confirmationState", "cancelled"); plan.put("status", "cancelled");
                plan.put("reason", "CatalogLaunchCancelled");
                audit(request, action, plan, true, "CatalogLaunchCancelled"); store.save(plan); return output(plan);
            }
            Map<String, Object> values = privateData.open(plan);
            Map<String, Object> material = gateway.material(access.project(authority.current().policy()), (String) plan.get("templateId"),
                    (String) plan.get("versionId"));
            source(values, material);
            if ("completeInputs".equals(action)) {
                Map<String, Object> input = input(request, Set.of("answers", "referenceBindings"));
                Map<String, Object> human = new LinkedHashMap<>(map(values.get("humanAnswers")));
                human.putAll(primitives(input.getOrDefault("answers", Map.of()))); values.put("humanAnswers", human);
                Map<String, Object> refs = new LinkedHashMap<>(map(values.get("referenceBindings")));
                refs.putAll(primitives(input.getOrDefault("referenceBindings", Map.of()))); values.put("referenceBindings", refs);
                plan.put("confirmationState", "pending");
            } else input(request, Set.of());
            String before = (String) values.get("graphDigest");
            rebuild(request, plan, values, material, authority);
            if (!"completeInputs".equals(action) && before != null && !before.equals(values.get("graphDigest"))) throw error(409, "CatalogLaunchSourceChanged");
            if (!"completeInputs".equals(action) && !Boolean.TRUE.equals(plan.get("inputsReady"))) throw error(409, "CatalogLaunchNeedsHumanInputs");
            if ("confirm".equals(action)) { plan.put("confirmationState", "approved"); plan.put("reason", "CatalogLaunchConfirmed"); }
            if ("apply".equals(action) && !"approved".equals(plan.get("confirmationState"))) throw error(409, "CatalogLaunchNeedsConfirmation");
            plan.put("privateEnvelope", privateData.seal(plan, values));
            audit(request, action, plan, true, "CatalogLaunchAllowed");
            if ("apply".equals(action)) dispatch(plan, values, request, authority); else store.save(plan);
            return output(plan);
        })));
    }

    private void dispatch(Map<String, Object> plan, Map<String, Object> values, ApiRequest request, AiCatalogLaunchAccess.Authorization authority) {
        plan.put("status", "dispatching"); plan.put("acceptedAt", clock.millis());
        if (!store.claim(plan)) throw error(409, "CatalogLaunchSourceChanged");
        try {
            Object created = access.domain(request, authority, "stack", "POST", null, null, map(values.get("stackBody")), domain ->
                    binding.create(plan, () -> access.manager("stack").create("stack", domain)));
            if (created == null || plan.get("internalStackId") == null
                    || !Objects.equals(ObjectUtils.getId(created), plan.get("internalStackId"))) throw error(503, "CatalogLaunchUnavailable");
            plan.put("status", "accepted"); plan.put("reason", "CatalogLaunchAccepted"); plan.put("dispatchAcknowledged", true);
        } catch (RuntimeException uncertain) {
            // A claimed mutation is never retried, even when its normal manager's outcome is not yet observable.
            plan.put("status", "unknown"); plan.put("reason", "CatalogLaunchDispatchUncertain");
        }
        store.save(plan);
    }

    private void rebuild(ApiRequest request, Map<String, Object> plan, Map<String, Object> values, Map<String, Object> material,
            AiCatalogLaunchAccess.Authorization authority) {
        access.rootCreate(authority);
        access.launchReads(authority, false);
        // Every supported launch configures links, including the empty set.
        // Reject insufficient scope before collecting or persisting human inputs.
        access.childActions(authority, "service", Boolean.TRUE.equals(plan.get("startOnCreate")));
        List<Map<String, Object>> schema = questions(material.get("questionSchema"));
        Map<String, Object> references = map(values.get("referenceBindings"));
        for (var entry : references.entrySet()) {
            Map<String, Object> question = schema.stream().filter(row -> entry.getKey().equals(row.get("name")) && "reference".equals(row.get("type")))
                    .findFirst().orElseThrow(() -> error(422, "CatalogLaunchInputInvalid"));
            String wireId = text(entry.getValue(), 0, 128);
            if (!wireId.isEmpty()) materializer.reference(request, authority, (String) question.get("referenceType"), wireId);
        }
        Map<String, Object> validation = new LinkedHashMap<>();
        for (String field : List.of("templateId", "versionId")) validation.put(field, plan.get(field));
        for (String field : List.of("digest", "safeAnswers", "humanAnswers", "referenceBindings")) validation.put(field, values.get(field));
        Map<String, Object> checked = gateway.validate(access.project(authority.current().policy()), validation);
        if (!Objects.equals(values.get("digest"), checked.get("digest"))) throw error(409, "CatalogLaunchSourceChanged");
        List<Map<String, Object>> missing = questions(checked.get("missingHumanInputs"));
        for (Map<String, Object> question : missing) {
            if (schema.stream().noneMatch(candidate -> graphs.digest(candidate).equals(graphs.digest(question)))) throw error(503, "CatalogLaunchUnavailable");
        }
        plan.put("missingHumanInputs", missing); plan.put("inputsReady", missing.isEmpty());
        Map<String, Object> graph = null;
        if (missing.isEmpty()) {
            var prepared = materializer.prepare(request, authority, plan, material, variables(checked.get("variables"), schema));
            graph = prepared.nativeGraph();
            access.launchReads(authority, !((List<?>) graph.get("volumes")).isEmpty());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", plan.get("name")); body.put("description", plan.get("description")); body.put("startOnCreate", plan.get("startOnCreate"));
            body.put("externalId", text(material.get("externalId"), 1, 4096));
            body.put("dockerCompose", graph.get("dockerCompose")); body.put("rancherCompose", graph.get("rancherCompose"));
                    body.put("environment", graph.get("variables"));
            Map<String, Object> validated = access.domain(request, authority, "stack", "POST", null, null, body, domain -> map(domain.getRequestObject()));
            if (!graphs.digest(body.get("environment")).equals(graphs.digest(validated.get("environment")))) throw error(422, "CatalogLaunchUnsupported");
            values.put("stackBody", body); values.put("graph", graph); values.put("authorizedReferenceIds", prepared.authorizedReferenceIds());
            values.put("graphDigest", graphs.digest(Map.of("nativeGraph", graph, "authorizedReferenceIds", prepared.authorizedReferenceIds())));
        } else {
            values.remove("stackBody"); values.remove("graph"); values.remove("authorizedReferenceIds"); values.remove("graphDigest");
        }
        Map<String, Object> template = map(material.get("template")), version = map(material.get("version"));
        Map<String, Object> summary = new LinkedHashMap<>(); summary.put("stackName", plan.get("name"));
        for (String field : List.of("templateKey", "name", "catalogScope", "catalogId")) {
            String destination = "name".equals(field) ? "templateName" : field;
            summary.put(destination, text(template.get(field), 0, 512));
        }
        summary.put("version", text(version.get("version"), 1, 512)); summary.put("revision", version.get("revision"));
        summary.put("serviceCount", graph == null ? 0 : ((List<?>) graph.get("services")).size());
        summary.put("volumeCount", graph == null ? 0 : ((List<?>) graph.get("volumes")).size()); summary.put("startsServices", plan.get("startOnCreate"));
        plan.put("summary", summary); List<Map<String, Object>> changes = new ArrayList<>();
        for (String field : List.of("name", "description", "startOnCreate")) changes.add(change(field, plan.get(field)));
        changes.add(change("catalogTemplate", summary.get("templateKey"))); changes.add(change("catalogVersion", summary.get("version")));
        changes.add(change("serviceCount", summary.get("serviceCount"))); changes.add(change("volumeCount", summary.get("volumeCount")));
        changes.add(change("humanInputCount", schema.stream().filter(row -> Boolean.TRUE.equals(row.get("requiresHuman"))).count()));
        changes.add(change("referenceCount", schema.stream().filter(row -> "reference".equals(row.get("type"))).count()));
        plan.put("changes", changes); plan.put("reason", missing.isEmpty() ? "CatalogLaunchNeedsConfirmation" : "CatalogLaunchNeedsHumanInputs");
    }

    private AiCatalogLaunchAccess.Authorization authority(Map<String, Object> plan, ApiRequest request) {
        var authority = access.bound(plan, request); access.catalog(authority);
        var schema = authority.current().schemas().getSchema(PLAN);
        String action = request.getAction();
        if (schema == null || "GET".equals(request.getMethod()) && (schema.getResourceMethods() == null || !schema.getResourceMethods().contains("GET"))
                || "POST".equals(request.getMethod()) && action == null && (schema.getCollectionMethods() == null ||
                        !schema.getCollectionMethods().contains("POST"))
                || action != null && (schema.getResourceActions() == null || !schema.getResourceActions().containsKey(action)))
            throw error(403, "OwnerPermissionDenied");
        return authority;
    }

    private Map<String, Object> owned(String id, ApiRequest request) {
        if (id == null || !id.matches("aicataloglaunch-[0-9a-f-]{36}")) throw error(404, "NotFound");
        Map<String, Object> plan = store.loadForUpdate(id); Policy actor = access.actor(request);
        if (plan == null || !Objects.equals(((Number) plan.get("actorId")).longValue(), actor.getAuthenticatedAsAccountId())
                || !Objects.equals(((Number) plan.get("accountId")).longValue(), actor.getAccountId())) throw error(404, "NotFound");
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key != null && (!(plan.get("credentialId") instanceof Number credential) || credential.longValue() != key.credentialId()
                || !(plan.get("credentialRevision") instanceof Number revision) || revision.longValue() != key.revision()
                || !Objects.equals(plan.get("credentialKind"), key.kind()))) throw error(403, "OwnerPermissionDenied");
        return plan;
    }

    protected Map<String, Object> output(Map<String, Object> plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "status", "reason", "verifiedResult", "inputsReady", "summary", "changes", "missingHumanInputs",
                "expiresAt", "confirmationState"))
            result.put(field, plan.get(field));
        if ("dispatching".equals(result.get("status"))) result.put("status", "running");
        result.put("missingHumanInputs", questions(plan.getOrDefault("missingHumanInputs", List.of())));
        result.put("type", PLAN); result.put("resourceTextIsUntrusted", true);
        result.put("requiresHumanConfirmation", "needs_user".equals(plan.get("status")) && "pending".equals(plan.get("confirmationState"))
                && Boolean.TRUE.equals(plan.get("inputsReady")) && !expired(plan));
        result.put("createdResource", plan.get("internalStackId") instanceof Number stack
                ? Map.of("resourceType", "stack", "id", ApiContext.getContext().getIdFormatter().formatId("stack", stack.longValue()), "name",
                        plan.get("name")) : null);
        return result;
    }

    private void source(Map<String, Object> values, Map<String, Object> material) {
        if (!Objects.equals(values.get("digest"), material.get("digest"))
                || !Objects.equals(values.get("questionSchemaDigest"), graphs.digest(questions(material.get("questionSchema")))))
            throw error(409, "CatalogLaunchSourceChanged");
    }

    private boolean expired(Map<String, Object> plan) { return !clock.instant().isBefore(Instant.parse((String) plan.get("expiresAt"))); }
    protected void enabled() { if (!AiCatalogLaunchOptionsService.enabled()) throw error(503, "CatalogLaunchUnavailable"); }
    private void human(ApiRequest request) {
        if (ApiKeyCredentialContext.get(request) != null || !"TokenAuth".equals(request.getAttribute(AccountConstants.AUTH_TYPE))
                || !io.cattle.platform.api.auth.ApiCookieAuthentication.isVerified(request)) throw error(403, "AiKeyConfirmationForbidden");
    }
    private Map<String, Object> input(ApiRequest request, Set<String> fields) {
        java.util.HashSet<String> allowed = new java.util.HashSet<>(fields);
        if (request.getAction() != null) allowed.add("action");
        Map<String, Object> input = closed(request.getRequestObject() == null ? Map.of() : request.getRequestObject(), allowed);
        if (input.containsKey("action")) {
            Object routed = request.getRequestParams() == null ? null
                    : io.github.ibuildthecloud.gdapi.util.RequestUtils.makeSingularIfCan(request.getRequestParams().get("action"));
            if (!Objects.equals(request.getAction(), input.remove("action")) || !Objects.equals(request.getAction(), routed))
                throw error(422, "CatalogLaunchInputInvalid");
        }
        if (input.get("type") != null && !PLAN.equals(input.get("type"))) throw error(422, "CatalogLaunchInputInvalid");
        return input;
    }
    private static Map<String, Object> change(String field, Object after) {
        Map<String, Object> change = new LinkedHashMap<>(); change.put("field", field); change.put("before", null); change.put("after", after); return change;
    }
    void audit(ApiRequest request, String action, Map<String, Object> plan, boolean allowed, String reason) {
        if (auditSinks == null || auditSinks.isEmpty()) throw error(503, "CatalogLaunchAuditUnavailable");
        try { for (ApiKeyAuditSink sink : auditSinks) sink.recordCatalogLaunchDecision(request, ApiUtils.getPolicy(), action, (String) plan.get("id"),
                plan.get("internalStackId") instanceof Number stack ? stack.longValue() : null, allowed, reason); }
        catch (RuntimeException unavailable) { throw error(503, "CatalogLaunchAuditUnavailable"); }
    }
    private Map<String, Object> audited(ApiRequest request, String action, String id, Supplier<Map<String, Object>> operation) {
        try { return operation.get(); }
        catch (ClientVisibleException denied) {
            if (!"CatalogLaunchAuditUnavailable".equals(denied.getCode())) {
                Map<String, Object> receipt = new LinkedHashMap<>(); receipt.put("id", id); audit(request, action, receipt, false, denied.getCode());
            }
            throw error(denied.getStatus(), denied.getCode());
        }
    }
    private <T> T locked(String id, Supplier<T> operation) {
        return locks.lock(new AbstractBlockingLockDefintion("AICATALOGLAUNCH:" + id) { }, new LockCallback<T>() {
            @Override public T doWithLock() { return operation.get(); }
        });
    }
}
