package io.cattle.platform.iaas.api.auth.upgrade;

import static io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.model.Nic;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.InstanceLink;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.storage.api.filter.ExternalTemplateInstanceFilter;
import io.cattle.platform.storage.service.StorageService;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.util.RequestUtils;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Actor-bound approval/receipt shared by cookie collaboration and isolated Key clients. */
public class AiServiceUpgradePlanService extends AbstractJooqDao {
    private static final Logger log = LoggerFactory.getLogger(AiServiceUpgradePlanService.class);
    public static final String PLAN_ATTRIBUTE = "ai.service.upgrade.plan";
    public static final String SNAPSHOT_ATTRIBUTE = "ai.service.upgrade.snapshot";
    @Inject AiServiceUpgradePlanStore store;
    @Inject ObjectManager objects;
    @Inject JsonMapper json;
    @Inject ResourceManagerLocator managers;
    @Inject LockManager locks;
    @Inject StorageService storage;
    @Inject List<ApiKeyAuditSink> auditSinks;
    @Inject AiKeyAuthorization keyAuthorization;
    Clock clock = Clock.systemUTC();

    public Map<String, Object> preview(ApiRequest request) {
        return audited(request, "preview", null, () -> {
            requireEnabled();
            Policy actor = actor(request);
            return locked("preview-" + actor.getAuthenticatedAsAccountId(), () -> {
                store.pruneExpiredPreviews(clock.millis());
                if (store.pendingCount(actor.getAuthenticatedAsAccountId()) >= 20) throw error(429, "UpgradePendingPlanLimit");
                Map<String, Object> input = input(request, Set.of("serviceId", "image", "type"));
                String serviceId = text(input.get("serviceId"));
                String image = text(input.get("image"));
                if (serviceId == null || image == null || image.length() > 512 || image.matches(".*\\s.*")
                        || image.contains("://") || image.equals("docker:")) throw error(422, "UpgradeInputInvalid");
                Service target = target(serviceId, request, true);
                requireReady(target);
                Map<String, Object> snapshot = snapshot(target);
                Map<String, Object> primary = map(snapshot.get("launchConfig"));
                if (primary.isEmpty() || primary.get("imageUuid") == null) throw error(409, "UpgradeTargetUnsupported");
                for (Object item : secondaries(target)) {
                    if (ServiceConstants.IMAGE_NONE.equalsIgnoreCase(text(map(item).get("imageUuid")))) throw error(409, "UpgradeTargetUnsupported");
                }
                String requested = normalizeImage(image);
                if (Objects.equals(primary.get("imageUuid"), requested)) throw error(409, "UpgradeImageUnchanged");
                Map<String, Object> plan = new LinkedHashMap<>();
                // A letter prefix is required by TypeIdFormatter for opaque IDs.
                String id = "aiplan-" + UUID.randomUUID();
                plan.put("id", id); plan.put("planRef", id);
                plan.put("actorId", actor.getAuthenticatedAsAccountId()); plan.put("accountId", target.getAccountId());
                ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
                if (key != null) {
                    plan.put("credentialId", key.credentialId()); plan.put("credentialRevision", key.revision());
                    plan.put("credentialKind", key.kind());
                }
                plan.put("serviceId", formattedServiceId(target)); plan.put("internalServiceId", target.getId());
                plan.put("serviceName", target.getName()); plan.put("currentImage", primary.get("imageUuid"));
                plan.put("displayPath", displayPath(target));
                plan.put("requestedImage", requested); plan.put("snapshot", snapshot);
                plan.put("expiresAt", clock.instant().plusSeconds(300).toString());
                plan.put("confirmationState", "pending"); plan.put("status", "preview");
                plan.put("verifiedResult", false); plan.put("messageCode", "UpgradeNeedsConfirmation");
                audit(request, "preview", id, target.getId(), true, "UpgradePreviewCreated");
                store.insert(plan);
                return output(plan);
            });
        });
    }

