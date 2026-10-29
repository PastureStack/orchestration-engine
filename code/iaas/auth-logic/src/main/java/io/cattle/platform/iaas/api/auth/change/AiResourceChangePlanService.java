package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.RequestUtils;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Separate typed-resource workflow: persisted approval, CAS, dispatch claim and receipt. */
public class AiResourceChangePlanService {
    @Inject AiResourceChangePlanStore store;
    @Inject AiResourceChangeDomain domain;
    @Inject LockManager locks;
    @Inject List<ApiKeyAuditSink> auditSinks;
    Clock clock = Clock.systemUTC();

    public Map<String, Object> preview(ApiRequest request) {
        String id = "aichange-" + UUID.randomUUID();
        return audited(request, "preview", id, () -> {
            requireEnabled();
            Map<String, Object> input = input(request, Set.of("type", "resourceType", "operation", "resourceId", "parameters"));
            if (!(input.get("resourceType") instanceof String) || !(input.get("operation") instanceof String)
                    || input.get("resourceId") != null && !(input.get("resourceId") instanceof String)) throw error(422, "ResourceChangeInputInvalid");
            AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require(text(input.get("resourceType")), text(input.get("operation")));
            Map<String, Object> parameters = descriptor.parameters(input.getOrDefault("parameters", Map.of()));
            String resourceId = text(input.get("resourceId"));
            if (descriptor.createsResource() ? resourceId != null : resourceId == null) throw error(422, "ResourceChangeInputInvalid");
            Object target = resourceId == null ? null : domain.lock(descriptor.resourceType(), domain.internalId(descriptor.resourceType(), resourceId));
            Object parent = descriptor.requiresStackParent()
                    ? domain.lock("stack", domain.internalId("stack", text(parameters.get("stackId")))) : null;
            Policy actor = domain.actor(request);
            if (target != null) visibleTarget(request, descriptor, resourceId, target);
            if (parent != null) domain.parent(request, descriptor, parameters);
            domain.ready(descriptor, target, parent);
            Map<String, Object> body = domain.prepare(request, descriptor, resourceId, parameters);
            Supplier<Map<String, Object>> save = () -> locked("actor-" + actor.getAuthenticatedAsAccountId(), () -> {
                store.prune(clock.millis());
                if (store.pendingCount(actor.getAuthenticatedAsAccountId()) >= 64) throw error(409, "ResourceChangeQuotaExceeded");
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("id", id); plan.put("resourceType", descriptor.resourceType()); plan.put("operation", descriptor.operation());
                plan.put("resourceId", resourceId); plan.put("internalResourceId", target == null ? null : ObjectUtils.getId(target));
                plan.put("actorId", actor.getAuthenticatedAsAccountId()); plan.put("accountId", actor.getAccountId());
                plan.put("parameters", parameters); plan.put("body", body);
                if ("dnsService".equals(descriptor.resourceType()) && Set.of("update_targets", "remove").contains(descriptor.operation()))
                    plan.put("referenceSnapshot", domain.aliasReferenceSnapshot(request, descriptor, resourceId, parameters));
                plan.put("snapshot", domain.snapshot(target)); plan.put("parentSnapshot", domain.snapshot(parent));
                plan.put("targetPath", domain.targetPath(request, target, parent, parameters));
                plan.put("changes", domain.changes(descriptor, target, parameters));
                plan.put("expiresAt", clock.instant().plusSeconds(300).toString());
                plan.put("confirmationState", "pending"); plan.put("status", "preview"); plan.put("verifiedResult", false);
                plan.put("messageCode", "ResourceChangePreviewCreated");
                ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
                if (key != null) {
                    plan.put("credentialId", key.credentialId()); plan.put("credentialRevision", key.revision()); plan.put("credentialKind", key.kind());
                }
                audit(request, "preview", plan, true, "ResourceChangePreviewCreated");
                store.insert(plan); return output(plan);
            });
            return descriptor.changesArchive() ? domain.withArchiveChange(descriptor, target, save) : save.get();
        });
    }

    public Map<String, Object> get(String id, ApiRequest request) {
        return audited(request, "read", id, () -> locked(validId(id), () -> {
            Map<String, Object> plan = owned(store.load(validId(id)), request);
            observe(plan, request); audit(request, "read", plan, true, "ResourceChangeRead"); return output(plan);
        }));
    }

