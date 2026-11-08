package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.ExitReason;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationHook;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Owner outcomes and exact metadata execution attestation; native authorization and receipt reads remain separate owners. */
public class AiResourceChangeProcessObserver implements ProcessAuthorizationHook {
    static final String ATTRIBUTE = "ai.resource.change.execution.owner";
    @Inject AiResourceChangePlanStore store;
    final AiCookieLifecycleWitness cookieWitness = new AiCookieLifecycleWitness(this);
    @Inject jakarta.inject.Provider<io.cattle.platform.servicediscovery.api.dao.ServiceExposeMapDao> restartMaps;
    @Inject ObjectManager objects;
    @Inject ApiKeyTargetResolver targets;
    @Inject io.cattle.platform.json.JsonMapper json;
    @Inject io.cattle.platform.servicediscovery.api.service.ServiceCreateValidation serviceCreateValidation;
    @Inject jakarta.inject.Provider<io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness> deploymentReadiness;

    /** No HTTP field/header can construct this private, request-identity-bound value. */
    private record Dispatch(ApiRequest request, String id) { }

    public <T> T dispatch(ApiRequest request, String id, Supplier<T> operation) {
        if ("loadBalancerService".equals(request.getType()) && "POST".equals(request.getMethod())
                && request.getId() == null && request.getAction() == null) {
            Map<String, Object> approved = store.load(id);
            try {
                Object body = store.json.readValue(store.json.writeValueAsString(request.getRequestObject()));
                if (approved == null || !id.equals(approved.get("operationId")) || !"dispatching".equals(approved.get("status"))
                        || !"loadBalancerService".equals(approved.get("resourceType")) || !"create".equals(approved.get("operation"))
                        || !Objects.equals(approved.get("body"), body)) throw new IllegalStateException("Approved TCP create body unavailable");
            } catch (java.io.IOException invalid) { throw new IllegalStateException("Approved TCP create body unavailable"); }
        }
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null) {
            Map<String, Object> session = store.execution(id);
            if (AiCookieLifecycleWitness.binding(session)) return cookieWitness.dispatch(request, id, operation);
            if (!AiResourceChangePlanStore.sessionLifecycle(session)) return operation.get();
            Policy owner = ApiUtils.getPolicy();
            Map<String, Object> plan = store.load(id);
            if (owner == null || number(session.get("actorId")) != owner.getAuthenticatedAsAccountId()
                    || number(session.get("accountId")) != owner.getAccountId() || !requestMatches(session, request)
                    || plan == null || !id.equals(plan.get("operationId")) || !"dispatching".equals(plan.get("status"))
                    || !(plan.get("parameters") instanceof Map<?, ?> parameters) || !parameters.isEmpty()
                    || !parameters.equals(plan.get("body")) || !parameters.equals(request.getRequestObject()))
                throw new IllegalStateException("Approved lifecycle request unavailable");
            Object previous = request.getAttribute(ATTRIBUTE);
            request.setAttribute(ATTRIBUTE, new Dispatch(request, id));
            try { return registryRemovalDispatch(request, session, operation); }
            finally { request.setAttribute(ATTRIBUTE, previous); }
        }
        Map<String, Object> binding = store.execution(id);
        Policy owner = ApiUtils.getPolicy();
        if (binding == null || !ordinaryIdentity(binding) || !id.equals(binding.get("id")) || !id.equals(request.getAttribute("apiKey.audit.requestId"))
                || !"ALLOW".equals(request.getAttribute("apiKey.audit.decision"))
                || number(binding.get("actorId")) != owner.getAuthenticatedAsAccountId()
                || authorizationAccount(binding) != owner.getAccountId()
                || number(binding.get("credentialId")) != key.credentialId() || number(binding.get("credentialRevision")) != key.revision()
                || !Objects.equals(binding.get("credentialKind"), key.kind()) || !requestMatches(binding, request))
            throw new IllegalStateException("Resource-change execution binding unavailable");
        if ("loadBalancerService".equals(binding.get("resourceType")) && "update_metadata".equals(binding.get("operation"))) {
            Map<String, Object> plan = store.load(id);
            if (plan == null || !(plan.get("parameters") instanceof Map<?, ?> approved) || approved.isEmpty()
                    || !Set.of("name", "description").containsAll(approved.keySet()) || !approved.equals(request.getRequestObject()))
                throw new IllegalStateException("Resource-change metadata request unavailable");
        }
        if (AiResourceChangePlanStore.loadBalancerScale(binding)) {
            Map<String, Object> plan = store.load(id);
            if (plan == null || !id.equals(plan.get("operationId")) || !"dispatching".equals(plan.get("status"))
                    || !"loadBalancerService".equals(plan.get("resourceType")) || !"scale".equals(plan.get("operation"))
                    || !(plan.get("parameters") instanceof Map<?, ?> approved) || !Set.of("scale").equals(approved.keySet())
                    || !(approved.get("scale") instanceof Integer scale) || scale < 1
                    || !approved.equals(plan.get("body")) || !approved.equals(request.getRequestObject()))
                throw new IllegalStateException("Approved resource-change scale request unavailable");
        }
        if (AiProjectMetadataChange.metadataBinding(binding) && !approvedProjectRequest(id, request))
            throw new IllegalStateException("Resource-change metadata request unavailable");
        if (AiHostLifecycleChange.binding(binding)) {
            Map<String, Object> plan = store.load(id);
            if (plan == null || !id.equals(plan.get("operationId")) || !"dispatching".equals(plan.get("status"))
                    || !Objects.equals(plan.get("resourceType"), binding.get("resourceType"))
                    || !Objects.equals(plan.get("operation"), binding.get("operation"))
                    || !(plan.get("parameters") instanceof Map<?, ?> approved) || !approved.isEmpty()
                    || !approved.equals(plan.get("body")) || !approved.equals(request.getRequestObject()))
                throw new IllegalStateException("Approved Host lifecycle request unavailable");
        }
        Object previous = request.getAttribute(ATTRIBUTE);
        request.setAttribute(ATTRIBUTE, new Dispatch(request, id));
        try { return registryRemovalDispatch(request, binding, operation); }
        finally { request.setAttribute(ATTRIBUTE, previous); }
    }

    private <T> T registryRemovalDispatch(ApiRequest request, Map<String, Object> binding, Supplier<T> operation) {
        if (!AiRegistryRemovalChange.binding(binding)) return operation.get();
        return io.cattle.platform.iaas.api.filter.registry.RegistryCredentialReferenceGuard.withRemovalPrecondition(request,
                number(binding.get("rootId")), (String) binding.get("rootUuid"), number(binding.get("accountId")), parent -> {
                    if (!registryConfiguration(binding, parent))
                        throw new io.github.ibuildthecloud.gdapi.exception.ClientVisibleException(409, "ResourceChangeConflict");
                }, operation);
    }

    @Override public Map<String, Object> capture(LaunchConfiguration config) {
        ApiContext context = ApiContext.getContext();
        ApiRequest request = context == null ? null : context.getApiRequest();
        if (request == null || !(request.getAttribute(ATTRIBUTE) instanceof Dispatch scope) || scope.request() != request) return Map.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> binding = store.execution(scope.id());
            if (binding == null || !ordinaryIdentity(binding) || !requestMatches(binding, request)) return Map.of();
            String nativeType = nativeType((String) binding.get("resourceType"));
            Long rootId = binding.get("rootId") instanceof Number id ? id.longValue() : null;
            if (rootId == null) {
                if (!nativeType.equals(config.getResourceType()) || !initialProcess(binding).equals(config.getProcessName())) return Map.of();
                rootId = numeric(config.getResourceId());
            }
            if (rootId == null) return Map.of();
            Object root = objects.loadResource(nativeType, rootId);
            if ("registry".equals(binding.get("resourceType")) && (!Objects.equals(binding.get("rootUuid"), uuid(root))
                    || !registryConfiguration(binding, root))) return Map.of();
            if (AiHostLifecycleChange.binding(binding) && (!AiHostLifecycleChange.queuedRoot(binding, root, config)
                    || !hostConfiguration(binding, root))) return Map.of();
            if (!ownedRoot(binding, root) || !resourceKind(binding, root)
                    || !Objects.equals(ObjectUtils.getId(root), rootId)
                    || !(AiProjectMetadataChange.metadataBinding(binding) ? config.getAccountId() == null
                            : number(config.getAccountId()) == number(binding.get("accountId")))
                    || "create".equals(binding.get("operation")) && !"requested".equals(ObjectUtils.getState(root))) return Map.of();
            Object initial = objects.loadResource(config.getResourceType(), config.getResourceId());
            if (!ownedRoot(binding, initial) || !Objects.equals(String.valueOf(ObjectUtils.getId(initial)), config.getResourceId())
                    || !initialEdge(binding, rootId, config, initial)) return Map.of();
            Map<String, Object> after = new LinkedHashMap<>(binding);
            if (binding.get("rootUuid") != null && !Objects.equals(binding.get("rootUuid"), uuid(root))) return Map.of();
            if ("service".equals(binding.get("resourceType")) && "update_links".equals(binding.get("operation"))
                    && AiAliasServiceChange.nativeId(binding.get("rootStackId"))
                        != AiAliasServiceChange.nativeId(ObjectUtils.getPropertyIgnoreErrors(root, "stackId"))) return Map.of();
            after.put("rootId", rootId); after.put("rootUuid", uuid(root));
            after.put("rootStackId", ObjectUtils.getPropertyIgnoreErrors(root, "stackId"));
            if (Set.of("dnsService", "loadBalancerService").contains(binding.get("resourceType"))
                    || "service".equals(binding.get("resourceType")) && "update_links".equals(binding.get("operation"))) {
                var stack = objects.loadResource(io.cattle.platform.core.model.Stack.class, number(after.get("rootStackId")));
                if (!ownedRow(stack, number(binding.get("accountId")))
                        || binding.get("rootStackUuid") != null && !Objects.equals(binding.get("rootStackUuid"), uuid(stack))) return Map.of();
                after.put("rootStackUuid", uuid(stack));
            }
            if (!binding.containsKey("managed")) {
                List<Map<String, Object>> services = new ArrayList<>();
                after.put("managed", pinManaged(binding, root, services)); after.put("servicePins", services);
            }
            List<Object> initialRows = new ArrayList<>(list(binding.get("initial")));
            Map<String, Object> identity = initialIdentity(config, initial);
            if (initialRows.contains(identity)) return Map.of();
            initialRows.add(identity); after.put("initial", initialRows);
            if (store.attest(scope.id(), binding, after)) return Map.of();
        }
        throw new IllegalStateException("Resource-change execution binding unavailable");
    }

    /** The native insert and exact session witness commit or roll back together, before queue/execute. */
    @Override public void prepareProcess(LaunchConfiguration config) { cookieWitness.prepare(config); }

    @Override public ProcessRecord createProcess(LaunchConfiguration config, Supplier<ProcessRecord> insert) {
        return cookieWitness.create(config, () -> createOriginalProcess(config, insert));
    }

    private ProcessRecord createOriginalProcess(LaunchConfiguration config, Supplier<ProcessRecord> insert) {
        ApiContext context = ApiContext.getContext();
        ApiRequest request = context == null ? null : context.getApiRequest();
        if (request == null || !(request.getAttribute(ATTRIBUTE) instanceof Dispatch scope) || scope.request() != request
                || io.cattle.platform.engine.context.EngineContext.hasParentProcess()) return insert.get();
        Map<String, Object> binding = store.execution(scope.id());
        if (!AiResourceChangePlanStore.sessionLifecycle(binding)) return insert.get();
        Policy owner = ApiUtils.getPolicy();
        Map<String, Object> approved = store.load(scope.id());
        if (owner == null || number(binding.get("actorId")) != owner.getAuthenticatedAsAccountId()
                || number(binding.get("accountId")) != owner.getAccountId() || !requestMatches(binding, request)
                || approved == null || !scope.id().equals(approved.get("operationId")) || !"dispatching".equals(approved.get("status"))
                || !(approved.get("parameters") instanceof Map<?, ?> parameters) || !parameters.isEmpty()
                || !parameters.equals(approved.get("body")) || !parameters.equals(request.getRequestObject())
                || !sessionRoot(binding, config) || !emptyLifecyclePayload(config))
            throw new IllegalStateException("Approved lifecycle process unavailable");
        return store.processCreation(() -> {
            Map<String, Object> before = store.execution(scope.id());
            if (before == null || !before.equals(binding) || before.containsKey("rootProcessId"))
                throw new IllegalStateException("Approved lifecycle process unavailable");
            ProcessRecord record = insert.get();
            if (record == null || record.getId() == null || record.getId() <= 0 || !sessionRoot(before, record)
                    || !Objects.equals(config.getData(), record.getData()) || !emptyLifecyclePayload(record))
                throw new IllegalStateException("Approved lifecycle process unavailable");
            Map<String, Object> after = new LinkedHashMap<>(before); after.put("rootProcessId", record.getId());
            if (!store.attestSessionProcess(scope.id(), before, after))
                throw new IllegalStateException("Approved lifecycle process unavailable");
            return record;
        });
    }

    private boolean sessionRoot(Map<String, Object> binding, LaunchConfiguration config) {
        String type = AiRegistryRemovalChange.binding(binding) ? "storagePool" : (String) binding.get("resourceType");
        Object root = objects.loadResource(type, number(binding.get("rootId")));
        return AiResourceChangePlanStore.sessionLifecycle(binding) && ownedRoot(binding, root) && resourceKind(binding, root)
                && Objects.equals(binding.get("rootUuid"), uuid(root))
                && number(config.getAccountId()) == number(binding.get("accountId"))
                && Objects.equals(type, config.getResourceType())
                && Long.toString(number(binding.get("rootId"))).equals(config.getResourceId())
                && initialProcess(binding).equals(config.getProcessName())
                && list(binding.get("initial")).contains(initialIdentity(config, root))
                && (AiRegistryRemovalChange.binding(binding) ? registryConfiguration(binding, root)
                    : AiHostLifecycleChange.binding(binding) ? AiHostLifecycleChange.queuedRoot(binding, root, config) && hostConfiguration(binding, root)
                    : AiStackUpgradeLifecycleChange.binding(binding)
                        ? root instanceof io.cattle.platform.core.model.Stack stack && "stack".equals(stack.getKind())
                        : root instanceof Service service && "service".equals(service.getKind())
                            && number(binding.get("rootStackId")) == number(service.getStackId()));
    }

    private static boolean emptyLifecyclePayload(LaunchConfiguration config) {
        if (config.getData() == null || config.getData().isEmpty()) return true;
        return config.getData().keySet().equals(Set.of("old")) && config.getData().get("old") instanceof Map<?, ?> old && old.isEmpty();
    }

    /** An absent or inconsistent durable witness is never reconstructed from current resource state. */
    Long sessionProcessId(Map<String, Object> plan, Object target) {
        Map<String, Object> binding = store.execution((String) plan.get("id"));
        if (!AiResourceChangePlanStore.sessionLifecycle(binding) || !Objects.equals(binding.get("id"), plan.get("id"))
                || !Objects.equals(binding.get("resourceType"), plan.get("resourceType"))
                || !Objects.equals(binding.get("operation"), plan.get("operation")) || !Objects.equals(plan.get("id"), plan.get("operationId"))
                || number(binding.get("actorId")) != number(plan.get("actorId"))
                || number(binding.get("accountId")) != number(plan.get("accountId"))
                || number(binding.get("rootId")) != number(ObjectUtils.getId(target)) || !ownedRoot(binding, target)
                || !resourceKind(binding, target) || !Objects.equals(binding.get("rootUuid"), uuid(target))
                || target instanceof Service service && (!"service".equals(service.getKind())
                    || number(binding.get("rootStackId")) != number(service.getStackId()))
                || !(binding.get("rootProcessId") instanceof Number process) || process.longValue() <= 0
                || !store.sessionLifecycleActive((String) plan.get("id"), binding)) return null;
        return process.longValue();
    }

    @Override public void beforeExecution(LaunchConfiguration config, Map<String, Object> metadata) {
        if ("host".equals(metadata.get("targetType")) && "POST".equals(metadata.get("requestMethod"))
                && metadata.get("requestId") instanceof String hostId && hostId.matches("aichange-[0-9a-f-]{36}")
                && !attestsHostLifecycle(config, metadata)) throw new ProcessAuthorizationDeniedException("OwnerPermissionDenied");
        if ("registry".equals(metadata.get("targetType")) && metadata.get("requestId") instanceof String registryId
                && registryId.matches("aichange-[0-9a-f-]{36}") && !attestsRegistryLifecycle(config, metadata))
            throw new ProcessAuthorizationDeniedException("OwnerPermissionDenied");
        if (metadata.get("requestId") instanceof String containerId && containerId.matches("aichange-[0-9a-f-]{36}")
                && "instance".equals(config.getResourceType()) && "instance.start".equals(config.getProcessName())
                && "container".equals(metadata.get("targetType")) && "start".equals(metadata.get("requestAction"))
                && !attestContainerStart(config, metadata))
            throw new IllegalStateException("Resource-change execution binding unavailable");
        if (metadata.get("requestId") instanceof String createId && createId.matches("aichange-[0-9a-f-]{36}")
                && "loadBalancerService".equals(metadata.get("targetType")) && "POST".equals(metadata.get("requestMethod"))
                && metadata.get("requestAction") == null && Boolean.TRUE.equals(metadata.get("requestCollection"))
                && "service".equals(config.getResourceType()) && "service.create".equals(config.getProcessName())
                && !attestsLoadBalancerCreate(config, metadata)) throw new ProcessAuthorizationDeniedException("OwnerPermissionDenied");
        if (metadata.get("requestId") instanceof String id && id.matches("aichange-[0-9a-f-]{36}")
                && "loadBalancerService".equals(metadata.get("targetType")) && "PUT".equals(metadata.get("requestMethod"))
                && "service".equals(config.getResourceType()) && "service.update".equals(config.getProcessName())
                && !(attestsLoadBalancerMetadata(config, metadata) || attestsLoadBalancerScale(config, metadata)))
            throw new ProcessAuthorizationDeniedException("OwnerPermissionDenied");
        if (requiresProjectMetadataAttestation(config, metadata) && !attestsProjectMetadataExecution(config, metadata))
            throw new ProcessAuthorizationDeniedException("OwnerPermissionDenied");
    }

    @Override public void afterExecution(LaunchConfiguration config, Map<String, Object> metadata, ExitReason reason) {
        // CANCELED is a terminating native result. Retry/timeout/changed state are not outcomes.
        if (reason == ExitReason.CANCELED) {
            containerStartCanceled(config, metadata);
            return;
        }
        if (reason != ExitReason.AUTHORIZATION_DENIED || !(config instanceof ProcessRecord record)
                || record.getId() == null || record.getId() <= 0
                || record.getEndTime() == null && !ExitReason.AUTHORIZATION_DENIED.name().equals(
                        record.getData() == null ? null : record.getData().get(ProcessAuthorization.COMPLETION_PENDING_KEY))
                || !ProcessAuthorization.metadata(config).equals(metadata)
                || !(metadata.get("requestId") instanceof String id) || !id.matches("aichange-[0-9a-f-]{36}")) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> binding = store.execution(id);
            if (binding == null || list(binding.get("initial")).isEmpty() || !metadataMatches(binding, metadata)) return;
            if ((AiProjectMetadataChange.metadataBinding(binding) || AiHostLifecycleChange.binding(binding)
                    || "registry".equals(binding.get("resourceType"))
                    || "loadBalancerService".equals(binding.get("resourceType"))
                    && "create".equals(binding.get("operation"))) && binding.get("rootProcessId") instanceof Number processId
                    && processId.longValue() != record.getId()) return;
            if (!provenChild(binding, config, metadata)) return;
            if (store.recordAuthorizationDenied(id, binding) || AiResourceChangePlanStore.AUTHORIZATION_DENIED.equals(store.failure(id))) return;
        }
        // Completion delivery is retried by the existing engine SPI, without
        // re-running the failed process or dispatching another domain request.
        throw new IllegalStateException("Resource-change outcome unavailable");
    }

    private boolean attestContainerStart(LaunchConfiguration config, Map<String, Object> metadata) {
        if (!(config instanceof ProcessRecord record) || record.getId() == null || record.getId() <= 0
                || !ProcessAuthorization.metadata(config).equals(metadata)
                || !(metadata.get("requestId") instanceof String id) || !id.matches("aichange-[0-9a-f-]{36}")) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> binding = store.execution(id);
            if (!containerStartRoot(binding, config, metadata)) return false;
            var persisted = objects.loadResource(io.cattle.platform.core.model.ProcessInstance.class, record.getId());
            if (persisted == null || persisted.getEndTime() != null || !Objects.equals(persisted.getId(), record.getId())
                    || !Objects.equals(persisted.getAccountId(), config.getAccountId())
                    || !Objects.equals(persisted.getProcessName(), config.getProcessName())
                    || !Objects.equals(persisted.getResourceType(), config.getResourceType())
                    || !Objects.equals(persisted.getResourceId(), config.getResourceId()) || persisted.getData() == null
                    || !(persisted.getData().get(ProcessAuthorization.DATA_KEY) instanceof Map<?, ?> raw)
                    || !metadata.equals(raw) || !metadata.equals(ProcessAuthorization.sanitize(raw))) return false;
            if (binding.get("rootProcessId") instanceof Number prior)
                return prior.longValue() == record.getId() && store.containerStartExecutionActive(id, binding);
            Map<String, Object> after = new LinkedHashMap<>(binding); after.put("rootProcessId", record.getId());
            if (store.attestContainerStartProcess(id, binding, after)) return true;
        }
        return false;
    }

    private boolean containerStartRoot(Map<String, Object> binding, LaunchConfiguration config, Map<String, Object> metadata) {
        if (!AiResourceChangePlanStore.containerStart(binding) || !"instance".equals(config.getResourceType())
                || !"instance.start".equals(config.getProcessName())
                || !Long.toString(number(binding.get("rootId"))).equals(config.getResourceId())
                || !metadataMatches(binding, metadata) || !provenChild(binding, config, metadata)) return false;
        Object root = objects.loadResource("instance", number(binding.get("rootId")));
        return root instanceof io.cattle.platform.core.model.Instance instance && "container".equals(instance.getKind())
                && list(binding.get("initial")).contains(initialIdentity(config, root));
    }

    private void containerStartCanceled(LaunchConfiguration config, Map<String, Object> metadata) {
        if (!(config instanceof ProcessRecord record) || record.getId() == null || record.getId() <= 0
                || !ProcessAuthorization.metadata(config).equals(metadata)
                || !(metadata.get("requestId") instanceof String id) || !id.matches("aichange-[0-9a-f-]{36}")) return;
        Object pending = record.getData() == null ? null : record.getData().get(ProcessAuthorization.COMPLETION_PENDING_KEY);
        if (pending != null && !ExitReason.CANCELED.name().equals(pending)) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> binding = store.execution(id);
            if (!containerStartRoot(binding, config, metadata) || !(binding.get("rootProcessId") instanceof Number root)
                    || root.longValue() != record.getId()) return;
            if (store.recordContainerStartFailure(id, binding, record.getId())) return;
            String failure = store.failure(id);
            if (AiResourceChangePlanStore.PROCESS_FAILED.equals(failure) || AiResourceChangePlanStore.AUTHORIZATION_DENIED.equals(failure)) return;
        }
        // The engine's completion-pending delivery retries only this callback, never the start.
        throw new IllegalStateException("Resource-change outcome unavailable");
    }

    private boolean provenChild(Map<String, Object> binding, LaunchConfiguration config, Map<String, Object> metadata) {
        if (!ordinaryIdentity(binding)) return false;
        long accountId = number(binding.get("accountId"));
        Object root = objects.loadResource(nativeType((String) binding.get("resourceType")), number(binding.get("rootId")));
        Object child = objects.loadResource(config.getResourceType(), config.getResourceId());
        if (!ownedRoot(binding, root) || !resourceKind(binding, root)
                || number(binding.get("rootId")) != number(ObjectUtils.getId(root))
                || child == null || !Objects.equals(String.valueOf(ObjectUtils.getId(child)), config.getResourceId())
                || !Objects.equals(uuid(root), binding.get("rootUuid"))
                || !Objects.equals(ObjectUtils.getPropertyIgnoreErrors(root, "stackId"), binding.get("rootStackId") instanceof Number stack
                        ? stack.longValue() : null)) return false;
        if (Set.of("dnsService", "loadBalancerService").contains(binding.get("resourceType"))
                || "service".equals(binding.get("resourceType")) && "update_links".equals(binding.get("operation"))) {
            var stack = objects.loadResource(io.cattle.platform.core.model.Stack.class, number(binding.get("rootStackId")));
            if (!ownedRow(stack, accountId) || !Objects.equals(uuid(stack), binding.get("rootStackUuid"))) return false;
        }
        if (list(binding.get("initial")).contains(initialIdentity(config, child)))
            return ownedRoot(binding, child) && initialEdge(binding, number(binding.get("rootId")), config, child)
                    && (!(child instanceof io.cattle.platform.core.model.ServiceConsumeMap) || ProcessAuthorization.executionBound(config));
        // StackRemove's direct ServiceRemove edge is scheduled by that exact
        // owner. Freeze the original child IDs and recheck their persisted parent.
        if (root instanceof io.cattle.platform.core.model.Stack && child instanceof Service service
                && "DELETE".equals(binding.get("method")) && "service.remove".equals(config.getProcessName())
                && "service".equals(config.getResourceType()) && services(binding).contains(service.getId())
                && Objects.equals(service.getStackId(), ObjectUtils.getId(root)) && ownedRow(service, accountId)) {
            for (Object value : list(binding.get("servicePins"))) if (value instanceof Map<?, ?> pin
                    && number(pin.get("id")) == service.getId() && Objects.equals(pin.get("uuid"), service.getUuid())) return true;
            return false;
        }
        var proof = ProcessAuthorization.verifiedChild(config);
        return proof != null && child != null && provenParent(binding, child, config, proof);
    }

    /** Metadata pins are captured before native handlers can retire their maps. */
    private List<Map<String, Object>> pinManaged(Map<String, Object> binding, Object root, List<Map<String, Object>> servicePins) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (root instanceof io.cattle.platform.core.model.Instance instance) {
            Map<String, Object> pin = new LinkedHashMap<>(); pin.put("instanceId", instance.getId());
            pin.put("instanceUuid", instance.getUuid()); result.add(pin); return result;
        }
        List<Service> services = new ArrayList<>();
        if (root instanceof Service service) services.add(service);
        else for (Long id : services(binding)) {
            Service service = objects.loadResource(Service.class, id);
            if (ownedRow(service, number(binding.get("accountId"))) && Objects.equals(service.getStackId(), ObjectUtils.getId(root))) services.add(service);
        }
        for (Service service : services) {
            servicePins.add(Map.of("id", service.getId(), "uuid", service.getUuid()));
            for (var map : objects.find(io.cattle.platform.core.model.ServiceExposeMap.class, "serviceId", service.getId(), "removed", null)) {
                if (map.getInstanceId() == null) {
                    if ("externalService".equals(service.getKind()) && endpointMap(map, service, number(binding.get("accountId")))
                            && "active".equals(map.getState()) && map.getRemoved() == null) {
                        Map<String, Object> pin = new LinkedHashMap<>();
                        pin.put("serviceId", service.getId()); pin.put("serviceUuid", service.getUuid()); pin.put("stackId", service.getStackId());
                        pin.put("mapId", map.getId()); pin.put("mapUuid", map.getUuid()); pin.put("endpoint", AiExternalServiceChange.endpoint(map));
                        result.add(pin);
                    }
                    continue;
                }
                var instance = objects.loadResource(io.cattle.platform.core.model.Instance.class, map.getInstanceId());
                if (!ownedRow(map, number(binding.get("accountId"))) || !ownedRow(instance, number(binding.get("accountId")))
                        || !Objects.equals(map.getServiceId(), service.getId()) || !Boolean.TRUE.equals(map.getManaged())
                        || Boolean.TRUE.equals(map.getUpgrade()) || !"active".equals(map.getState()) || map.getRemoved() != null) continue;
                Map<String, Object> pin = new LinkedHashMap<>(); pin.put("instanceId", instance.getId()); pin.put("instanceUuid", instance.getUuid());
                pin.put("serviceId", service.getId()); pin.put("serviceUuid", service.getUuid()); pin.put("stackId", service.getStackId());
                pin.put("mapId", map.getId()); pin.put("mapUuid", map.getUuid()); result.add(pin);
            }
        }
        return result;
    }

    private boolean pinnedInstance(Map<String, Object> binding, Long id) {
        if (id == null) return false;
        for (Object value : list(binding.get("managed"))) {
            if (!(value instanceof Map<?, ?> pin) || number(pin.get("instanceId")) != id) continue;
            var instance = objects.loadResource(io.cattle.platform.core.model.Instance.class, id);
            if (!ownedRow(instance, number(binding.get("accountId"))) || !Objects.equals(pin.get("instanceUuid"), instance.getUuid())) return false;
            if (!(pin.get("mapId") instanceof Number)) return Objects.equals(id, binding.get("rootId"))
                    || number(binding.get("rootId")) == id;
            var service = objects.loadResource(Service.class, number(pin.get("serviceId")));
            var map = objects.loadResource(io.cattle.platform.core.model.ServiceExposeMap.class, number(pin.get("mapId")));
            if (!ownedRow(service, number(binding.get("accountId"))) || !ownedRow(map, number(binding.get("accountId")))
                    || !Objects.equals(pin.get("serviceUuid"), service.getUuid()) || !Objects.equals(pin.get("mapUuid"), map.getUuid())
                    || !Objects.equals(service.getStackId(), pin.get("stackId") instanceof Number stack ? stack.longValue() : null)
                    || !Objects.equals(map.getInstanceId(), id) || !Objects.equals(map.getServiceId(), service.getId())
                    || !Boolean.TRUE.equals(map.getManaged()) || Boolean.TRUE.equals(map.getUpgrade())
                    || !Set.of("active", "removing", "removed").contains(String.valueOf(map.getState()))
                    || instance.getServiceId() != null && !Objects.equals(instance.getServiceId(), service.getId())
                    || instance.getStackId() != null && !Objects.equals(instance.getStackId(), service.getStackId())) return false;
            if (!"active".equals(map.getState()) && !Set.of("stopping", "stopped", "removing", "removed")
                    .contains(String.valueOf(instance.getState()))) return false;
            return true;
        }
        return false;
    }

    private boolean provenParent(Map<String, Object> binding, Object child, LaunchConfiguration config, ProcessAuthorization.VerifiedChild proof) {
        Long parent = numeric(proof.parentId()); if (parent == null) return false;
        if (!proof.references().isEmpty() && !(child instanceof io.cattle.platform.core.model.VolumeStoragePoolMap)
                && !(child instanceof io.cattle.platform.core.model.ServiceConsumeMap)) return false;
        if ("service".equals(proof.parentType()) && child instanceof io.cattle.platform.core.model.ServiceExposeMap map)
            return externalEndpoint(binding, map, config, parent);
        if ("service".equals(proof.parentType()) && child instanceof io.cattle.platform.core.model.ServiceConsumeMap map)
            return parent == number(binding.get("rootId")) && "remove".equals(binding.get("operation"))
                    && aliasMap(binding, map, config)
                    // A live denial may occur before references can be bound. The private
                    // plan's original map/target pins still attest this failure only.
                    && (proof.references().isEmpty() || aliasIdentity(binding, map).equals(proof.references()));
        if ("service".equals(proof.parentType()) && child instanceof io.cattle.platform.core.model.Instance instance) {
            boolean service = "service".equals(binding.get("resourceType")) ? parent == number(binding.get("rootId")) : services(binding).contains(parent);
            return service && pinnedInstance(binding, instance.getId()) && ("instance.stop".equals(config.getProcessName())
                    || "instance.remove".equals(config.getProcessName()));
        }
        if ("instance".equals(proof.parentType()) && pinnedInstance(binding, parent)) {
            if (child instanceof io.cattle.platform.core.model.Instance instance) return parent.equals(instance.getId())
                    && Set.of("instance.stop", "instance.remove", "instance.deallocate").contains(config.getProcessName());
            if (!ownedRow(child, number(binding.get("accountId")))
                    || !parent.equals(ObjectUtils.getPropertyIgnoreErrors(child, "instanceId"))) return false;
            return Set.of("nic.remove", "nic.deactivate", "port.remove", "instancelink.remove", "instancehostmap.remove",
                    "mount.deactivate", "mount.remove", "volume.deactivate", "volume.remove", "volume.deallocate").contains(config.getProcessName())
                    && (!(child instanceof io.cattle.platform.core.model.Volume volume) || Integer.valueOf(0).equals(volume.getDeviceNumber()));
        }
        if ("mount".equals(proof.parentType()) && child instanceof io.cattle.platform.core.model.Volume volume
                && "volume.deactivate".equals(config.getProcessName())) {
            var mount = objects.loadResource(io.cattle.platform.core.model.Mount.class, parent);
            return ownedRow(mount, number(binding.get("accountId"))) && ownedRow(volume, number(binding.get("accountId")))
                    && Objects.equals(mount.getVolumeId(), volume.getId()) && pinnedInstance(binding, mount.getInstanceId());
        }
        if ("volume".equals(proof.parentType()) && child instanceof io.cattle.platform.core.model.VolumeStoragePoolMap map
                && Set.of("volumestoragepoolmap.deactivate", "volumestoragepoolmap.remove").contains(config.getProcessName())
                && parent.equals(map.getVolumeId())) {
            var volume = objects.loadResource(io.cattle.platform.core.model.Volume.class, parent);
            if (!ownedRow(volume, number(binding.get("accountId"))) || !Integer.valueOf(0).equals(volume.getDeviceNumber())
                    || !pinnedInstance(binding, volume.getInstanceId()) || uuid(map) == null) return false;
            if (proof.references().isEmpty()) return false;
            var pool = objects.loadResource(io.cattle.platform.core.model.StoragePool.class, map.getStoragePoolId());
            return pool != null && pool.getUuid() != null && proof.references().equals(Map.of("volumeId", volume.getId().toString(),
                    "volumeUuid", volume.getUuid(), "storagePoolId", pool.getId().toString(), "storagePoolUuid", pool.getUuid()));
        }
        return false;
    }

    /** Observation only: no endpoint identity here grants permission to execute it. */
    private boolean externalEndpoint(Map<String, Object> binding, io.cattle.platform.core.model.ServiceExposeMap map,
            LaunchConfiguration config, Long parent) {
        if (!"externalService".equals(binding.get("resourceType")) || parent != number(binding.get("rootId"))
                || !"serviceExposeMap".equals(config.getResourceType())) return false;
        Service service = objects.loadResource(Service.class, parent);
        if (!ownedRow(service, number(binding.get("accountId"))) || !Objects.equals(service.getUuid(), binding.get("rootUuid"))
                || !"externalService".equals(service.getKind()) || !endpointMap(map, service, number(binding.get("accountId")))) return false;
        if ("serviceexposemap.create".equals(config.getProcessName()))
            return map.getRemoved() == null && Set.of("requested", "activating", "active").contains(String.valueOf(map.getState()))
                    && AiExternalServiceChange.matchesTarget(service, map);
        if (!"serviceexposemap.remove".equals(config.getProcessName())
                || !Set.of("active", "removing", "removed").contains(String.valueOf(map.getState()))) return false;
        for (Object value : list(binding.get("managed"))) if (value instanceof Map<?, ?> pin
                && number(pin.get("mapId")) == map.getId() && Objects.equals(pin.get("mapUuid"), map.getUuid())
                && number(pin.get("serviceId")) == service.getId() && Objects.equals(pin.get("serviceUuid"), service.getUuid())
                && number(pin.get("stackId")) == number(service.getStackId())
                && Objects.equals(pin.get("endpoint"), AiExternalServiceChange.endpoint(map))) return true;
        return false;
    }

    private static boolean endpointMap(io.cattle.platform.core.model.ServiceExposeMap map, Service service, long account) {
        return ownedRow(map, account) && Objects.equals(map.getServiceId(), service.getId())
                && map.getInstanceId() == null && Boolean.TRUE.equals(map.getManaged()) && !Boolean.TRUE.equals(map.getUpgrade())
                && AiExternalServiceChange.endpoint(map) != null;
    }

    private static boolean resourceKind(Map<String, Object> binding, Object root) {
        if ("registry".equals(binding.get("resourceType"))) return AiRegistryLifecycleChange.registry(root);
        if ("service".equals(binding.get("resourceType")) && "update_links".equals(binding.get("operation")))
            return root instanceof Service service && "service".equals(service.getKind());
        return !Set.of("externalService", "dnsService", "loadBalancerService").contains(String.valueOf(binding.get("resourceType")))
                || root instanceof Service service && Objects.equals(binding.get("resourceType"), service.getKind());
    }

    private boolean aliasMap(Map<String, Object> binding, io.cattle.platform.core.model.ServiceConsumeMap map, LaunchConfiguration config) {
        if (!("dnsService".equals(binding.get("resourceType"))
                || "service".equals(binding.get("resourceType")) && "update_links".equals(binding.get("operation")))
                || !"serviceConsumeMap".equals(config.getResourceType())
                || !ownedRow(map, number(binding.get("accountId"))) || number(map.getServiceId()) != number(binding.get("rootId"))
                || !AiAliasServiceChange.simpleLink(map)) return false;
        Service consumed = objects.loadResource(Service.class, map.getConsumedServiceId());
        if (!ownedRow(consumed, number(binding.get("accountId"))) || aliasIdentity(binding, map).isEmpty()) return false;
        String references = "serviceconsumemap.create".equals(config.getProcessName()) ? "aliasReferences" : "aliasBeforeReferences";
        boolean pinned = false;
        for (Object value : list(binding.get(references))) if (value instanceof Map<?, ?> pin
                && number(pin.get("id")) == number(consumed.getId()) && Objects.equals(pin.get("uuid"), consumed.getUuid())
                && number(pin.get("accountId")) == number(consumed.getAccountId())
                && number(pin.get("stackId")) == number(consumed.getStackId()) && Objects.equals(pin.get("kind"), consumed.getKind())
                && Objects.equals(pin.get("stackUuid"), aliasIdentity(binding, map).get("aliasConsumedStackUuid"))) pinned = true;
        if (!pinned) return false;
        if ("serviceconsumemap.create".equals(config.getProcessName()) && Set.of("update_targets", "update_links").contains(binding.get("operation"))) {
            if (map.getRemoved() != null || !Set.of("requested", "activating", "active").contains(String.valueOf(map.getState()))) return false;
            for (Object value : list(binding.get("aliasReferences"))) if (value instanceof Map<?, ?> pin
                    && number(pin.get("id")) == number(consumed.getId()) && Objects.equals(pin.get("uuid"), consumed.getUuid())
                    && number(pin.get("stackId")) == number(consumed.getStackId())) return true;
            return false;
        }
        if (!"serviceconsumemap.remove".equals(config.getProcessName())
                || !Set.of("update_targets", "update_links", "remove").contains(String.valueOf(binding.get("operation")))
                || !Set.of("active", "removing", "removed").contains(String.valueOf(map.getState()))) return false;
        for (Object value : list(binding.get("aliasLinks"))) if (value instanceof Map<?, ?> pin
                && number(pin.get("id")) == number(map.getId()) && Objects.equals(pin.get("uuid"), map.getUuid())
                && number(pin.get("serviceId")) == number(map.getServiceId())
                && number(pin.get("consumedServiceId")) == number(consumed.getId())
                && Objects.equals(pin.get("consumedUuid"), consumed.getUuid())) return true;
        return false;
    }

    /** Persisted identity comparison only; these pins never authorize execution. */
    private Map<String, String> aliasIdentity(Map<String, Object> binding, io.cattle.platform.core.model.ServiceConsumeMap map) {
        Service root = objects.loadResource(Service.class, number(binding.get("rootId")));
        Service consumed = objects.loadResource(Service.class, map.getConsumedServiceId());
        if (!ownedRow(root, number(binding.get("accountId"))) || !Objects.equals(binding.get("resourceType"), root.getKind())
                || !ownedRow(consumed, number(binding.get("accountId"))) || Objects.equals(root.getId(), consumed.getId())
                || root.getStackId() == null || consumed.getStackId() == null
                || !Set.of("service", "dnsService", "externalService", "loadBalancerService").contains(String.valueOf(consumed.getKind()))
                || "service".equals(binding.get("resourceType")) && !"service".equals(consumed.getKind())) return Map.of();
        var stack = objects.loadResource(io.cattle.platform.core.model.Stack.class, root.getStackId());
        var consumedStack = objects.loadResource(io.cattle.platform.core.model.Stack.class, consumed.getStackId());
        if (!ownedRow(stack, number(binding.get("accountId"))) || !ownedRow(consumedStack, number(binding.get("accountId")))) return Map.of();
        return Map.of("aliasServiceUuid", root.getUuid(), "aliasMapUuid", map.getUuid(), "aliasConsumedServiceId", consumed.getId().toString(),
                "aliasConsumedServiceUuid", consumed.getUuid(), "aliasStackId", stack.getId().toString(), "aliasStackUuid", stack.getUuid(),
                "aliasConsumedStackId", consumedStack.getId().toString(), "aliasConsumedStackUuid", consumedStack.getUuid());
    }

    private boolean metadataMatches(Map<String, Object> binding, Map<String, Object> metadata) {
        if ("service".equals(binding.get("resourceType")) && "update_links".equals(binding.get("operation"))) {
            for (String field : List.of("principalAccountId", "accountId"))
                if (AiAliasServiceChange.nativeId(metadata.get(field)) < 1) return false;
            if (AiAliasServiceChange.nativeRevision(metadata.get("policyRevision")) < 0) return false;
        }
        try {
            if (targets.parseScopeId("credential", (String) metadata.get("keyId")) != number(binding.get("credentialId"))) return false;
        } catch (RuntimeException invalid) { return false; }
        return Objects.equals(binding.get("id"), metadata.get("requestId"))
                && number(binding.get("actorId")) == number(metadata.get("principalAccountId"))
                && number(binding.get("accountId")) == number(metadata.get("accountId"))
                && number(binding.get("credentialRevision")) == number(metadata.get("policyRevision"))
                && Objects.equals(binding.get("resourceType"), metadata.get("targetType"))
                && Objects.equals(Long.toString(number(binding.get("rootId"))), metadata.get("targetId"))
                && Objects.equals(binding.get("method"), metadata.get("requestMethod"))
                && Objects.equals(binding.get("action"), metadata.get("requestAction"))
                && Objects.equals(binding.get("keyOperation"), metadata.get("operation"))
                && !metadata.containsKey("requestLink") && Boolean.FALSE.equals(metadata.get("preview"))
                && Objects.equals("create".equals(binding.get("operation")), metadata.get("requestCollection"));
    }

    private boolean requestMatches(Map<String, Object> binding, ApiRequest request) {
        return Objects.equals(binding.get("resourceType"), request.getType()) && Objects.equals(binding.get("method"), request.getMethod())
                && Objects.equals(binding.get("action"), request.getAction()) && request.getLink() == null
                && ("create".equals(binding.get("operation")) ? request.getId() == null
                        : Objects.equals(Long.toString(number(binding.get("rootId"))), request.getId()));
    }

    private boolean initialEdge(Map<String, Object> binding, long rootId, LaunchConfiguration config, Object initial) {
        if (("dnsService".equals(binding.get("resourceType")) || "service".equals(binding.get("resourceType"))
                && "update_links".equals(binding.get("operation"))) && "POST".equals(binding.get("method"))
                && "setservicelinks".equals(binding.get("action")) && initial instanceof io.cattle.platform.core.model.ServiceConsumeMap map)
            return aliasMap(binding, map, config);
        if (nativeType((String) binding.get("resourceType")).equals(config.getResourceType())
                && Long.toString(rootId).equals(config.getResourceId()) && initialProcess(binding).equals(config.getProcessName())) return true;
        if (!"stack".equals(binding.get("resourceType")) || !(initial instanceof Service service)
                || !"service".equals(config.getResourceType()) || !Objects.equals(service.getStackId(), rootId)
                || !services(binding).contains(service.getId())) return false;
        return "activateservices".equals(binding.get("action")) && "service.activate".equals(config.getProcessName())
                || "deactivateservices".equals(binding.get("action")) && "service.deactivate".equals(config.getProcessName());
    }

    private static String initialProcess(Map<String, Object> binding) {
        String suffix = switch ((String) binding.get("method")) {
        case "PUT" -> "update";
        case "DELETE" -> "remove";
        default -> binding.get("action") == null ? "create" : ((String) binding.get("action")).toLowerCase(java.util.Locale.ROOT);
        };
        return ("registry".equals(binding.get("resourceType")) ? "storagepool" : nativeType((String) binding.get("resourceType"))) + "." + suffix;
    }

    private static String nativeType(String type) {
        return "registry".equals(type) ? "storagePool" : "project".equals(type) ? "account" : "container".equals(type) ? "instance"
                : Set.of("externalService", "dnsService", "loadBalancerService").contains(type) ? "service" : type;
    }
    private boolean registryConfiguration(Map<String, Object> binding, Object root) {
        if (!AiRegistryLifecycleChange.registry(root) || !(binding.get("registryConfiguration") instanceof Map<?, ?> expected)) return false;
        try {
            Map<String, Object> current = json.readValue(json.writeValueAsString(
                    AiRegistryLifecycleChange.snapshot((io.cattle.platform.core.model.StoragePool) root)));
            return expected.equals(AiRegistryLifecycleChange.configuration(current))
                    && (!AiRegistryRemovalChange.binding(binding) || AiRegistryRemovalChange.captured(binding.get(AiRegistryRemovalChange.CREDENTIALS))
                        .equals(store.registryRemovalCredentials(binding)));
        } catch (io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid) { return false; }
        catch (java.io.IOException unavailable) { throw new IllegalStateException("Resource-change execution binding unavailable", unavailable); }
    }

    private boolean hostConfiguration(Map<String, Object> binding, Object root) {
        if (!AiHostLifecycleChange.binding(binding) || !AiHostLifecycleChange.host(root)
                || !(binding.get("hostConfiguration") instanceof Map<?, ?> expected)) return false;
        var host = (io.cattle.platform.core.model.Host) root;
        var project = objects.loadResource(io.cattle.platform.core.model.Account.class, host.getAccountId());
        return expected.equals(AiHostLifecycleChange.configuration(AiHostLifecycleChange.snapshot(host, project, json)));
    }

    private boolean attestsHostLifecycle(LaunchConfiguration config, Map<String, Object> metadata) {
        if (!(config instanceof ProcessRecord record) || record.getId() == null || record.getId() <= 0
                || !metadata.equals(ProcessAuthorization.metadata(config)) || config.getData() == null) return false;
        Set<String> reserved = Set.of("old", ProcessAuthorization.DATA_KEY, ProcessAuthorization.CAPTURED_EXECUTION_KEY,
                ProcessAuthorization.VERIFIED_CHILD_KEY, ProcessAuthorization.COMPLETION_PENDING_KEY);
        if (!reserved.containsAll(config.getData().keySet())) return false;
        if (config.getData().containsKey("old") && (!(config.getData().get("old") instanceof Map<?, ?> old) || !old.isEmpty())) return false;
        String id = (String) metadata.get("requestId");
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> binding = store.execution(id);
            Object root = binding == null ? null : objects.loadResource("host", number(binding.get("rootId")));
            if (binding == null || !metadataMatches(binding, metadata) || !provenChild(binding, config, metadata)
                    || !AiHostLifecycleChange.queuedRoot(binding, root, config) || !hostConfiguration(binding, root)) return false;
            var persisted = objects.loadResource(io.cattle.platform.core.model.ProcessInstance.class, record.getId());
            if (persisted == null || persisted.getEndTime() != null || !Objects.equals(persisted.getId(), record.getId())
                    || !Objects.equals(persisted.getAccountId(), config.getAccountId()) || !Objects.equals(persisted.getData(), config.getData())
                    || !Objects.equals(persisted.getProcessName(), config.getProcessName())
                    || !Objects.equals(persisted.getResourceType(), config.getResourceType())
                    || !Objects.equals(persisted.getResourceId(), config.getResourceId())) return false;
            if (binding.get("rootProcessId") instanceof Number processId)
                return processId.longValue() == record.getId() && store.hostLifecycleExecutionActive(id, binding);
            Map<String, Object> after = new LinkedHashMap<>(binding); after.put("rootProcessId", record.getId());
            if (store.attestHostLifecycleProcess(id, binding, after)) return true;
        }
        return false;
    }
    private boolean attestsRegistryLifecycle(LaunchConfiguration config, Map<String, Object> metadata) {
        if (!(config instanceof ProcessRecord record) || record.getId() == null || record.getId() <= 0
                || !metadata.equals(ProcessAuthorization.metadata(config)) || config.getData() == null) return false;
        Set<String> reserved = Set.of("old", ProcessAuthorization.DATA_KEY, ProcessAuthorization.CAPTURED_EXECUTION_KEY,
                ProcessAuthorization.VERIFIED_CHILD_KEY, ProcessAuthorization.COMPLETION_PENDING_KEY);
        if (!reserved.containsAll(config.getData().keySet())) return false;
        String id = (String) metadata.get("requestId");
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> binding = store.execution(id);
            if (binding == null || !"registry".equals(binding.get("resourceType")) || !metadataMatches(binding, metadata)
                    || !provenChild(binding, config, metadata)
                    || !registryConfiguration(binding, objects.loadResource("storagePool", number(binding.get("rootId"))))) return false;
            var persisted = objects.loadResource(io.cattle.platform.core.model.ProcessInstance.class, record.getId());
            if (persisted == null || persisted.getEndTime() != null || !Objects.equals(persisted.getId(), record.getId())
                    || !Objects.equals(persisted.getAccountId(), config.getAccountId()) || !Objects.equals(persisted.getData(), config.getData())
                    || !Objects.equals(persisted.getProcessName(), config.getProcessName())
                    || !Objects.equals(persisted.getResourceType(), config.getResourceType())
                    || !Objects.equals(persisted.getResourceId(), config.getResourceId())) return false;
            if (binding.get("rootProcessId") instanceof Number processId)
                return processId.longValue() == record.getId() && store.registryLifecycleExecutionActive(id, binding);
            Map<String, Object> after = new LinkedHashMap<>(binding); after.put("rootProcessId", record.getId());
            if (store.attestRegistryLifecycleProcess(id, binding, after)) return true;
        }
        return false;
    }
    String registryRemovalOutcome(String id) {
        Map<String, Object> binding = store.execution(id);
        if (!AiRegistryRemovalChange.binding(binding) || !id.equals(binding.get("id"))
                || !(binding.get("rootProcessId") instanceof Number processId) || processId.longValue() <= 0) return "unknown";
        var record = objects.loadResource(io.cattle.platform.core.model.ProcessInstance.class, processId.longValue());
        if (record == null || !Objects.equals(record.getId(), processId.longValue())
                || !"storagepool.remove".equals(record.getProcessName()) || !"storagePool".equals(record.getResourceType())
                || !Long.toString(number(binding.get("rootId"))).equals(record.getResourceId())
                || !Objects.equals(record.getAccountId(), number(binding.get("accountId")))) return "unknown";
        // Cookie correlation is the exact native insert/claim witness, never a Key authorization surrogate.
        if (AiResourceChangePlanStore.sessionLifecycle(binding)) {
            if (!store.sessionLifecycleActive(id, binding)
                    || record.getData() != null && record.getData().containsKey(ProcessAuthorization.DATA_KEY)) return "unknown";
            return record.getEndTime() == null ? "running" : "SUCCESS".equals(record.getResult()) ? "succeeded" : "failed";
        }
        if (record.getData() == null || !(record.getData().get(ProcessAuthorization.DATA_KEY) instanceof Map<?, ?> raw)) return "unknown";
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) return "unknown";
            metadata.put(key, entry.getValue());
        }
        if (!metadataMatches(binding, metadata)) return "unknown";
        return record.getEndTime() == null ? "running" : "SUCCESS".equals(record.getResult()) ? "succeeded" : "failed";
    }

    /** Scale shares the native PUT process with metadata, but never its approved field set. */
    private boolean attestsLoadBalancerScale(LaunchConfiguration config, Map<String, Object> metadata) {
        if (!(metadata.get("requestId") instanceof String id) || !id.matches("aichange-[0-9a-f-]{36}")
                || !(config instanceof ProcessRecord record) || record.getId() == null || record.getId() <= 0
                || !ProcessAuthorization.metadata(config).equals(metadata)) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> binding = store.execution(id), plan = store.load(id);
            if (!AiResourceChangePlanStore.loadBalancerScale(binding) || plan == null || !id.equals(plan.get("operationId"))
                    || !"loadBalancerService".equals(plan.get("resourceType")) || !"scale".equals(plan.get("operation"))
                    || !(binding.get("rootUuid") instanceof String rootUuid) || rootUuid.isBlank()
                    || !(binding.get("rootStackUuid") instanceof String stackUuid) || stackUuid.isBlank()
                    || number(binding.get("rootId")) <= 0 || number(binding.get("rootStackId")) <= 0
                    || !metadataMatches(binding, metadata) || !provenChild(binding, config, metadata)
                    || !(plan.get("parameters") instanceof Map<?, ?> approved) || !Set.of("scale").equals(approved.keySet())
                    || !(approved.get("scale") instanceof Integer scale) || scale < 1
                    || !approved.equals(plan.get("body")) || config.getData() == null
                    || !Objects.equals(scale, config.getData().get("scale"))
                    || !(plan.get("snapshot") instanceof Map<?, ?> before)
                    || !(before.get("loadBalancerConfiguration") instanceof Map<?, ?> configuration)
                    || !(plan.get("referenceSnapshot") instanceof Map<?, ?> parent)
                    || !Objects.equals(before.get("uuid"), binding.get("rootUuid"))
                    || number(before.get("id")) != number(binding.get("rootId"))
                    || number(before.get("accountId")) != number(binding.get("accountId"))
                    || !"loadBalancerService".equals(before.get("kind"))
                    || number(before.get("stackId")) != number(binding.get("rootStackId"))
                    || number(parent.get("id")) != number(binding.get("rootStackId"))
                    || !Objects.equals(parent.get("uuid"), binding.get("rootStackUuid"))
                    || number(parent.get("accountId")) != number(binding.get("accountId"))
                    || !(config.getData().get("old") instanceof Map<?, ?> old) || !Set.of("scale").equals(old.keySet())
                    || !configuration.containsKey("scale") || !Objects.equals(configuration.get("scale"), old.get("scale"))) return false;
            Set<String> reserved = Set.of("old", ProcessAuthorization.DATA_KEY, ProcessAuthorization.CAPTURED_EXECUTION_KEY,
                    ProcessAuthorization.VERIFIED_CHILD_KEY, ProcessAuthorization.COMPLETION_PENDING_KEY);
            for (String field : config.getData().keySet()) if (!"scale".equals(field) && !reserved.contains(field)) return false;
            Object root = objects.loadResource("service", number(binding.get("rootId")));
            if (!(root instanceof Service service) || !Set.of("active", "inactive").contains(service.getState() == null ? "" : service.getState())
                    || !Objects.equals(scale, io.cattle.platform.object.util.DataAccessor.fieldInteger(service, "scale"))
                    || deploymentReadiness == null || !deploymentReadiness.get().supportsManualScale(service)) return false;
            var persisted = objects.loadResource(io.cattle.platform.core.model.ProcessInstance.class, record.getId());
            if (persisted == null || persisted.getEndTime() != null || !Objects.equals(persisted.getId(), record.getId())
                    || !Objects.equals(persisted.getAccountId(), config.getAccountId())
                    || !Objects.equals(persisted.getProcessName(), config.getProcessName())
                    || !Objects.equals(persisted.getResourceType(), config.getResourceType())
                    || !Objects.equals(persisted.getResourceId(), config.getResourceId()) || !Objects.equals(persisted.getData(), config.getData())
                    || !(persisted.getData().get(ProcessAuthorization.DATA_KEY) instanceof Map<?, ?> raw)
                    || !metadata.equals(raw) || !metadata.equals(ProcessAuthorization.sanitize(raw))) return false;
            if (binding.get("rootProcessId") instanceof Number prior)
                return prior.longValue() == record.getId() && store.loadBalancerScaleExecutionActive(id, binding);
            Map<String, Object> after = new LinkedHashMap<>(binding); after.put("rootProcessId", record.getId());
            if (store.attestLoadBalancerScaleProcess(id, binding, after)) return true;
        }
        return false;
    }

    private boolean attestsLoadBalancerMetadata(LaunchConfiguration config, Map<String, Object> metadata) {
        if (!(metadata.get("requestId") instanceof String id) || !id.matches("aichange-[0-9a-f-]{36}")
                || !(config instanceof ProcessRecord record) || record.getId() == null || record.getId() <= 0
                || !ProcessAuthorization.metadata(config).equals(metadata)) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> binding = store.execution(id);
            Map<String, Object> plan = store.load(id);
            if (binding == null || plan == null || !id.equals(plan.get("operationId"))
                    || !"loadBalancerService".equals(binding.get("resourceType")) || !"update_metadata".equals(binding.get("operation"))
                    || !"PUT".equals(binding.get("method")) || !metadataMatches(binding, metadata) || !provenChild(binding, config, metadata)
                    || !(plan.get("parameters") instanceof Map<?, ?> approved) || approved.isEmpty()
                    || !Set.of("name", "description").containsAll(approved.keySet()) || config.getData() == null) return false;
            Set<String> reserved = Set.of("old", ProcessAuthorization.DATA_KEY, ProcessAuthorization.CAPTURED_EXECUTION_KEY,
                    ProcessAuthorization.VERIFIED_CHILD_KEY, ProcessAuthorization.COMPLETION_PENDING_KEY);
            for (var entry : config.getData().entrySet()) {
                if (!reserved.contains(entry.getKey()) && (!approved.containsKey(entry.getKey())
                        || !Objects.equals(approved.get(entry.getKey()), entry.getValue()))) return false;
            }
            var root = objects.loadResource("service", number(binding.get("rootId")));
            var stack = objects.loadResource(io.cattle.platform.core.model.Stack.class, number(binding.get("rootStackId")));
            if (root == null || ObjectUtils.getRemoved(root) != null || stack == null || stack.getRemoved() != null) return false;
            var persisted = objects.loadResource(io.cattle.platform.core.model.ProcessInstance.class, record.getId());
            if (persisted == null || persisted.getEndTime() != null || !Objects.equals(persisted.getId(), record.getId())
                    || !Objects.equals(persisted.getAccountId(), config.getAccountId())
                    || !Objects.equals(persisted.getProcessName(), config.getProcessName())
                    || !Objects.equals(persisted.getResourceType(), config.getResourceType())
                    || !Objects.equals(persisted.getResourceId(), config.getResourceId()) || !Objects.equals(persisted.getData(), config.getData())
                    || !(persisted.getData().get(ProcessAuthorization.DATA_KEY) instanceof Map<?, ?> raw)
                    || !metadata.equals(raw) || !metadata.equals(ProcessAuthorization.sanitize(raw))) return false;
            if (binding.get("rootProcessId") instanceof Number prior)
                return prior.longValue() == record.getId() && store.loadBalancerMetadataExecutionActive(id, binding);
            Map<String, Object> after = new LinkedHashMap<>(binding); after.put("rootProcessId", record.getId());
            if (store.attestLoadBalancerMetadataProcess(id, binding, after)) return true;
        }
        return false;
    }

    private boolean attestsLoadBalancerCreate(LaunchConfiguration config, Map<String, Object> metadata) {
        if (!(config instanceof ProcessRecord record) || record.getId() == null || record.getId() <= 0
                || !(metadata.get("requestId") instanceof String id) || !ProcessAuthorization.metadata(config).equals(metadata)) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> binding = store.execution(id), plan = store.load(id);
            if (binding == null || plan == null || !"loadBalancerService".equals(binding.get("resourceType"))
                    || !"create".equals(binding.get("operation")) || !id.equals(plan.get("operationId"))
                    || !metadataMatches(binding, metadata) || !provenChild(binding, config, metadata)) return false;
            Object root = objects.loadResource("service", number(binding.get("rootId")));
            if (!(root instanceof Service service) || service.getRemoved() != null || list(binding.get("loadBalancerTargets")).isEmpty()
                    || !(binding.get("loadBalancerParent") instanceof Map<?, ?> parent)
                    || number(parent.get("id")) != number(binding.get("rootStackId"))
                    || !Objects.equals(parent.get("uuid"), binding.get("rootStackUuid"))) return false;
            if (!(plan.get("body") instanceof Map<?, ?> rawBody) || !(rawBody.get("launchConfig") instanceof Map<?, ?> launch)
                    || !(launch.get("imageUuid") instanceof String image)) return false;
            Map<String, Object> approvedBody = new LinkedHashMap<>();
            for (var entry : rawBody.entrySet()) if (entry.getKey() instanceof String name) approvedBody.put(name, entry.getValue());
            if (!AiLoadBalancerCreateChange.configurationMatches(service, approvedBody, number(parent.get("id")),
                    serviceCreateValidation.imageUuid(image), target -> targets.parseScopeId("service", target))) return false;
            for (Object item : list(binding.get("loadBalancerTargets"))) {
                if (!(item instanceof Map<?, ?> pin)) return false;
                Service target = objects.loadResource(Service.class, number(pin.get("id")));
                var stack = objects.loadResource(io.cattle.platform.core.model.Stack.class, number(pin.get("stackId")));
                if (target == null || target.getRemoved() != null || stack == null || stack.getRemoved() != null
                        || !ownedRow(target, number(binding.get("accountId"))) || !ownedRow(stack, number(binding.get("accountId")))
                        || !Objects.equals(pin.get("uuid"), target.getUuid()) || !Objects.equals(pin.get("kind"), target.getKind())
                        || number(pin.get("stackId")) != number(target.getStackId()) || !Objects.equals(pin.get("stackUuid"), stack.getUuid())) return false;
            }
            var persisted = objects.loadResource(io.cattle.platform.core.model.ProcessInstance.class, record.getId());
            if (persisted == null || persisted.getEndTime() != null || !Objects.equals(persisted.getId(), record.getId())
                    || !Objects.equals(persisted.getAccountId(), config.getAccountId())
                    || !Objects.equals(persisted.getProcessName(), config.getProcessName())
                    || !Objects.equals(persisted.getResourceType(), config.getResourceType())
                    || !Objects.equals(persisted.getResourceId(), config.getResourceId()) || !Objects.equals(persisted.getData(), config.getData())
                    || !(persisted.getData().get(ProcessAuthorization.DATA_KEY) instanceof Map<?, ?> raw)
                    || !metadata.equals(raw) || !metadata.equals(ProcessAuthorization.sanitize(raw))) return false;
            if (binding.get("rootProcessId") instanceof Number prior)
                return prior.longValue() == record.getId() && store.loadBalancerCreateExecutionActive(id, binding);
            Map<String, Object> after = new LinkedHashMap<>(binding); after.put("rootProcessId", record.getId());
            if (store.attestLoadBalancerCreateProcess(id, binding, after)) return true;
        }
        return false;
    }

    private boolean requiresProjectMetadataAttestation(LaunchConfiguration config, Map<String, Object> metadata) {
        if (!(metadata.get("requestId") instanceof String id) || !id.matches("aichange-[0-9a-f-]{36}")) return false;
        // Shape only selects mandatory attestation; it can never supply authority when the private binding is absent.
        if ("project".equals(metadata.get("targetType"))
                || "account".equals(config.getResourceType()) && "account.update".equals(config.getProcessName())) return true;
        Map<String, Object> binding = store.execution(id);
        return binding != null && "project".equals(binding.get("resourceType"));
    }

    /** A durable, already claimed exact Account.update chain, never a client-provided AI flag. */
    public boolean attestsProjectMetadataExecution(LaunchConfiguration config, Map<String, Object> metadata) {
        if (!(config instanceof ProcessRecord record) || record.getId() == null || record.getId() <= 0
                || !(metadata.get("requestId") instanceof String id) || !id.matches("aichange-[0-9a-f-]{36}")) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> binding = store.execution(id);
            if (!AiProjectMetadataChange.metadataBinding(binding) || config.getAccountId() != null
                    || !"account".equals(config.getResourceType()) || !"account.update".equals(config.getProcessName())
                    || !metadataMatches(binding, metadata) || !approvedProjectUpdate(id, binding, config, metadata)
                    || !provenChild(binding, config, metadata)) return false;
            var persisted = objects.loadResource(io.cattle.platform.core.model.ProcessInstance.class, record.getId());
            if (persisted == null || persisted.getEndTime() != null || !Objects.equals(persisted.getId(), record.getId())
                    || persisted.getAccountId() != null
                    || !Objects.equals(persisted.getProcessName(), config.getProcessName())
                    || !Objects.equals(persisted.getResourceType(), config.getResourceType())
                    || !Objects.equals(persisted.getResourceId(), config.getResourceId()) || persisted.getData() == null
                    || !(persisted.getData().get(ProcessAuthorization.DATA_KEY) instanceof Map<?, ?> raw)
                    || !metadata.equals(raw) || !metadata.equals(ProcessAuthorization.sanitize(raw))) return false;
            if (binding.get("rootProcessId") instanceof Number prior)
                return prior.longValue() == record.getId() && store.projectExecutionActive(id, binding);
            Map<String, Object> after = new LinkedHashMap<>(binding); after.put("rootProcessId", record.getId());
            if (store.attestProjectProcess(id, binding, after)) return true;
        }
        return false;
    }
    private boolean approvedProjectRequest(String id, ApiRequest request) {
        Map<String, Object> plan = store.load(id);
        if (plan == null || !(plan.get("parameters") instanceof Map<?, ?> approved)
                || !(request.getRequestObject() instanceof Map<?, ?> body)) return false;
        Map<Object, Object> actual = new LinkedHashMap<>(body);
        if (actual.containsKey("type") && !"project".equals(actual.remove("type"))) return false;
        return actual.equals(approved);
    }

    private boolean approvedProjectUpdate(String id, Map<String, Object> binding, LaunchConfiguration config, Map<String, Object> metadata) {
        if (!ProcessAuthorization.executionBound(config) || !ProcessAuthorization.projectUpdateExecution(config, metadata)
                || !Objects.equals(binding.get("rootUuid"), metadata.get("projectUpdateUuid"))) return false;
        List<?> actual = (List<?>) metadata.get("projectUpdateFields");
        if (!((List<?>) binding.get("metadataFields")).containsAll(actual)) return false;
        Map<String, Object> plan = store.load(id);
        if (plan == null || !(plan.get("parameters") instanceof Map<?, ?> approved)) return false;
        // Native scheduling omits unchanged parameters. Every actual change still equals its privately approved value.
        return actual.stream().allMatch(field -> approved.containsKey(field) && Objects.equals(approved.get(field), config.getData().get(field)));
    }

    private static long authorizationAccount(Map<String, Object> binding) {
        return number(binding.get(AiProjectMetadataChange.metadataBinding(binding) ? "actorId" : "accountId"));
    }
    private static boolean ownedRoot(Map<String, Object> binding, Object row) {
        if (!AiProjectMetadataChange.metadataBinding(binding)) return ownedRow(row, number(binding.get("accountId")));
        try { return AiProjectMetadataChange.selectedReadable(row, number(binding.get("accountId"))) != null; }
        catch (io.github.ibuildthecloud.gdapi.exception.ClientVisibleException denied) { return false; }
    }
    private static boolean ownedRow(Object row, long account) {
        if (row == null || !Objects.equals(ObjectUtils.getAccountId(row), account) || uuid(row) == null) return false;
        Object state = ObjectUtils.getState(row); Object removed = ObjectUtils.getRemoved(row);
        return !Set.of("purging", "purged").contains(String.valueOf(state)) && (removed != null) == "removed".equals(state);
    }
    private static String uuid(Object row) {
        Object value = ObjectUtils.getPropertyIgnoreErrors(row, "uuid"); return value instanceof String text && !text.isBlank() ? text : null;
    }
    private static Map<String, Object> initialIdentity(LaunchConfiguration config, Object row) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("type", config.getResourceType()); result.put("id", config.getResourceId());
        result.put("process", config.getProcessName()); result.put("uuid", uuid(row));
        if (row instanceof io.cattle.platform.core.model.ServiceConsumeMap map) {
            result.put("serviceId", Objects.toString(map.getServiceId(), ""));
            result.put("consumedServiceId", Objects.toString(map.getConsumedServiceId(), ""));
        }
        return result;
    }
    private static List<?> list(Object value) { return value instanceof List<?> values ? values : List.of(); }
    private static List<Long> services(Map<String, Object> binding) {
        return list(binding.get("services")).stream().map(AiResourceChangeProcessObserver::number).toList();
    }
    private static boolean ordinaryIdentity(Map<String, Object> binding) {
        if (!("service".equals(binding.get("resourceType")) && "update_links".equals(binding.get("operation")))) return true;
        for (String field : List.of("rootId", "rootStackId", "actorId", "accountId", "credentialId"))
            if (AiAliasServiceChange.nativeId(binding.get(field)) < 1) return false;
        if (AiAliasServiceChange.nativeRevision(binding.get("credentialRevision")) < 0) return false;
        for (String field : List.of("aliasReferences", "aliasBeforeReferences", "aliasLinks")) {
            if (!(binding.get(field) instanceof List<?> pins) || pins.size() > AiAliasServiceChange.MAX_TARGETS) return false;
            for (Object value : pins) {
                if (!(value instanceof Map<?, ?> pin)) return false;
                for (String id : field.equals("aliasLinks") ? List.of("id", "accountId", "serviceId", "consumedServiceId")
                        : List.of("id", "accountId", "stackId"))
                    if (AiAliasServiceChange.nativeId(pin.get(id)) < 1) return false;
            }
        }
        return true;
    }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : -1; }
    private static Long numeric(String value) {
        try { return value != null && value.matches("[1-9][0-9]*") ? Long.valueOf(value) : null; }
        catch (NumberFormatException invalid) { return null; }
    }
}