    public Map<String, Object> get(String id, ApiRequest request) {
        return audited(request, "read", id, () -> locked(id, () -> {
            Map<String, Object> plan = owned(id, request, false);
            observe(plan, target(text(plan.get("internalServiceId")), request, false));
            audit(request, "read", id, number(plan.get("internalServiceId")), true, "UpgradeRead");
            return output(plan);
        }));
    }

    /** Human inbox only; no Key can enumerate another plan by sharing its owner. */
    public List<Map<String, Object>> pending(ApiRequest request) {
        if (ApiKeyCredentialContext.get(request) != null) throw error(403, "UpgradeHumanSessionRequired");
        Policy actor = actor(request);
        int requested = request.getPagination() == null || request.getPagination().getLimit() == null
                ? 20 : request.getPagination().getLimit();
        if (requested < 1) throw error(422, "UpgradeInputInvalid");
        int limit = Math.min(20, requested);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> plan : store.pending(actor.getAuthenticatedAsAccountId(), clock.millis())) {
            if (number(plan.get("actorId")) != actor.getAuthenticatedAsAccountId()
                    || number(plan.get("accountId")) != actor.getAccountId() || !"preview".equals(plan.get("status"))
                    || plan.get("operationId") != null || !Set.of("pending", "approved").contains(Objects.toString(plan.get("confirmationState"),
                            ""))) continue;
            try {
                Service target = target(text(plan.get("internalServiceId")), request, true);
                if (plan.containsKey("credentialId")) keyAuthorization.authorizeBound(plan, target, "upgrade", request);
                plan.put("displayPath", displayPath(target));
                result.add(output(plan));
                if (result.size() == limit) break;
            } catch (ClientVisibleException denied) {
                if (denied.getStatus() != 401 && denied.getStatus() != 403 && denied.getStatus() != 404) throw denied;
            }
        }
        audit(request, "read", null, null, true, "UpgradePendingPlansRead");
        if (request.getServletContext() != null) request.getServletContext().getResponse().setHeader("Cache-Control", "no-store");
        return result;
    }

    public Map<String, Object> action(String id, ApiRequest request) {
        String action = request.getAction();
        return audited(request, action, id, () -> locked(id, () -> {
            if (ApiKeyCredentialContext.get(request) != null && !"apply".equals(action))
                throw error(403, "AiKeyConfirmationForbidden");
            // BodyParserRequestHandler merges query parameters into the body;
            // an action with no input schema retains its exact routing action.
            Map<String, Object> actionInput = input(request, Set.of("type", "action"));
            Object queryAction = request.getRequestParams() == null ? null
                    : RequestUtils.makeSingularIfCan(request.getRequestParams().get("action"));
            if (actionInput.containsKey("action") && (!Objects.equals(action, actionInput.get("action"))
                    || !Objects.equals(action, queryAction))) {
                throw error(422, "UpgradeInputInvalid");
            }
            // No client-supplied approval, image, target or snapshot is accepted.
            Map<String, Object> plan = owned(id, request, true);
            if (plan.containsKey("credentialId") && "apply".equals(action) && ApiKeyCredentialContext.get(request) == null)
                throw error(403, "UpgradeBoundKeyRequired");
            Service target = target(text(plan.get("internalServiceId")), request, true);
            String state = text(plan.get("status"));
            if ("apply".equals(action) && !"preview".equals(state)) {
                if (plan.get("operationId") == null) throw error(409, "UpgradeCancelled");
                // Durable receipt wins over retries, including a lost response or restart.
                observe(plan, target);
                audit(request, action, id, target.getId(), true, "UpgradeExistingOperation");
                return output(plan);
            }
            if (!Set.of("confirm", "apply", "cancel").contains(action)) throw error(422, "UpgradeActionInvalid");
            if (!"preview".equals(state)) throw error(409, "UpgradeAlreadyAccepted");
            if ("cancel".equals(action)) {
                audit(request, action, id, target.getId(), true, "UpgradeCancelled");
                plan.put("confirmationState", "cancelled"); plan.put("status", "cancelled");
                plan.remove("snapshot");
                plan.put("messageCode", "UpgradeCancelled"); store.save(plan); return output(plan);
            }
            requireEnabled();
            if (!clock.instant().isBefore(java.time.Instant.parse(text(plan.get("expiresAt"))))) throw error(409, "UpgradePlanExpired");
            if ("cancelled".equals(plan.get("confirmationState"))) throw error(409, "UpgradeCancelled");
            requireReady(target);
            if (!snapshot(target).equals(plan.get("snapshot"))) throw error(409, "UpgradePlanStale");
            if ("confirm".equals(action)) {
                if (plan.containsKey("credentialId")) keyAuthorization.authorizeBound(plan, target, "upgrade", request);
                audit(request, action, id, target.getId(), true, "UpgradeConfirmed");
                plan.put("confirmationState", "approved"); plan.put("messageCode", "UpgradeReady");
                store.save(plan); return output(plan);
            }
            if (!"approved".equals(plan.get("confirmationState"))) throw error(409, "UpgradeNeedsConfirmation");
            if (plan.containsKey("credentialId")) keyAuthorization.authorizeBound(plan, target, "upgrade", request);
            audit(request, action, id, target.getId(), true, "UpgradeAccepted");
            plan.put("status", "dispatching"); plan.put("operationId", id);
            plan.put("acceptedAt", clock.millis());
            plan.put("processBaseline", processBaseline(target.getId()));
            plan.put("priorInstances", priorInstances(target));
            plan.put("messageCode", "UpgradeDispatchUncertain");
            if (!store.claim(plan)) return output(owned(id, request, true));
            try {
                dispatch(plan, target, request);
                plan.put("status", "accepted"); plan.put("messageCode", "UpgradeAccepted");
                store.save(plan);
            } catch (RuntimeException failure) {
                // The existing action may have persisted or scheduled before throwing.
                // Never infer a rollback and never dispatch a second time.
                plan.put("status", "unknown"); plan.put("messageCode", "UpgradeDispatchUncertain");
                recordDispatchFailure(plan, failure);
                store.save(plan);
            }
            return output(plan);
        }));
    }

    /** Operator diagnostics only: never log exception messages, request bodies, or launch settings. */
    protected void recordDispatchFailure(Map<String, Object> plan, RuntimeException failure) {
        Throwable cause = failure;
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int depth = 0; depth < 8 && cause.getCause() != null && seen.add(cause); depth++) cause = cause.getCause();
        String code = failure instanceof ClientVisibleException visible ? visible.getCode() : "Unclassified";
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")) code = "Unclassified";
        String location = "unknown";
        StackTraceElement[] trace = cause.getStackTrace();
        if (trace.length > 0) {
            StackTraceElement selected = trace[0];
            for (StackTraceElement frame : trace) {
                if (frame.getClassName().startsWith("io.cattle.")) { selected = frame; break; }
            }
            location = selected.getClassName() + "." + selected.getMethodName() + ":" + selected.getLineNumber();
        }
        plan.put("dispatchFailureClass", cause.getClass().getName());
        plan.put("dispatchFailureCode", code);
        plan.put("dispatchFailureLocation", location);
        log.warn("AI upgrade dispatch failed: plan={} class={} code={} source={}",
                plan.get("id"), cause.getClass().getName(), code, location);
    }

    protected void dispatch(Map<String, Object> plan, Service target, ApiRequest original) {
        Map<String, Object> primary = map(map(plan.get("snapshot")).get("launchConfig"));
        primary.put("imageUuid", plan.get("requestedImage"));
        // Only primary is submitted; the existing filter copies secondary configs and resolves
        // their dependency versions without dropping env/ports/volumes/hardware settings.
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("launchConfig", primary); strategy.put("startFirst", false);
        strategy.put("batchSize", 1); strategy.put("intervalMillis", 2000);
        ApiRequest action = new ApiRequest(original);
        action.setType(target.getKind()); action.setId(target.getId().toString());
        action.setMethod("POST"); action.setAction("upgrade");
        action.setRequestObject(new LinkedHashMap<>(Map.of("inServiceStrategy", strategy)));
        action.setAttribute(PLAN_ATTRIBUTE, plan.get("id"));
        action.setAttribute(SNAPSHOT_ATTRIBUTE, plan.get("snapshot"));
        Object result = AiKeyAuthorization.dispatch(original, action,
                () -> managers.getResourceManagerByType(target.getKind()).resourceAction(target.getKind(), action));
        if (result == null) throw error(409, "UpgradeDispatchUncertain");
    }

    protected Map<String, Object> owned(String id, ApiRequest request, boolean write) {
        Policy actor = actor(request);
        if (id == null || !id.matches("aiplan-[0-9a-f-]{36}")) throw error(404, "NotFound");
        Map<String, Object> plan = store.load(id);
        if (plan == null || number(plan.get("actorId")) != actor.getAuthenticatedAsAccountId()
                || number(plan.get("accountId")) != actor.getAccountId()) throw error(404, "NotFound");
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key != null && (!(plan.get("credentialId") instanceof Number idKey) || idKey.longValue() != key.credentialId()
                || number(plan.get("credentialRevision")) != key.revision() || !Objects.equals(plan.get("credentialKind"), key.kind())))
            throw error(404, "NotFound");
        Service target = target(text(plan.get("internalServiceId")), request, write);
        plan.put("displayPath", displayPath(target));
        return plan;
    }

    protected String displayPath(Service target) {
        Account project = objects.loadResource(Account.class, target.getAccountId());
        Stack stack = objects.loadResource(Stack.class, target.getStackId());
        if (project == null || project.getRemoved() != null || stack == null || stack.getRemoved() != null
                || !Objects.equals(stack.getAccountId(), target.getAccountId())) throw error(404, "NotFound");
        return Objects.toString(project.getName(), "") + " / " + Objects.toString(stack.getName(), "")
                + " / " + Objects.toString(target.getName(), "");
    }

    protected Policy actor(ApiRequest request) {
        if (ApiKeyCredentialContext.get(request) != null && keyAuthorization == null) throw error(503, "KeyAuthorizationUnavailable");
        Policy policy = ApiKeyCredentialContext.get(request) == null ? ApiUtils.getPolicy() : keyAuthorization.actor(request);
        if (policy == null) throw error(403, "UpgradeCookieSessionRequired");
        Account actor = objects.loadResource(Account.class, policy.getAuthenticatedAsAccountId());
        if (actor == null || actor.getRemoved() != null || !"active".equals(actor.getState())) throw error(403, "PermissionDenied");
        return policy;
    }

    protected Service target(String id, ApiRequest request, boolean write) {
        Policy policy = actor(request);
        Service loaded = objects.loadResource(Service.class, id);
        if (loaded == null || loaded.getRemoved() != null || loaded.getAccountId() != policy.getAccountId()
                || !"service".equals(loaded.getKind())) throw error(404, "NotFound");
        if (ApiKeyCredentialContext.get(request) != null) {
            if (keyAuthorization == null) throw error(503, "KeyAuthorizationUnavailable");
            keyAuthorization.authorizeService(request, loaded, write ? "upgrade" : "read");
        }
        Schema schema = request.getSchemaFactory().getSchema(loaded.getKind());
        if (schema == null || !schema.getResourceMethods().contains("GET")
                || (write && (!schema.getResourceActions().containsKey("upgrade")
                        || !schema.getResourceActions().containsKey("finishupgrade")))) throw error(403, "PermissionDenied");
        ResourceManager manager = managers.getResourceManagerByType(loaded.getKind());
        Object visible = manager.getById(loaded.getKind(), id, new ListOptions(request));
        if (!(visible instanceof Service)) throw error(404, "NotFound");
        return (Service) visible;
    }

    protected void requireEnabled() {
        if (!Boolean.TRUE.equals(ArchaiusUtil.getBooleanProperty("api.ai.service.upgrade.enabled").get())) throw error(503, "UpgradeFeatureDisabled");
    }
    private void requireReady(Service target) {
        if (!"active".equals(target.getState())) throw error(409, "UpgradeTargetBusy");
    }

    /** Exact persisted target snapshot, including scale and service settings, never returned to clients. */
    public Map<String, Object> snapshot(Service target) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("data", copy(target.getData())); snapshot.put("name", target.getName());
        snapshot.put("stackId", target.getStackId()); snapshot.put("state", target.getState());
        snapshot.put("selectorContainer", target.getSelectorContainer()); snapshot.put("selectorLink", target.getSelectorLink());
        snapshot.put("launchConfig", copy(DataAccessor.fieldMap(target, "launchConfig")));
        snapshot.put("secondaryLaunchConfigs", copy(secondaries(target)));
        return map(snapshot);
    }

    protected long processBaseline(long serviceId) {
        Long id = create().select(org.jooq.impl.DSL.max(PROCESS_INSTANCE.ID)).from(PROCESS_INSTANCE)
                .where(PROCESS_INSTANCE.RESOURCE_TYPE.eq("service").and(PROCESS_INSTANCE.RESOURCE_ID.eq(Long.toString(serviceId))))
                .fetchOne(0, Long.class);
        return id == null ? 0 : id;
    }

    protected List<Map<String, Object>> processes(Map<String, Object> plan) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var record : create().selectFrom(PROCESS_INSTANCE).where(PROCESS_INSTANCE.RESOURCE_TYPE.eq("service")
                .and(PROCESS_INSTANCE.RESOURCE_ID.eq(Long.toString(number(plan.get("internalServiceId")))))
                .and(PROCESS_INSTANCE.ID.gt(number(plan.get("processBaseline")))))
                .orderBy(PROCESS_INSTANCE.ID.desc()).limit(100).fetch()) {
            if (record.getData() != null && Objects.equals(plan.get("id"), record.getData().get("aiServiceUpgradePlanId"))) {
                Map<String, Object> observed = new LinkedHashMap<>();
                observed.put("name", record.getProcessName()); observed.put("result", record.getResult());
                observed.put("ended", record.getEndTime() != null); result.add(observed);
            }
        }
        return result;
    }

    protected boolean instancesReady(Service target) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("", 0);
        for (Object item : secondaries(target)) counts.put(text(map(item).get("name")), 0);
        Map<String, Object> launch = DataAccessor.fieldMap(target, "launchConfig");
        for (ServiceExposeMap exposure : objects.find(ServiceExposeMap.class, "serviceId", target.getId(), "removed", null)) {
            if (Boolean.TRUE.equals(exposure.getUpgrade())) return false;
            if (!Boolean.TRUE.equals(exposure.getManaged())) continue;
            Instance instance = objects.loadResource(Instance.class, exposure.getInstanceId());
            if (instance == null || instance.getRemoved() != null || !"running".equals(instance.getState())) return false;
            if (instance.getHealthState() != null && !Set.of("healthy", "updating-healthy").contains(instance.getHealthState())) return false;
            String configName = exposure.getDnsPrefix();
            Map<String, Object> expected = launch;
            if (configName != null && !"io.rancher.service.primary.launch.config".equals(configName)) {
                expected = null;
                for (Object item : secondaries(target)) {
                    Map<String, Object> config = map(item);
                    if (Objects.equals(configName, config.get("name"))) expected = config;
                }
            } else configName = "";
            if (expected == null || !Objects.equals(expected.get("version"), instance.getVersion())) return false;
            counts.put(configName, counts.getOrDefault(configName, 0) + 1);
        }
        Integer scale = DataAccessor.fieldInteger(target, "scale");
        return scale != null && counts.values().stream().allMatch(count -> count >= scale);
    }

    protected List<Map<String, Object>> priorInstances(Service target) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ServiceExposeMap exposure : objects.find(ServiceExposeMap.class, "serviceId", target.getId(), "removed", null)) {
            if (!Boolean.TRUE.equals(exposure.getManaged())) continue;
            Instance instance = objects.loadResource(Instance.class, exposure.getInstanceId());
            if (instance == null || instance.getRemoved() != null || !Objects.equals(instance.getAccountId(), target.getAccountId()))
                throw error(409, "UpgradeTargetBusy");
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("id", instance.getId()); before.put("configName", exposure.getDnsPrefix()); before.put("version", instance.getVersion());
            result.add(before);
        }
        return result;
    }

    protected boolean priorInstancesRemoved(Map<String, Object> plan, Service target) {
        Object captured = plan.get("priorInstances");
        if (!(captured instanceof List<?> prior)) return false;
        for (Object value : prior) {
            Map<String, Object> before = map(value);
            String configName = text(before.get("configName"));
            Map<String, Object> current = DataAccessor.fieldMap(target, "launchConfig");
            if (configName != null && !configName.isEmpty() && !"io.rancher.service.primary.launch.config".equals(configName)) {
                current = null;
                for (Object item : secondaries(target)) {
                    Map<String, Object> config = map(item);
                    if (configName.equals(config.get("name"))) current = config;
                }
            }
            // Unchanged sidekicks need not be replaced by an image-only upgrade.
            if (current != null && Objects.equals(before.get("version"), current.get("version"))) continue;
            long instanceId = number(before.get("id"));
            Instance instance = objects.loadResource(Instance.class, instanceId);
            if (instance != null && (instance.getRemoved() == null
                    || !Set.of("removed", "purging", "purged").contains(String.valueOf(instance.getState())))) return false;
            if (!objects.find(ServiceExposeMap.class, "instanceId", instanceId, "removed", null).isEmpty()
                    || !objects.find(InstanceHostMap.class, "instanceId", instanceId, "removed", null).isEmpty()
                    || !objects.find(Port.class, "instanceId", instanceId, "removed", null).isEmpty()
                    || !objects.find(Nic.class, "instanceId", instanceId, "removed", null).isEmpty()
                    || !objects.find(InstanceLink.class, "instanceId", instanceId, "removed", null).isEmpty()) return false;
            // Named/shared volumes remain intact. Only the old attachment must
            // settle after its asynchronous MountDeactivate job completes.
            for (Mount mount : objects.find(Mount.class, "instanceId", instanceId, "removed", null)) {
                if (!Set.of("inactive", "removed").contains(String.valueOf(mount.getState()))) return false;
            }
        }
        return true;
    }

    protected void observe(Map<String, Object> plan, Service target) {
        String state = text(plan.get("status"));
        if (Set.of("preview", "cancelled", "succeeded", "failed").contains(state)) return;
        List<Map<String, Object>> processes = processes(plan);
        boolean finished = false;
        for (Map<String, Object> process : processes) {
            if (Boolean.TRUE.equals(process.get("ended")) && Set.of("CANCELED", "FAILURE").contains(process.get("result"))) {
                plan.put("status", "failed"); plan.put("messageCode", "UpgradeProcessFailed"); plan.remove("snapshot"); store.save(plan); return;
            }
            finished |= "service.finishupgrade".equals(process.get("name")) && Boolean.TRUE.equals(process.get("ended"))
                    && "SUCCESS".equals(process.get("result"));
        }
        if (finished && "active".equals(target.getState())
                && Objects.equals(plan.get("requestedImage"), DataAccessor.fieldMap(target, "launchConfig").get("imageUuid"))
                && settingsPreserved(plan, target) && instancesReady(target)) {
            if (priorInstancesRemoved(plan, target)) {
                plan.put("status", "succeeded"); plan.put("verifiedResult", true); plan.put("messageCode", "UpgradeCompleted");
                plan.remove("snapshot");
            } else {
                plan.put("status", "running"); plan.put("verifiedResult", false); plan.put("messageCode", "UpgradeCleanupPending");
            }
        } else if (finished && (!settingsPreserved(plan, target)
                || !Objects.equals(plan.get("requestedImage"), DataAccessor.fieldMap(target, "launchConfig").get("imageUuid")))) {
            plan.put("status", "unknown"); plan.put("messageCode", "UpgradeResultMismatch");
        } else if (!processes.isEmpty()) {
            plan.put("status", "running"); plan.put("messageCode", "UpgradeInProgress");
        } else if ("dispatching".equals(state) || clock.millis() - number(plan.get("acceptedAt")) > 60000) {
            plan.put("status", "unknown"); plan.put("messageCode", "UpgradeDispatchUncertain");
        }
        store.save(plan);
    }

    protected boolean settingsPreserved(Map<String, Object> plan, Service target) {
        Map<String, Object> expected = map(map(plan.get("snapshot")).get("launchConfig"));
        Map<String, Object> actual = map(DataAccessor.fieldMap(target, "launchConfig"));
        expected.remove("imageUuid"); expected.remove("version"); actual.remove("imageUuid"); actual.remove("version");
        if (!expected.equals(actual)) return false;
        Object original = map(plan.get("snapshot")).get("secondaryLaunchConfigs");
        List<?> before = original instanceof List<?> ? (List<?>) original : List.of();
        List<?> after = secondaries(target);
        if (before.size() != after.size()) return false;
        Map<String, Object> configs = new LinkedHashMap<>();
        for (Object value : before) { Map<String, Object> config = map(value); config.remove("version"); configs.put(text(config.get("name")), config); }
        for (Object value : after) {
            Map<String, Object> config = map(value); config.remove("version");
            if (!config.equals(configs.remove(text(config.get("name"))))) return false;
        }
        return configs.isEmpty();
    }

    private <T> T locked(String id, Supplier<T> action) {
        return locks.lock(new AbstractBlockingLockDefintion("AI.SERVICE.UPGRADE." + id) { }, new LockCallback<T>() {
            @Override public T doWithLock() { return action.get(); }
        });
    }
    private Map<String, Object> audited(ApiRequest request, String action, String id, Supplier<Map<String, Object>> operation) {
        try { return operation.get(); }
        catch (ClientVisibleException denied) {
            audit(request, action, id, null, false, denied.getCode()); throw denied;
        }
    }
    protected void audit(ApiRequest request, String action, String id, Long target, boolean allowed, String reason) {
        if (auditSinks == null || auditSinks.isEmpty()) throw error(503, "UpgradeAuditUnavailable");
        for (ApiKeyAuditSink sink : auditSinks) sink.recordServiceUpgradeDecision(request, ApiUtils.getPolicy(), action, id, target, allowed, reason);
    }
    protected Object copy(Object value) {
        if (value == null) return null;
        try { return json.readValue(json.writeValueAsString(value), Object.class); }
        catch (IOException e) { throw new IllegalStateException("Cannot snapshot upgrade target", e); }
    }
    protected Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) return result;
        if (!(copy(value) instanceof Map<?, ?> copied)) throw new IllegalStateException("Upgrade snapshot must be an object");
        for (Map.Entry<?, ?> entry : copied.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalStateException("Upgrade snapshot keys must be strings");
            result.put(key, entry.getValue());
        }
        return result;
    }
    private Map<String, Object> input(ApiRequest request, Set<String> allowed) {
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        if (!allowed.containsAll(input.keySet())) throw error(422, "UpgradeInputInvalid");
        return input;
    }
    protected Map<String, Object> output(Map<String, Object> plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("id", "planRef", "accountId", "serviceId", "serviceName", "displayPath", "currentImage", "requestedImage",
                "expiresAt", "confirmationState", "status", "operationId", "verifiedResult", "messageCode")) {
            if (plan.containsKey(key)) result.put(key, plan.get(key));
        }
        if ("dispatching".equals(result.get("status"))) result.put("status", "unknown");
        return result;
    }
    protected String formattedServiceId(Service target) {
        return ApiContext.getContext().getIdFormatter().formatId("service", target.getId()).toString();
    }
    protected String normalizeImage(String image) {
        return ExternalTemplateInstanceFilter.getImageUuid(image.startsWith("docker:") ? image : "docker:" + image, storage);
    }
    private static String text(Object value) { return value == null ? null : value.toString(); }
    private static List<?> secondaries(Service target) {
        Object value = DataAccessor.field(target, "secondaryLaunchConfigs", Object.class);
        return value instanceof List<?> ? (List<?>) value : List.of();
    }
    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
