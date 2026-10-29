package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.ExitReason;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationHook;
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

/** Owner outcome projection only. This observer never authorizes a process or a receipt read. */
public class AiResourceChangeProcessObserver implements ProcessAuthorizationHook {
    static final String ATTRIBUTE = "ai.resource.change.execution.owner";
    @Inject AiResourceChangePlanStore store;
    @Inject ObjectManager objects;
    @Inject ApiKeyTargetResolver targets;

    /** No HTTP field/header can construct this private, request-identity-bound value. */
    private record Dispatch(ApiRequest request, String id) { }

    public <T> T dispatch(ApiRequest request, String id, Supplier<T> operation) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null) return operation.get();
        Map<String, Object> binding = store.execution(id);
        Policy owner = ApiUtils.getPolicy();
        if (binding == null || !id.equals(binding.get("id")) || !id.equals(request.getAttribute("apiKey.audit.requestId"))
                || !"ALLOW".equals(request.getAttribute("apiKey.audit.decision"))
                || number(binding.get("actorId")) != owner.getAuthenticatedAsAccountId()
                || number(binding.get("accountId")) != owner.getAccountId()
                || number(binding.get("credentialId")) != key.credentialId() || number(binding.get("credentialRevision")) != key.revision()
                || !Objects.equals(binding.get("credentialKind"), key.kind()) || !requestMatches(binding, request))
            throw new IllegalStateException("Resource-change execution binding unavailable");
        Object previous = request.getAttribute(ATTRIBUTE);
        request.setAttribute(ATTRIBUTE, new Dispatch(request, id));
        try { return operation.get(); }
        finally { request.setAttribute(ATTRIBUTE, previous); }
    }

    @Override public Map<String, Object> capture(LaunchConfiguration config) {
        ApiContext context = ApiContext.getContext();
        ApiRequest request = context == null ? null : context.getApiRequest();
        if (request == null || !(request.getAttribute(ATTRIBUTE) instanceof Dispatch scope) || scope.request() != request) return Map.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> binding = store.execution(scope.id());
            if (binding == null || !requestMatches(binding, request)) return Map.of();
            String nativeType = nativeType((String) binding.get("resourceType"));
            Long rootId = binding.get("rootId") instanceof Number id ? id.longValue() : null;
            if (rootId == null) {
                if (!nativeType.equals(config.getResourceType()) || !initialProcess(binding).equals(config.getProcessName())) return Map.of();
                rootId = numeric(config.getResourceId());
            }
            if (rootId == null) return Map.of();
            Object root = objects.loadResource(nativeType, rootId);
            if (!ownedRow(root, number(binding.get("accountId"))) || !Objects.equals(ObjectUtils.getId(root), rootId)
                    || number(config.getAccountId()) != number(binding.get("accountId"))
                    || "create".equals(binding.get("operation")) && !"requested".equals(ObjectUtils.getState(root))) return Map.of();
            Object initial = objects.loadResource(config.getResourceType(), config.getResourceId());
            if (!ownedRow(initial, number(binding.get("accountId"))) || !Objects.equals(String.valueOf(ObjectUtils.getId(initial)), config.getResourceId())
                    || !initialEdge(binding, rootId, config, initial)) return Map.of();
            Map<String, Object> after = new LinkedHashMap<>(binding);
            if (binding.get("rootUuid") != null && !Objects.equals(binding.get("rootUuid"), uuid(root))) return Map.of();
            after.put("rootId", rootId); after.put("rootUuid", uuid(root));
            after.put("rootStackId", ObjectUtils.getPropertyIgnoreErrors(root, "stackId"));
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

    @Override public void afterExecution(LaunchConfiguration config, Map<String, Object> metadata, ExitReason reason) {
        if (reason != ExitReason.AUTHORIZATION_DENIED || !(config instanceof ProcessRecord record)
                || record.getId() == null || record.getId() <= 0
                || record.getEndTime() == null && !ExitReason.AUTHORIZATION_DENIED.name().equals(
                        record.getData() == null ? null : record.getData().get(ProcessAuthorization.COMPLETION_PENDING_KEY))
                || !ProcessAuthorization.metadata(config).equals(metadata)
                || !(metadata.get("requestId") instanceof String id) || !id.matches("aichange-[0-9a-f-]{36}")) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> binding = store.execution(id);
            if (binding == null || list(binding.get("initial")).isEmpty() || !metadataMatches(binding, metadata)) return;
            if (!provenChild(binding, config, metadata)) return;
            if (store.recordAuthorizationDenied(id, binding) || AiResourceChangePlanStore.AUTHORIZATION_DENIED.equals(store.failure(id))) return;
        }
        // Completion delivery is retried by the existing engine SPI, without
        // re-running the failed process or dispatching another domain request.
        throw new IllegalStateException("Resource-change outcome unavailable");
    }

    private boolean provenChild(Map<String, Object> binding, LaunchConfiguration config, Map<String, Object> metadata) {
        long accountId = number(binding.get("accountId"));
        Object root = objects.loadResource(nativeType((String) binding.get("resourceType")), number(binding.get("rootId")));
        Object child = objects.loadResource(config.getResourceType(), config.getResourceId());
        if (!ownedRow(root, accountId) || number(binding.get("rootId")) != number(ObjectUtils.getId(root))
                || child == null || !Objects.equals(String.valueOf(ObjectUtils.getId(child)), config.getResourceId())
                || !Objects.equals(uuid(root), binding.get("rootUuid"))
                || !Objects.equals(ObjectUtils.getPropertyIgnoreErrors(root, "stackId"), binding.get("rootStackId") instanceof Number stack
                        ? stack.longValue() : null)) return false;
        if (list(binding.get("initial")).contains(initialIdentity(config, child)))
            return ownedRow(child, accountId) && initialEdge(binding, number(binding.get("rootId")), config, child);
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
        if (!proof.references().isEmpty() && !(child instanceof io.cattle.platform.core.model.VolumeStoragePoolMap)) return false;
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

    private boolean metadataMatches(Map<String, Object> binding, Map<String, Object> metadata) {
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
        return nativeType((String) binding.get("resourceType")) + "." + suffix;
    }

    private static String nativeType(String type) { return "container".equals(type) ? "instance" : type; }
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
        result.put("process", config.getProcessName()); result.put("uuid", uuid(row)); return result;
    }
    private static List<?> list(Object value) { return value instanceof List<?> values ? values : List.of(); }
    private static List<Long> services(Map<String, Object> binding) {
        return list(binding.get("services")).stream().map(AiResourceChangeProcessObserver::number).toList();
    }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : -1; }
    private static Long numeric(String value) {
        try { return value != null && value.matches("[1-9][0-9]*") ? Long.valueOf(value) : null; }
        catch (NumberFormatException invalid) { return null; }
    }
}