    public List<Map<String, Object>> pending(ApiRequest request) {
        human(request); Policy actor = domain.actor(request); List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> plan : store.pending(actor.getAuthenticatedAsAccountId(), clock.millis())) {
            if (number(plan.get("accountId")) != actor.getAccountId() || !"preview".equals(plan.get("status"))
                    || plan.get("operationId") != null || !Set.of("pending", "approved").contains(Objects.toString(plan.get("confirmationState"),
                            ""))) continue;
            try {
                owned(plan, request); AiResourceChangeDescriptor descriptor = descriptor(plan);
                if (!descriptor.createsResource()) domain.visible(request, descriptor, text(plan.get("resourceId")));
                domain.parent(request, descriptor, map(plan.get("parameters")));
                domain.prepare(request, descriptor, text(plan.get("resourceId")), map(plan.get("parameters")), plan);
                result.add(output(plan));
            } catch (ClientVisibleException denied) { if (denied.getStatus() >= 500) throw denied; }
        }
        audit(request, "read", Map.of(), true, "ResourceChangePendingPlansRead");
        return result;
    }

    public Map<String, Object> action(String id, ApiRequest request) {
        String action = request.getAction();
        return audited(request, action, id, () -> locked(validId(id), () -> {
            if (!Set.of("confirm", "apply", "cancel").contains(action == null ? "" : action)) throw error(422, "ResourceChangeInputInvalid");
            if (!"apply".equals(action)) human(request);
            Map<String, Object> actionInput = input(request, Set.of("type", "action"));
            Object query = request.getRequestParams() == null ? null : RequestUtils.makeSingularIfCan(request.getRequestParams().get("action"));
            if (actionInput.containsKey("action") && (!Objects.equals(action, actionInput.get("action")) || !Objects.equals(action, query)))
                throw error(422, "ResourceChangeInputInvalid");
            // Locking reads precede regular domain lookups in the write transaction.
            Map<String, Object> loaded = store.loadForUpdate(id);
            if (loaded == null) throw error(404, "NotFound");
            AiResourceChangeDescriptor descriptor = descriptor(loaded);
            Object target = loaded.get("internalResourceId") instanceof Number targetId ? domain.lock(descriptor.resourceType(), targetId.longValue()) : null;
            Map<String, Object> parameters = map(loaded.get("parameters"));
            Object parent = descriptor.requiresStackParent()
                    ? domain.lock("stack", domain.internalId("stack", text(parameters.get("stackId")))) : null;
            Map<String, Object> plan = owned(loaded, request);
            if ("apply".equals(action) && !"preview".equals(plan.get("status"))) {
                if (plan.get("operationId") == null) throw error(409, "ResourceChangeCancelled");
                observe(plan, request); audit(request, action, plan, true, "ResourceChangeExistingOperation"); return output(plan);
            }
            if (!"preview".equals(plan.get("status"))) throw error(409, "ResourceChangeCancelled");
            if ("cancel".equals(action)) {
                audit(request, action, plan, true, "ResourceChangeCancelled");
                plan.put("confirmationState", "cancelled"); plan.put("status", "cancelled"); plan.put("messageCode", "ResourceChangeCancelled");
                eraseSnapshot(plan); store.save(plan); return output(plan);
            }
            requireEnabled();
            if (!clock.instant().isBefore(java.time.Instant.parse(text(plan.get("expiresAt"))))) throw error(409, "ResourceChangePlanExpired");
            if (!domain.snapshot(target).equals(plan.get("snapshot")) || !domain.snapshot(parent).equals(plan.get("parentSnapshot")))
                throw error(409, "ResourceChangePlanStale");
            if (!descriptor.createsResource()) visibleTarget(request, descriptor, text(plan.get("resourceId")), target);
            domain.parent(request, descriptor, parameters); domain.ready(descriptor, target, parent);
            if (!domain.prepare(request, descriptor, text(plan.get("resourceId")), parameters, plan).equals(plan.get("body")))
                throw error(409, "ResourceChangePlanStale");
            Supplier<Map<String, Object>> change = () -> {
                if ("confirm".equals(action)) {
                    audit(request, action, plan, true, "ResourceChangeConfirmed");
                    plan.put("confirmationState", "approved"); plan.put("messageCode", "ResourceChangeReady"); store.save(plan); return output(plan);
                }
                if (!"approved".equals(plan.get("confirmationState"))) throw error(409, "ResourceChangeNeedsConfirmation");
                if (plan.containsKey("credentialId") && ApiKeyCredentialContext.get(request) == null) throw error(403, "ResourceChangeBoundKeyRequired");
                audit(request, action, plan, true, "ResourceChangeAccepted");
                plan.put("status", "dispatching"); plan.put("operationId", id); plan.put("acceptedAt", clock.millis());
                plan.put("messageCode", "ResourceChangeDispatchUncertain");
                if (!store.claim(plan)) return output(owned(store.load(id), request));
                try {
                    Object result = domain.dispatch(request, descriptor, text(plan.get("resourceId")), parameters, id);
                    if (descriptor.createsResource()) {
                        Object createdId = ObjectUtils.getId(result);
                        long numericId = createdId instanceof Number numeric ? numeric.longValue()
                                : domain.internalId(descriptor.resourceType(), text(createdId));
                        if (numericId <= 0) throw new IllegalStateException("Created resource identity unavailable");
                        plan.put("internalResourceId", numericId); plan.put("resourceId", domain.publicId(descriptor.resourceType(), numericId));
                    }
                    plan.put("dispatchAcknowledged", true);
                    plan.put("status", "accepted"); plan.put("messageCode", "ResourceChangeAccepted");
                } catch (RuntimeException uncertain) {
                    // A domain manager can schedule work before its response fails.
                    // The durable claim is never reset and this operation never replays.
                    plan.put("status", "unknown"); plan.put("messageCode", "ResourceChangeDispatchUncertain");
                    plan.put("dispatchAcknowledged", false);
                }
                store.save(plan); return output(plan);
            };
            return descriptor.changesArchive() ? domain.withArchiveChange(descriptor, target, change) : change.get();
        }));
    }

    private void visibleTarget(ApiRequest request, AiResourceChangeDescriptor descriptor, String id, Object lockedTarget) {
        Object visible = domain.visible(request, descriptor, id);
        // The final normal manager also uses the ambient RR snapshot. Reject an older view before claiming a JSON metadata write.
        if (descriptor.changesArchive() && !domain.snapshot(lockedTarget).equals(domain.snapshot(visible)))
            throw error(409, "ArchiveConcurrentChange");
    }

    protected Map<String, Object> owned(Map<String, Object> plan, ApiRequest request) {
        Policy actor = domain.actor(request);
        if (plan == null || number(plan.get("actorId")) != actor.getAuthenticatedAsAccountId() || number(plan.get("accountId")) != actor.getAccountId())
            throw error(404, "NotFound");
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key != null && (number(plan.get("credentialId")) != key.credentialId()
                || number(plan.get("credentialRevision")) != key.revision() || !Objects.equals(plan.get("credentialKind"), key.kind()))) throw
                        error(404, "NotFound");
        if (plan.get("internalResourceId") instanceof Number) domain.receiptTarget(request, descriptor(plan), plan);
        return plan;
    }

    protected void observe(Map<String, Object> plan, ApiRequest request) {
        if (AiResourceChangePlanStore.projectFailure(plan)) { eraseSnapshot(plan); store.save(plan); return; }
        String status = text(plan.get("status"));
        if (!Set.of("dispatching", "accepted", "running", "unknown").contains(status)) return;
        // A coincidentally matching state cannot prove that an interrupted
        // dispatch ran. Keep that durable operation unknown, never replay it.
        if (!Boolean.TRUE.equals(plan.get("dispatchAcknowledged"))) {
            plan.put("status", "unknown"); plan.put("messageCode", "ResourceChangeDispatchUncertain"); store.save(plan); return;
        }
        if (!(plan.get("internalResourceId") instanceof Number)) return;
        AiResourceChangeDescriptor descriptor = descriptor(plan);
        Object target = domain.receiptTarget(request, descriptor, plan);
        if (domain.complete(descriptor, target, map(plan.get("parameters")), map(plan.get("snapshot")))) {
            plan.put("status", "succeeded"); plan.put("verifiedResult", true); plan.put("messageCode", "ResourceChangeCompleted"); eraseSnapshot(plan);
        } else if (Set.of("error", "erroring").contains(Objects.toString(ObjectUtils.getState(target), ""))) {
            plan.put("status", "failed"); plan.put("messageCode", "ResourceChangeProcessFailed"); eraseSnapshot(plan);
        } else if ("unknown".equals(status) || "dispatching".equals(status) || clock.millis() - number(plan.get("acceptedAt")) > 600000) {
            plan.put("status", "unknown"); plan.put("messageCode", "ResourceChangeDispatchUncertain");
        } else { plan.put("status", "running"); plan.put("messageCode", "ResourceChangeRunning"); }
        store.save(plan);
    }

    protected void requireEnabled() {
        if (!Boolean.TRUE.equals(ArchaiusUtil.getBooleanProperty("api.ai.resource.change.enabled").get())) throw error(503, "ResourceChangeFeatureDisabled");
    }

    private void human(ApiRequest request) {
        if (ApiKeyCredentialContext.get(request) != null || !"TokenAuth".equals(request.getAttribute(AccountConstants.AUTH_TYPE)))
            throw error(403, "AiKeyConfirmationForbidden");
    }

    protected void audit(ApiRequest request, String action, Map<String, Object> plan, boolean allowed, String reason) {
        if (auditSinks == null || auditSinks.isEmpty()) throw error(503, "ResourceChangeAuditUnavailable");
        try {
            for (ApiKeyAuditSink sink : auditSinks) sink.recordResourceChangeDecision(request, ApiUtils.getPolicy(), action,
                    text(plan.get("id")), Objects.toString(plan.get("resourceType"), "aiResourceChangePlan"),
                    plan.get("internalResourceId") instanceof Number id ? id.longValue() : null, allowed, reason);
        } catch (RuntimeException unavailable) { throw error(503, "ResourceChangeAuditUnavailable"); }
    }

    private Map<String, Object> audited(ApiRequest request, String action, String id, Supplier<Map<String, Object>> operation) {
        try { return operation.get(); }
        catch (ClientVisibleException denied) {
            Map<String, Object> receipt = new LinkedHashMap<>(); receipt.put("id", id);
            audit(request, Objects.toString(action, "unknown"), receipt, false, denied.getCode());
            throw error(denied.getStatus(), denied.getCode());
        }
    }

    private <T> T locked(String id, Supplier<T> operation) {
        return locks.lock(new AbstractBlockingLockDefintion("AIRESOURCECHANGE:" + id) { }, new LockCallback<T>() {
            @Override public T doWithLock() { return operation.get(); }
        });
    }

    protected Map<String, Object> output(Map<String, Object> plan) {
        AiResourceChangePlanStore.projectFailure(plan);
        Map<String, Object> output = new LinkedHashMap<>();
        for (String field : List.of("id", "resourceType", "operation", "resourceId", "targetPath", "changes", "confirmationState",
                "status", "operationId", "verifiedResult", "messageCode", "expiresAt")) output.put(field, plan.get(field));
        output.put("resourceTextIsUntrusted", true); return output;
    }

    private void eraseSnapshot(Map<String, Object> plan) {
        plan.remove("snapshot"); plan.remove("parentSnapshot"); plan.remove("body"); plan.remove("referenceSnapshot");
    }
    private AiResourceChangeDescriptor descriptor(Map<String, Object> plan) {
        return AiResourceChangeDescriptor.require(text(plan.get("resourceType")), text(plan.get("operation")));
    }
    private String validId(String id) { if (id == null || !id.matches("aichange-[0-9a-f-]{36}")) throw error(404, "NotFound"); return id; }
    private Map<String, Object> input(ApiRequest request, Set<String> allowed) {
        Map<String, Object> result = map(request.getRequestObject());
        if (!allowed.containsAll(result.keySet()) || result.containsKey("type") && !"aiResourceChangePlan".equals(result.get("type")))
            throw error(422, "ResourceChangeInputInvalid");
        return result;
    }
    private static Map<String, Object> map(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> source)) throw error(422, "ResourceChangeInputInvalid");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw error(422, "ResourceChangeInputInvalid");
            result.put(key, entry.getValue());
        }
        return result;
    }
    private static String text(Object value) { return value instanceof String text ? text : null; }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }
    private static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
