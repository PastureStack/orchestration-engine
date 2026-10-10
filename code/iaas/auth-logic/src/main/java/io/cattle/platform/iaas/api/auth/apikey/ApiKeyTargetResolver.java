package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.resource.NamedResourceIdentity;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.InstanceLink;
import io.cattle.platform.core.model.Nic;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.VolumeStoragePoolMap;
import io.cattle.platform.core.model.IpAddress;
import io.cattle.platform.core.model.IpAddressNicMap;
import io.cattle.platform.core.model.HostIpAddressMap;
import io.cattle.platform.core.model.Image;
import io.cattle.platform.core.model.ImageStoragePoolMap;
import io.cattle.platform.core.model.StoragePool;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.constants.StoragePoolConstants;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.MountTable.MOUNT;
import static io.cattle.platform.core.model.tables.IpAddressNicMapTable.IP_ADDRESS_NIC_MAP;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import static io.cattle.platform.core.model.tables.VolumeStoragePoolMapTable.VOLUME_STORAGE_POOL_MAP;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.HashSet;
import java.util.LinkedHashSet;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.FieldType;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Schema;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;

/** Scope comes from persisted parents; a payload only names a parent to load. */
public class ApiKeyTargetResolver {
    // These resources have no stack-owned lifecycle. Do not infer this from a
    // missing stackId getter on an arbitrary plugin resource.
    static final Set<String> PROJECT_ONLY = Set.of("host", "network", "secret", "certificate", "registry", "storagePool", "storagePoolHostMap", "networkPolicy", "projectMember");
    static final Set<String> PLATFORM = Set.of("schema", "setting", "userPreference", "account", "apiKey", "apiKeyRestricted", "auditLog");
    @Inject ObjectManager objectManager;
    @Inject @Named("DefaultIdFormatter") IdFormatter idFormatter;

    public List<ApiKeyPolicyEvaluator.Target> resolve(ApiRequest request, Policy owner) {
        String type = request.getType();
        if (request.getId() != null) {
            if ("schema".equals(type) || "setting".equals(type)) {
                // These are named, synthetic API resources, not numeric model
                // rows. Existing schema authorization and managers validate the
                // requested name; never reinterpret it as a DB row ID.
                return List.of(ApiKeyPolicyEvaluator.Target.platformResource(type, request.getId()));
            }
            Object value = objectManager.loadResource(type, parentId(type, request.getId()));
            if (value == null) throw new ClientVisibleException(ResponseCodes.NOT_FOUND);
            List<ApiKeyPolicyEvaluator.Target> targets = new ArrayList<>();
            targets.add(resolveObject(type, value, owner));
            if (!"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod())) {
                addDestinationParents(request, owner, targets);
            }
            return targets;
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && request.getAction() == null) {
            return resolveCreate(request, owner);
        }
        return List.of(); // Collection access is narrowed by SQL, not a fake ID.
    }

    public ApiKeyPolicyEvaluator.Target resolveObject(String type, Object value, Policy owner) {
        type = ApiKeyQueryScopes.canonical(type);
        if (owner.authorizeObject(value) == null) denied("OwnerPermissionDenied");
        if (value instanceof NamedResourceIdentity named) {
            String namedType = ApiKeyQueryScopes.canonical(named.getApiResourceType());
            if (!Set.of("setting", "schema").contains(namedType)) denied("KeyScopeDenied");
            return ApiKeyPolicyEvaluator.Target.platformResource(namedType, named.getApiResourceId());
        }
        Object rawId = ObjectUtils.getPropertyIgnoreErrors(value, "id");
        String id = format(type, rawId);
        if (value instanceof Stack stack) return stack("stack", id, stack, owner);
        if (value instanceof Service service) {
            return child("service", id, service.getAccountId(), service.getStackId(), owner);
        }
        if (value instanceof Instance instance) {
            return child("container", id, instance.getAccountId(), instanceParents(instance, owner).stackId(), owner);
        }
        if (value instanceof Volume volume) {
            return child("volume", id, volume.getAccountId(), volume.getStackId(), owner);
        }
        if (value instanceof Account account && AccountConstants.PROJECT_KIND.equals(account.getKind())) {
            return ApiKeyPolicyEvaluator.Target.projectResource(type, id, format("project", account.getId()));
        }
        if (PROJECT_ONLY.contains(type)) {
            Object accountId = ObjectUtils.getPropertyIgnoreErrors(value, "accountId");
            if (accountId instanceof Number n) return child(type, id, n.longValue(), null, owner);
        }
        if (PLATFORM.contains(type)) return ApiKeyPolicyEvaluator.Target.platformResource(type, id);
        return ApiKeyPolicyEvaluator.Target.unresolved(type, id);
    }

    public record ImageDependency(ApiKeyPolicyEvaluator.Target scope, List<ApiKeyPolicyEvaluator.Target> instances) {
        public ImageDependency {
            Objects.requireNonNull(scope, "dependency scope");
            instances = List.copyOf(instances);
        }
    }

    public record LifecycleDependency(ApiKeyPolicyEvaluator.Target scope, List<ApiKeyPolicyEvaluator.Target> resources) {
        public LifecycleDependency {
            Objects.requireNonNull(scope, "dependency scope");
            resources = List.copyOf(resources);
        }
    }

    /** Background lifecycle children inherit only their persisted owning Instance's
     * root grant. This closed model graph is not public API scope resolution and
     * never uses a process name, payload parent, Host or storage pool as ancestry. */
    public LifecycleDependency resolveLifecycleDependency(Object root, Object resource, Policy owner) {
        Class<?> model = lifecycleModel(resource);
        if (model == null || !(root instanceof Instance || root instanceof Service || root instanceof Stack)) return null;
        Class<?> rootModel = root instanceof Instance ? Instance.class : root instanceof Service ? Service.class : Stack.class;
        root = persisted(rootModel, root, owner, "accountId", "stackId", "serviceId");
        Object stored = persisted(model, resource, owner, model == ServiceExposeMap.class
                ? new String[] { "accountId", "instanceId", "serviceId", "managed", "upgrade" }
                : new String[] { "accountId", "instanceId", "hostId", "volumeId", "nicId", "ipAddressId", "storagePoolId", "stackId", "targetInstanceId", "networkId" });
        if (stored instanceof ServiceExposeMap map) {
            // A map belongs to its exact Service, not every Service sharing its
            // Instance. Only persisted managed/upgrade lifecycle maps qualify.
            if (!(root instanceof Service serviceRoot)
                    || !Objects.equals(map.getServiceId(), serviceRoot.getId())
                    || !Objects.equals(map.getAccountId(), serviceRoot.getAccountId())
                    || !(Boolean.TRUE.equals(map.getManaged())
                        || (Boolean.FALSE.equals(map.getManaged()) && Boolean.TRUE.equals(map.getUpgrade())))) denied("KeyScopeDenied");
            Service service = liveResource(Service.class, map.getServiceId(), owner);
            if (service.getStackId() != null) {
                Stack stack = liveResource(Stack.class, service.getStackId(), owner);
                if (!Objects.equals(stack.getAccountId(), map.getAccountId())) denied("KeyScopeDenied");
            }
        }
        List<Object> ancestry = new ArrayList<>();
        List<Instance> instances = lifecycleInstances(stored, owner, ancestry);
        if (instances.isEmpty()) denied("KeyScopeDenied");
        String type = lifecycleType(model);
        String id = format(type, ObjectUtils.getPropertyIgnoreErrors(stored, "id"));
        ApiKeyPolicyEvaluator.Target scope = null;
        Set<ApiKeyPolicyEvaluator.Target> resources = new LinkedHashSet<>();
        for (Instance instance : instances) {
            liveResource(Account.class, instance.getAccountId(), owner);
            var parents = instanceParents(instance, owner);
            var target = child("container", format("container", instance.getId()), instance.getAccountId(), parents.stackId(), owner);
            boolean belongs = root instanceof Instance container && Objects.equals(container.getId(), instance.getId())
                    && Objects.equals(container.getAccountId(), instance.getAccountId())
                    || root instanceof Service service && parents.serviceIds().contains(service.getId())
                    && Objects.equals(service.getAccountId(), instance.getAccountId())
                    || root instanceof Stack stack && Objects.equals(stack.getId(), parents.stackId())
                    && Objects.equals(stack.getAccountId(), instance.getAccountId());
            if (!belongs || target.level() == ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED) denied("KeyScopeDenied");
            if (parents.stackId() != null) {
                Stack stack = objectManager.loadResource(Stack.class, parents.stackId());
                if (stack == null || stack.getRemoved() != null) denied("KeyScopeDenied");
            }
            Object declaredStack = ObjectUtils.getPropertyIgnoreErrors(stored, "stackId");
            if (declaredStack != null && !Objects.equals(declaredStack, parents.stackId())) denied("KeyScopeDenied");
            if (scope != null && (!Objects.equals(scope.projectId(), target.projectId())
                    || !Objects.equals(scope.stackId(), target.stackId()))) denied("KeyScopeDenied");
            scope = target;
            resources.add(target); // Preserve explicit container DENY under a parent ALLOW.
            for (Long serviceId : parents.serviceIds()) {
                resources.add(resolveObject("service", liveResource(Service.class, serviceId, owner), owner));
            }
        }
        var dependency = scope.stackId() == null
                ? ApiKeyPolicyEvaluator.Target.projectResource(type, id, scope.projectId())
                : ApiKeyPolicyEvaluator.Target.stackResource(type, id, scope.projectId(), scope.stackId());
        for (Object ancestor : ancestry) {
            String ancestorType = lifecycleType(lifecycleModel(ancestor));
            String ancestorId = format(ancestorType, ObjectUtils.getPropertyIgnoreErrors(ancestor, "id"));
            resources.add(scope.stackId() == null
                    ? ApiKeyPolicyEvaluator.Target.projectResource(ancestorType, ancestorId, scope.projectId())
                    : ApiKeyPolicyEvaluator.Target.stackResource(ancestorType, ancestorId, scope.projectId(), scope.stackId()));
        }
        return new LifecycleDependency(dependency, List.copyOf(resources));
    }

    private String lifecycleType(Class<?> model) {
        String name = model.getSimpleName();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private Class<?> lifecycleModel(Object resource) {
        if (resource instanceof ServiceExposeMap) return ServiceExposeMap.class;
        if (resource instanceof InstanceHostMap) return InstanceHostMap.class;
        if (resource instanceof Nic) return Nic.class;
        if (resource instanceof Port) return Port.class;
        if (resource instanceof InstanceLink) return InstanceLink.class;
        if (resource instanceof Mount) return Mount.class;
        if (resource instanceof Volume) return Volume.class;
        if (resource instanceof VolumeStoragePoolMap) return VolumeStoragePoolMap.class;
        if (resource instanceof IpAddress) return IpAddress.class;
        if (resource instanceof IpAddressNicMap) return IpAddressNicMap.class;
        if (resource instanceof HostIpAddressMap) return HostIpAddressMap.class;
        return null;
    }

    private List<Instance> lifecycleInstances(Object resource, Policy owner, List<Object> ancestry) {
        ancestry.add(resource);
        if (resource instanceof ServiceExposeMap map) return List.of(liveInstance(map.getInstanceId(), requiredAccount(map.getAccountId()), owner));
        if (resource instanceof InstanceHostMap map) return List.of(liveInstance(map.getInstanceId(), null, owner));
        if (resource instanceof Nic nic) return List.of(liveInstance(nic.getInstanceId(), requiredAccount(nic.getAccountId()), owner));
        if (resource instanceof Port port) return List.of(liveInstance(port.getInstanceId(), requiredAccount(port.getAccountId()), owner));
        if (resource instanceof InstanceLink link) return List.of(liveInstance(link.getInstanceId(), requiredAccount(link.getAccountId()), owner));
        if (resource instanceof Mount mount) {
            Volume volume = liveResource(Volume.class, mount.getVolumeId(), owner);
            ancestry.add(volume);
            if (!Objects.equals(mount.getAccountId(), volume.getAccountId())) denied("KeyScopeDenied");
            Instance instance = liveInstance(mount.getInstanceId(), requiredAccount(mount.getAccountId()), owner);
            requireVolumeStack(volume, instance, owner);
            return List.of(instance);
        }
        if (resource instanceof VolumeStoragePoolMap map) return lifecycleInstances(liveResource(Volume.class, map.getVolumeId(), owner), owner, ancestry);
        if (resource instanceof Volume volume) {
            List<Instance> instances = new ArrayList<>();
            Long accountId = requiredAccount(volume.getAccountId());
            if (volume.getInstanceId() != null) instances.add(liveInstance(volume.getInstanceId(), accountId, owner));
            List<Mount> mounts = objectManager.find(Mount.class, MOUNT.VOLUME_ID, volume.getId(), MOUNT.REMOVED, null);
            if (mounts != null) for (Mount mount : mounts) {
                Mount stored = persisted(Mount.class, mount, owner, "accountId", "instanceId", "volumeId");
                ancestry.add(stored);
                if (!Objects.equals(stored.getVolumeId(), volume.getId()) || !Objects.equals(stored.getAccountId(), accountId)) denied("KeyScopeDenied");
                instances.add(liveInstance(stored.getInstanceId(), accountId, owner));
            }
            for (Instance instance : instances) requireVolumeStack(volume, instance, owner);
            return instances;
        }
        if (resource instanceof IpAddressNicMap map) {
            IpAddress address = liveResource(IpAddress.class, map.getIpAddressId(), owner);
            ancestry.add(address);
            Nic nic = liveResource(Nic.class, map.getNicId(), owner);
            if (!Objects.equals(address.getAccountId(), nic.getAccountId())) denied("KeyScopeDenied");
            return lifecycleInstances(nic, owner, ancestry);
        }
        if (resource instanceof HostIpAddressMap map) return lifecycleInstances(liveResource(IpAddress.class, map.getIpAddressId(), owner), owner, ancestry);
        if (resource instanceof IpAddress address) {
            List<Instance> instances = new ArrayList<>();
            List<IpAddressNicMap> maps = objectManager.find(IpAddressNicMap.class,
                    IP_ADDRESS_NIC_MAP.IP_ADDRESS_ID, address.getId(), IP_ADDRESS_NIC_MAP.REMOVED, null);
            if (maps != null) for (IpAddressNicMap map : maps) {
                IpAddressNicMap stored = persisted(IpAddressNicMap.class, map, owner, "nicId", "ipAddressId");
                if (!Objects.equals(stored.getIpAddressId(), address.getId())) denied("KeyScopeDenied");
                instances.addAll(lifecycleInstances(stored, owner, ancestry));
            }
            return instances;
        }
        throw new IllegalArgumentException("Unknown lifecycle model");
    }

    private Long requiredAccount(Long accountId) {
        if (accountId == null) denied("KeyScopeDenied");
        return accountId;
    }

    private void requireVolumeStack(Volume volume, Instance instance, Policy owner) {
        if (volume.getStackId() != null && !Objects.equals(volume.getStackId(), instanceParents(instance, owner).stackId())) denied("KeyScopeDenied");
    }

    private Instance liveInstance(Long id, Long accountId, Policy owner) {
        Instance instance = liveResource(Instance.class, id, owner);
        if (instance.getAccountId() == null || (accountId != null && !Objects.equals(accountId, instance.getAccountId()))) denied("KeyScopeDenied");
        return instance;
    }

    private <T> T liveResource(Class<T> model, Long id, Policy owner) {
        if (id == null) denied("KeyScopeDenied");
        T stored = objectManager.loadResource(model, id);
        if (stored == null || !Objects.equals(id, ObjectUtils.getPropertyIgnoreErrors(stored, "id"))
                || ObjectUtils.getPropertyIgnoreErrors(stored, "removed") != null) denied("KeyScopeDenied");
        Object state = ObjectUtils.getPropertyIgnoreErrors(stored, "state");
        if (state instanceof String name && Set.of("removed", "purging", "purged").contains(name)) denied("KeyScopeDenied");
        if (owner.authorizeObject(stored) == null) denied("OwnerPermissionDenied");
        return stored;
    }

    private <T> T persisted(Class<T> model, Object resource, Policy owner, String... references) {
        Object id = ObjectUtils.getPropertyIgnoreErrors(resource, "id");
        if (!(id instanceof Long) || ObjectUtils.getPropertyIgnoreErrors(resource, "removed") != null) denied("KeyScopeDenied");
        T stored = liveResource(model, (Long) id, owner);
        for (String reference : references) {
            if (!Objects.equals(ObjectUtils.getPropertyIgnoreErrors(resource, reference),
                    ObjectUtils.getPropertyIgnoreErrors(stored, reference))) denied("KeyScopeDenied");
        }
        return stored;
    }

    /** The Instance remove PRE listener retires its upgrade map before OS storage
     * teardown. Only the trusted finishupgrade job may retain that exact removed
     * relationship as ancestry; it is not a public grant on a removed map. */
    public LifecycleDependency resolveUpgradeRemovalDependency(Object root, Object resource, Policy owner,
            String processName, String resourceType, EngineContext.VerifiedExecutionFrame frame, Map<String, Object> binding) {
        return upgradeRemoval(root, resource, owner, processName, resourceType, frame, binding, null);
    }

    private LifecycleDependency upgradeRemoval(Object root, Object resource, Policy owner,
            String processName, String resourceType, EngineContext.VerifiedExecutionFrame frame, Map<String, Object> binding,
            ProcessAuthorization.VerifiedChild scheduled) {
        boolean instanceStep = resource instanceof Instance && "instance".equals(resourceType)
                && Set.of("instance.remove", "instance.deallocate").contains(processName);
        boolean volumeStep = resource instanceof Volume && "volume".equals(resourceType)
                && Set.of("volume.remove", "volume.deallocate").contains(processName);
        if (!(root instanceof Service) || !(instanceStep || volumeStep)) return null;
        if (instanceStep && !"removing".equals(((Instance) resource).getState())) return null;
        Service service = persisted(Service.class, root, owner, "accountId", "stackId", "state");
        Volume volume = volumeStep ? scheduled == null ? persisted(Volume.class, resource, owner,
                "accountId", "instanceId", "stackId", "imageId", "deviceNumber", "state")
                : cleanupProjection(Volume.class, resource, owner, "accountId", "instanceId", "stackId", "imageId", "deviceNumber", "state") : null;
        Instance instance = instanceStep ? persisted(Instance.class, resource, owner,
                "accountId", "stackId", "serviceId", "state") : scheduled == null
                ? liveInstance(volume.getInstanceId(), volume.getAccountId(), owner)
                : cleanupResource(Instance.class, volume.getInstanceId(), owner);
        // First execution with a still-live map keeps the ordinary resolver.
        // This recovery path exists only once actual teardown is in progress.
        if (!"removing".equals(instance.getState()) && !(scheduled != null && "removed".equals(instance.getState())
                && instance.getRemoved() != null)) return null;
        List<ServiceExposeMap> maps = objectManager.find(ServiceExposeMap.class,
                SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(), SERVICE_EXPOSE_MAP.SERVICE_ID, service.getId(),
                SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true, SERVICE_EXPOSE_MAP.STATE, "removed");
        if (maps == null) denied("KeyScopeDenied");
        if (maps.isEmpty()) return null; // Still-live ancestry retains its existing authorization path.
        if (binding == null || !"service".equals(binding.get("targetType"))
                || !(binding.get("targetId") instanceof String id)
                || !Objects.equals(service.getId(), executionResourceId("service", id))
                || !"upgrade".equals(binding.get("operation")) || !"finishupgrade".equals(binding.get("requestAction"))
                || !"POST".equals(binding.get("requestMethod")) || !Boolean.FALSE.equals(binding.get("requestCollection"))
                || !Boolean.FALSE.equals(binding.get("preview"))) denied("KeyScopeDenied");
        if (!Objects.equals(instance.getAccountId(), service.getAccountId())
                || instance.getServiceId() != null && !Objects.equals(instance.getServiceId(), service.getId())
                || instance.getStackId() != null && !Objects.equals(instance.getStackId(), service.getStackId())) denied("KeyScopeDenied");
        liveResource(Account.class, service.getAccountId(), owner);
        if (service.getStackId() != null) {
            Stack stack = liveResource(Stack.class, service.getStackId(), owner);
            if (!Objects.equals(stack.getAccountId(), service.getAccountId())) denied("KeyScopeDenied");
        }
        if (frame != null && !frame.rootAuthorization().equals(binding)) denied("KeyScopeDenied");
        if (scheduled != null && !scheduled.rootAuthorization().equals(binding)) denied("KeyScopeDenied");
        if (volumeStep || "instance.deallocate".equals(processName)) {
            boolean actualParent = frame != null && ("instance".equals(frame.resourceType())
                    && Objects.equals(instance.getId(), executionResourceId("container", frame.resourceId()))
                    || volumeStep && "volume".equals(frame.resourceType())
                    && Objects.equals(volume.getId(), executionResourceId("volume", frame.resourceId())));
            boolean scheduledParent = scheduled != null && frame == null && volumeStep && "volume".equals(scheduled.parentType())
                    && Objects.equals(volume.getId(), executionResourceId("volume", scheduled.parentId()));
            if (!actualParent && !scheduledParent) denied("KeyScopeDenied");
        }
        var container = child("container", format("container", instance.getId()), instance.getAccountId(), service.getStackId(), owner);
        Set<ApiKeyPolicyEvaluator.Target> resources = new LinkedHashSet<>();
        resources.add(container);
        resources.add(resolveObject("service", service, owner));
        for (ServiceExposeMap supplied : maps) {
            ServiceExposeMap map = supplied.getId() == null ? null : objectManager.loadResource(ServiceExposeMap.class, supplied.getId());
            if (map == null || !Objects.equals(supplied.getId(), map.getId()) || map.getRemoved() == null
                    || !"removed".equals(map.getState()) || !Boolean.FALSE.equals(map.getManaged()) || !Boolean.TRUE.equals(map.getUpgrade())
                    || !Objects.equals(instance.getId(), map.getInstanceId()) || !Objects.equals(service.getId(), map.getServiceId())
                    || !Objects.equals(service.getAccountId(), map.getAccountId())) denied("KeyScopeDenied");
            for (String reference : List.of("accountId", "instanceId", "serviceId", "managed", "upgrade", "removed", "state")) {
                if (!Objects.equals(ObjectUtils.getPropertyIgnoreErrors(supplied, reference),
                        ObjectUtils.getPropertyIgnoreErrors(map, reference))) denied("KeyScopeDenied");
            }
            if (owner.authorizeObject(map) == null) denied("OwnerPermissionDenied");
            resources.add(child("serviceExposeMap", format("serviceExposeMap", map.getId()), map.getAccountId(), service.getStackId(), owner));
        }
        // A currently shared/reassigned Instance may not borrow an old tombstone.
        List<ServiceExposeMap> liveMaps = objectManager.find(ServiceExposeMap.class,
                SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(), SERVICE_EXPOSE_MAP.REMOVED, null);
        if (liveMaps == null) denied("KeyScopeDenied");
        for (ServiceExposeMap row : liveMaps) {
            ServiceExposeMap map = persisted(ServiceExposeMap.class, row, owner, "accountId", "instanceId", "serviceId", "managed", "upgrade");
            if (!Objects.equals(map.getInstanceId(), instance.getId()) || !Objects.equals(map.getServiceId(), service.getId())
                    || !Objects.equals(map.getAccountId(), service.getAccountId())
                    || !Boolean.FALSE.equals(map.getManaged()) || !Boolean.TRUE.equals(map.getUpgrade())) denied("KeyScopeDenied");
            resources.add(child("serviceExposeMap", format("serviceExposeMap", map.getId()), map.getAccountId(), service.getStackId(), owner));
        }
        if (volume != null) {
            if (!Integer.valueOf(0).equals(volume.getDeviceNumber()) || !Objects.equals(volume.getInstanceId(), instance.getId())
                    || !Objects.equals(volume.getAccountId(), instance.getAccountId())
                    || volume.getStackId() != null && !Objects.equals(volume.getStackId(), service.getStackId())) denied("KeyScopeDenied");
            List<Mount> mounts = objectManager.find(Mount.class, MOUNT.VOLUME_ID, volume.getId(), MOUNT.REMOVED, null);
            if (mounts == null) denied("KeyScopeDenied");
            for (Mount row : mounts) {
                Mount mount = persisted(Mount.class, row, owner, "accountId", "instanceId", "volumeId");
                if (!Objects.equals(mount.getVolumeId(), volume.getId()) || !Objects.equals(mount.getInstanceId(), instance.getId())
                        || !Objects.equals(mount.getAccountId(), instance.getAccountId())) denied("KeyScopeDenied");
                resources.add(child("mount", format("mount", mount.getId()), mount.getAccountId(), service.getStackId(), owner));
            }
            var target = child("volume", format("volume", volume.getId()), volume.getAccountId(), service.getStackId(), owner);
            resources.add(target);
            return new LifecycleDependency(target, List.copyOf(resources));
        }
        return new LifecycleDependency(container, List.copyOf(resources));
    }

    /** Exact teardown children only. A queued Volume map REMOVE uses the private
     * persisted scheduling edge, never an invented retry frame. Removed rows are
     * relationship evidence here only; ordinary resolvers remain fail-closed. */
    public LifecycleDependency resolveUpgradeCleanupDependency(Object root, Object resource, Policy owner,
            LaunchConfiguration config, EngineContext.VerifiedExecutionFrame frame,
            EngineContext.VerifiedExecutionFrame parent, Map<String, Object> binding) {
        return upgradeCleanup(root, resource, owner, config, frame, parent, binding, false);
    }

    /** FULL/legacy already authorize the owned root. This only substitutes the
     * null-account ACL of its private Image rows, not a new scoped key grant. */
    public LifecycleDependency resolveOwnedImageCleanupDependency(Object root, Object resource, Policy owner,
            LaunchConfiguration config, EngineContext.VerifiedExecutionFrame frame,
            EngineContext.VerifiedExecutionFrame parent, Map<String, Object> binding) {
        return upgradeCleanup(root, resource, owner, config, frame, parent, binding, true);
    }

    private LifecycleDependency upgradeCleanup(Object root, Object resource, Policy owner,
            LaunchConfiguration config, EngineContext.VerifiedExecutionFrame frame,
            EngineContext.VerifiedExecutionFrame parent, Map<String, Object> binding, boolean fullAuthority) {
        String process = config.getProcessName();
        boolean volumeMap = resource instanceof VolumeStoragePoolMap && "volumeStoragePoolMap".equalsIgnoreCase(config.getResourceType())
                && Set.of("volumestoragepoolmap.deactivate", "volumestoragepoolmap.remove").contains(process);
        boolean hostMap = resource instanceof InstanceHostMap && "instanceHostMap".equalsIgnoreCase(config.getResourceType())
                && Set.of("instancehostmap.deactivate", "instancehostmap.remove").contains(process);
        boolean image = resource instanceof Image && "image".equals(config.getResourceType())
                && Set.of("image.deactivate", "image.remove").contains(process);
        boolean cache = resource instanceof ImageStoragePoolMap && "imageStoragePoolMap".equalsIgnoreCase(config.getResourceType())
                && Set.of("imagestoragepoolmap.deactivate", "imagestoragepoolmap.remove").contains(process);
        if (!fullAuthority && !(root instanceof Service) || !(volumeMap || hostMap || image || cache)) return null;
        if (fullAuthority && (!(image || cache) || !(root instanceof Instance || root instanceof Volume || root instanceof Service || root instanceof Stack))) return null;
        if (!fullAuthority && (binding == null || !"finishupgrade".equals(binding.get("requestAction")))) return null;
        if (!Objects.equals(ObjectUtils.getPropertyIgnoreErrors(resource, "id"), executionResourceId(config.getResourceType(), config.getResourceId())))
            denied("KeyScopeDenied");
        var scheduled = frame == null && volumeMap && "volumestoragepoolmap.remove".equals(process)
                ? ProcessAuthorization.verifiedChild(config) : null;
        if (hostMap) {
            InstanceHostMap map = cleanupProjection(InstanceHostMap.class, resource, owner, "instanceId", "hostId", "state");
            Instance instance = liveInstance(map.getInstanceId(), null, owner);
            LifecycleDependency owned = upgradeRemoval(root, instance, owner, "instance.remove", "instance", frame, binding, null);
            if (owned == null) return null; // Still-live maps keep ordinary lifecycle authorization.
            if (!verified(frame, "instance", map.getInstanceId(), binding)) denied("KeyScopeDenied");
            Host host = liveResource(Host.class, map.getHostId(), owner);
            if (!Objects.equals(host.getAccountId(), instance.getAccountId())) denied("KeyScopeDenied");
            return cleanupChild(owned, "instanceHostMap", map.getId(), instance.getAccountId(), owner,
                    resolveObject("host", host, owner));
        }
        if (frame == null && scheduled == null && volumeMap) {
            VolumeStoragePoolMap map = persisted(VolumeStoragePoolMap.class, resource, owner, "volumeId", "storagePoolId");
            Volume volume = liveResource(Volume.class, map.getVolumeId(), owner);
            // Determine whether recovery is needed before introducing a new
            // frame requirement. Retired ancestry still fails without proof.
            if (upgradeRemoval(root, volume, owner, "volume.remove", "volume", null, binding, null) == null) return null;
        }
        if (frame == null && scheduled == null) denied("KeyScopeDenied");
        EngineContext.VerifiedExecutionFrame volumeFrame = cache ? parent : frame;
        Long volumeId = scheduled != null ? executionResourceId("volume", scheduled.parentId())
                : volumeFrame == null ? null : executionResourceId("volume", volumeFrame.resourceId());
        if (scheduled != null && (!"volume".equals(scheduled.parentType()) || !scheduled.rootAuthorization().equals(binding))) denied("KeyScopeDenied");
        if (scheduled == null && !verified(volumeFrame, "volume", volumeId, binding)) denied("KeyScopeDenied");
        Volume volume = scheduled == null ? liveResource(Volume.class, volumeId, owner) : cleanupResource(Volume.class, volumeId, owner);
        if (!("removing".equals(volume.getState()) || scheduled != null && "removed".equals(volume.getState()) && volume.getRemoved() != null))
            denied("KeyScopeDenied");
        LifecycleDependency owned;
        if (fullAuthority) {
            Instance instance = liveInstance(volume.getInstanceId(), volume.getAccountId(), owner);
            String rootType = root instanceof Instance ? "container" : root instanceof Volume ? "volume" : root instanceof Service ? "service" : "stack";
            if (!Integer.valueOf(0).equals(volume.getDeviceNumber())
                    || !(binding.get("targetType") instanceof String type) || !rootType.equals(ApiKeyQueryScopes.canonical(type))
                    || !(binding.get("targetId") instanceof String id) || !Objects.equals(ObjectUtils.getPropertyIgnoreErrors(root, "id"), executionResourceId(rootType, id))
                    || !Objects.equals(ObjectUtils.getPropertyIgnoreErrors(root, "accountId"), volume.getAccountId())
                    || root instanceof Instance && !Objects.equals(ObjectUtils.getPropertyIgnoreErrors(root, "id"), instance.getId())
                    || root instanceof Volume && !Objects.equals(ObjectUtils.getPropertyIgnoreErrors(root, "id"), volume.getId())) denied("KeyScopeDenied");
            liveResource(Account.class, volume.getAccountId(), owner);
            List<Mount> mounts = objectManager.find(Mount.class, MOUNT.VOLUME_ID, volume.getId(), MOUNT.REMOVED, null);
            if (mounts == null) denied("KeyScopeDenied");
            for (Mount row : mounts) {
                Mount mount = persisted(Mount.class, row, owner, "accountId", "instanceId", "volumeId");
                if (!Objects.equals(mount.getInstanceId(), instance.getId()) || !Objects.equals(mount.getVolumeId(), volume.getId())
                        || !Objects.equals(mount.getAccountId(), volume.getAccountId())) denied("KeyScopeDenied");
            }
            var scope = ApiKeyPolicyEvaluator.Target.projectResource("volume", format("volume", volume.getId()), format("project", volume.getAccountId()));
            owned = new LifecycleDependency(scope, List.of(scope));
        } else owned = upgradeRemoval(root, volume, owner, "volume.remove", "volume", volumeFrame, binding, scheduled);
        if (owned == null) return null;
        if (volumeMap) {
            VolumeStoragePoolMap map = cleanupProjection(VolumeStoragePoolMap.class, resource, owner, "volumeId", "storagePoolId", "state");
            if (!Objects.equals(map.getVolumeId(), volume.getId())) denied("KeyScopeDenied");
            StoragePool pool = liveResource(StoragePool.class, map.getStoragePoolId(), owner);
            if (pool.getAccountId() != null && !Objects.equals(pool.getAccountId(), volume.getAccountId())) denied("KeyScopeDenied");
            if (map.getUuid() == null || volume.getUuid() == null || pool.getUuid() == null) denied("KeyScopeDenied");
            Map<String, String> references = Map.of("childUuid", map.getUuid(), "volumeUuid", volume.getUuid(),
                    "storagePoolId", pool.getId().toString(), "storagePoolUuid", pool.getUuid());
            if (scheduled != null && !references.equals(scheduled.references())) denied("KeyScopeDenied");
            if (frame != null && ProcessAuthorization.verifiedChild(config) != null) {
                var proof = ProcessAuthorization.verifiedChild(config);
                if (!proof.references().isEmpty() && !proof.references().equals(references)) denied("KeyScopeDenied");
                ProcessAuthorization.bindVerifiedChildReferences(config, references);
            }
            return cleanupChild(owned, "volumeStoragePoolMap", map.getId(), volume.getAccountId(), owner,
                    resolveObject("storagePool", pool, owner));
        }
        Image actualImage = objectManager.loadResource(Image.class, volume.getImageId());
        Instance instance = liveInstance(volume.getInstanceId(), volume.getAccountId(), owner);
        if (actualImage == null || volume.getImageId() == null || !Objects.equals(actualImage.getId(), volume.getImageId())
                || !Objects.equals(instance.getImageId(), actualImage.getId()) || actualImage.getUuid() == null
                || !"docker".equals(actualImage.getFormat()) || !"container".equals(actualImage.getInstanceKind())
                || Set.of("purging", "purged").contains(String.valueOf(actualImage.getState()))
                || (actualImage.getRemoved() != null) != "removed".equals(actualImage.getState())
                || actualImage.getAccountId() != null && (!Objects.equals(actualImage.getAccountId(), volume.getAccountId())
                    || owner.authorizeObject(actualImage) == null)
                || !Objects.equals(actualImage.getRegistryCredentialId(), instance.getRegistryCredentialId())) denied("KeyScopeDenied");
        // Remote registration creates a new Image row per Instance, not per tag.
        // Defend against legacy/high-privilege explicit rebinding; Docker content
        // may be shared by name, but these Image/map rows are not a public grant.
        List<Volume> volumes = objectManager.find(Volume.class, io.cattle.platform.core.model.tables.VolumeTable.VOLUME.IMAGE_ID,
                actualImage.getId(), io.cattle.platform.core.model.tables.VolumeTable.VOLUME.REMOVED, null);
        List<Instance> instances = objectManager.find(Instance.class, INSTANCE.IMAGE_ID, actualImage.getId(), INSTANCE.REMOVED, null);
        if (volumes == null || instances == null) denied("KeyScopeDenied");
        for (Volume row : volumes) {
            Volume stored = persisted(Volume.class, row, owner, "accountId", "instanceId", "imageId", "deviceNumber");
            if (!Objects.equals(stored.getId(), volume.getId())) denied("KeyScopeDenied");
        }
        for (Instance row : instances) {
            Instance stored = persisted(Instance.class, row, owner, "accountId", "imageId");
            if (!Objects.equals(stored.getId(), instance.getId())) denied("KeyScopeDenied");
        }
        if (image) {
            for (String field : List.of("id", "accountId", "uuid", "state", "removed", "registryCredentialId", "format", "instanceKind"))
                if (!Objects.equals(ObjectUtils.getPropertyIgnoreErrors(resource, field), ObjectUtils.getPropertyIgnoreErrors(actualImage, field))) denied("KeyScopeDenied");
        } else if (!verified(frame, "image", actualImage.getId(), binding)) denied("KeyScopeDenied");
        Set<ApiKeyPolicyEvaluator.Target> resources = new LinkedHashSet<>(owned.resources());
        resources.add(actualImage.getAccountId() == null ? ApiKeyPolicyEvaluator.Target.platformResource("image", format("image", actualImage.getId()))
                : ApiKeyPolicyEvaluator.Target.projectResource("image", format("image", actualImage.getId()), format("project", actualImage.getAccountId())));
        List<VolumeStoragePoolMap> allocations = objectManager.find(VolumeStoragePoolMap.class, VOLUME_STORAGE_POOL_MAP.VOLUME_ID, volume.getId());
        List<ImageStoragePoolMap> caches = objectManager.find(ImageStoragePoolMap.class,
                io.cattle.platform.core.model.tables.ImageStoragePoolMapTable.IMAGE_STORAGE_POOL_MAP.IMAGE_ID, actualImage.getId());
        if (allocations == null || caches == null) denied("KeyScopeDenied");
        Set<Long> pools = new HashSet<>();
        for (VolumeStoragePoolMap row : allocations) {
            VolumeStoragePoolMap allocation = cleanupProjection(VolumeStoragePoolMap.class, row, owner, "volumeId", "storagePoolId", "state");
            if (!Objects.equals(allocation.getVolumeId(), volume.getId())) denied("KeyScopeDenied");
            StoragePool pool = liveResource(StoragePool.class, allocation.getStoragePoolId(), owner);
            if (pool.getAccountId() != null && !Objects.equals(pool.getAccountId(), volume.getAccountId())) denied("KeyScopeDenied");
            pools.add(pool.getId()); resources.add(resolveObject("storagePool", pool, owner));
            resources.add(owned.scope().stackId() == null
                    ? ApiKeyPolicyEvaluator.Target.projectResource("volumeStoragePoolMap", format("volumeStoragePoolMap", allocation.getId()), owned.scope().projectId())
                    : ApiKeyPolicyEvaluator.Target.stackResource("volumeStoragePoolMap", format("volumeStoragePoolMap", allocation.getId()), owned.scope().projectId(), owned.scope().stackId()));
        }
        boolean found = image;
        for (ImageStoragePoolMap row : caches) {
            ImageStoragePoolMap map = imageCleanupCache(row);
            if (!Objects.equals(map.getImageId(), actualImage.getId()) || !pools.contains(map.getStoragePoolId())) denied("KeyScopeDenied");
            if (cache && Objects.equals(map.getId(), ObjectUtils.getPropertyIgnoreErrors(resource, "id"))) {
                imageCleanupCache((ImageStoragePoolMap) resource); found = true;
            }
            resources.add(ApiKeyPolicyEvaluator.Target.platformResource("imageStoragePoolMap", format("imageStoragePoolMap", map.getId())));
        }
        if (!found) denied("KeyScopeDenied");
        return new LifecycleDependency(owned.scope(), List.copyOf(resources));
    }

    private boolean verified(EngineContext.VerifiedExecutionFrame frame, String type, Long id, Map<String, Object> binding) {
        return frame != null && type.equals(frame.resourceType()) && frame.rootAuthorization().equals(binding)
                && Objects.equals(id, executionResourceId(type, frame.resourceId()));
    }

    private LifecycleDependency cleanupChild(LifecycleDependency owned, String type, Long id, Long account, Policy owner,
            ApiKeyPolicyEvaluator.Target parent) {
        Set<ApiKeyPolicyEvaluator.Target> resources = new LinkedHashSet<>(owned.resources());
        var target = owned.scope().stackId() == null ? ApiKeyPolicyEvaluator.Target.projectResource(type, format(type, id), format("project", account))
                : ApiKeyPolicyEvaluator.Target.stackResource(type, format(type, id), format("project", account), owned.scope().stackId());
        resources.add(target); resources.add(parent);
        return new LifecycleDependency(target, List.copyOf(resources));
    }

    private <T> T cleanupResource(Class<T> model, Long id, Policy owner) {
        T stored = id == null ? null : objectManager.loadResource(model, id);
        if (stored == null || !Objects.equals(id, ObjectUtils.getPropertyIgnoreErrors(stored, "id"))
                || Set.of("purging", "purged").contains(String.valueOf(ObjectUtils.getPropertyIgnoreErrors(stored, "state")))) denied("KeyScopeDenied");
        Object removed = ObjectUtils.getPropertyIgnoreErrors(stored, "removed");
        if ((removed != null) != "removed".equals(ObjectUtils.getPropertyIgnoreErrors(stored, "state"))) denied("KeyScopeDenied");
        if (owner.authorizeObject(stored) == null) denied("OwnerPermissionDenied");
        return stored;
    }

    private <T> T cleanupProjection(Class<T> model, Object supplied, Policy owner, String... fields) {
        Long id = (Long) ObjectUtils.getPropertyIgnoreErrors(supplied, "id");
        T stored = cleanupResource(model, id, owner);
        if (!Objects.equals(ObjectUtils.getPropertyIgnoreErrors(supplied, "removed"), ObjectUtils.getPropertyIgnoreErrors(stored, "removed"))) denied("KeyScopeDenied");
        for (String field : fields) if (!Objects.equals(ObjectUtils.getPropertyIgnoreErrors(supplied, field), ObjectUtils.getPropertyIgnoreErrors(stored, field))) denied("KeyScopeDenied");
        return stored;
    }

    private ImageStoragePoolMap imageCleanupCache(ImageStoragePoolMap supplied) {
        ImageStoragePoolMap stored = supplied.getId() == null ? null : objectManager.loadResource(ImageStoragePoolMap.class, supplied.getId());
        if (stored == null || !Objects.equals(supplied.getId(), stored.getId())
                || Set.of("purging", "purged").contains(String.valueOf(stored.getState()))
                || (stored.getRemoved() != null) != "removed".equals(stored.getState())) denied("KeyScopeDenied");
        for (String field : List.of("imageId", "storagePoolId", "state", "removed", "uuid"))
            if (!Objects.equals(ObjectUtils.getPropertyIgnoreErrors(supplied, field), ObjectUtils.getPropertyIgnoreErrors(stored, field))) denied("KeyScopeDenied");
        return stored;
    }

    /** Only the verified executing Volume may ensure its allocated image cache.
     * Other users of a shared Image are neither ancestors nor newly authorized.
     * Shared image/pool targets are retained only to preserve explicit DENY; the
     * dependency scope remains the actual owned Volume, not a public image grant. */
    public LifecycleDependency resolveImageEnsureDependency(Object root, Object resource, Policy owner,
            EngineContext.VerifiedExecutionFrame frame, Map<String, Object> binding) {
        if (frame == null || !"volume".equals(frame.resourceType()) || frame.rootAuthorization().isEmpty()
                || !frame.rootAuthorization().equals(binding)) denied("KeyScopeDenied");
        String rootType = root instanceof Instance ? "container" : root instanceof Service ? "service" : root instanceof Stack ? "stack" : null;
        if (rootType == null || !(binding.get("targetType") instanceof String type)
                || !rootType.equals(ApiKeyQueryScopes.canonical(type)) || !(binding.get("targetId") instanceof String id)
                || !Objects.equals(ObjectUtils.getPropertyIgnoreErrors(root, "id"), executionResourceId(rootType, id))) denied("KeyScopeDenied");
        Volume volume = liveResource(Volume.class, executionResourceId("volume", frame.resourceId()), owner);
        LifecycleDependency owned = resolveLifecycleDependency(root, volume, owner);
        if (owned == null || volume.getImageId() == null) denied("KeyScopeDenied");
        ImageStoragePoolMap cache = null;
        if (resource instanceof Image image) {
            if (!Objects.equals(volume.getImageId(), image.getId()) || image.getRemoved() != null) denied("KeyScopeDenied");
        } else if (resource instanceof ImageStoragePoolMap map) {
            cache = sharedImageCache(map);
            if (!Objects.equals(cache.getImageId(), volume.getImageId())) denied("KeyScopeDenied");
        } else denied("KeyScopeDenied");
        Image image = objectManager.loadResource(Image.class, volume.getImageId());
        if (image == null || !Objects.equals(volume.getImageId(), image.getId()) || image.getRemoved() != null
                || Set.of("removed", "purging", "purged").contains(String.valueOf(image.getState()))) denied("KeyScopeDenied");
        if (image.getAccountId() != null && owner.authorizeObject(image) == null) denied("OwnerPermissionDenied");
        if (resource instanceof Image supplied && (!Objects.equals(supplied.getAccountId(), image.getAccountId())
                || !Objects.equals(supplied.getState(), image.getState())
                || !Objects.equals(supplied.getRegistryCredentialId(), image.getRegistryCredentialId()))) denied("KeyScopeDenied");
        Set<ApiKeyPolicyEvaluator.Target> resources = new LinkedHashSet<>(owned.resources());
        if (image.getRegistryCredentialId() != null) {
            Credential credential = liveResource(Credential.class, image.getRegistryCredentialId(), owner);
            if (!"active".equals(credential.getState()) || !CredentialConstants.KIND_REGISTRY_CREDENTIAL.equals(credential.getKind())
                    || !Objects.equals(credential.getAccountId(), volume.getAccountId())) denied("OwnerPermissionDenied");
            StoragePool registry = liveResource(StoragePool.class, credential.getRegistryId(), owner);
            if (!"active".equals(registry.getState()) || !StoragePoolConstants.KIND_REGISTRY.equals(registry.getKind())
                    || !Objects.equals(registry.getAccountId(), volume.getAccountId())) denied("OwnerPermissionDenied");
            for (Instance instance : lifecycleInstances(volume, owner, new ArrayList<>())) {
                if (!Objects.equals(instance.getRegistryCredentialId(), credential.getId())) denied("OwnerPermissionDenied");
            }
            resources.add(ApiKeyPolicyEvaluator.Target.projectResource("registryCredential", format("registryCredential", credential.getId()),
                    format("project", credential.getAccountId())));
            resources.add(ApiKeyPolicyEvaluator.Target.projectResource("registry", format("registry", registry.getId()),
                    format("project", registry.getAccountId())));
        }
        resources.add(image.getAccountId() == null
                ? ApiKeyPolicyEvaluator.Target.platformResource("image", format("image", image.getId()))
                : ApiKeyPolicyEvaluator.Target.projectResource("image", format("image", image.getId()), format("project", image.getAccountId())));
        List<VolumeStoragePoolMap> allocations = cache == null
                ? objectManager.find(VolumeStoragePoolMap.class, VOLUME_STORAGE_POOL_MAP.VOLUME_ID, volume.getId(),
                        VOLUME_STORAGE_POOL_MAP.REMOVED, null)
                : objectManager.find(VolumeStoragePoolMap.class,
                    VOLUME_STORAGE_POOL_MAP.VOLUME_ID, volume.getId(),
                    VOLUME_STORAGE_POOL_MAP.STORAGE_POOL_ID, cache.getStoragePoolId(), VOLUME_STORAGE_POOL_MAP.REMOVED, null);
        if (allocations == null || allocations.isEmpty()) denied("KeyScopeDenied");
        for (VolumeStoragePoolMap allocation : allocations) {
            VolumeStoragePoolMap stored = persisted(VolumeStoragePoolMap.class, allocation, owner, "volumeId", "storagePoolId");
            if (!Objects.equals(stored.getVolumeId(), volume.getId())
                    || (cache != null && !Objects.equals(stored.getStoragePoolId(), cache.getStoragePoolId()))) denied("KeyScopeDenied");
            StoragePool pool = liveResource(StoragePool.class, stored.getStoragePoolId(), owner);
            resources.addAll(resolveLifecycleDependency(root, stored, owner).resources());
            resources.add(resolveObject("storagePool", pool, owner));
        }
        if (cache != null) {
            resources.add(ApiKeyPolicyEvaluator.Target.platformResource("imageStoragePoolMap", format("imageStoragePoolMap", cache.getId())));
        }
        return new LifecycleDependency(owned.scope(), List.copyOf(resources));
    }

    private ImageStoragePoolMap sharedImageCache(ImageStoragePoolMap supplied) {
        if (supplied.getId() == null || supplied.getRemoved() != null) denied("KeyScopeDenied");
        ImageStoragePoolMap stored = objectManager.loadResource(ImageStoragePoolMap.class, supplied.getId());
        // Shared cache rows have no owning Account. This reload is only used
        // after verified Volume/root checks, never for ordinary object grants.
        if (stored == null || !Objects.equals(supplied.getId(), stored.getId()) || stored.getRemoved() != null
                || Set.of("removed", "purging", "purged").contains(String.valueOf(stored.getState()))
                || !Objects.equals(supplied.getState(), stored.getState())
                || !Objects.equals(supplied.getImageId(), stored.getImageId())
                || !Objects.equals(supplied.getStoragePoolId(), stored.getStoragePoolId())) denied("KeyScopeDenied");
        return stored;
    }

    private Long executionResourceId(String type, String raw) {
        // ProcessRecord uses raw decimal IDs. Typed IDs must still round-trip
        // as the actual schema; this does not change public scope resolution.
        try { return raw != null && raw.matches("[0-9]+") ? Long.valueOf(raw) : parentId(type, raw); }
        catch (NumberFormatException invalid) { denied("KeyScopeDenied"); return null; }
    }

    /** Internal image creation is a dependency, not a separate image API grant.
     * Only persisted references from the authorized container/service/stack
     * establish that relationship. Preserve each real container target so its
     * explicit resource DENY cannot be hidden by the derived image scope.
     * No process-data or request hint is trusted.
     */
    public ImageDependency resolveImageDependency(Object root, Image image, Policy owner) {
        if (image.getId() == null || image.getRemoved() != null) denied("KeyScopeDenied");
        List<Instance> instances = objectManager.find(Instance.class,
                INSTANCE.IMAGE_ID, image.getId(), INSTANCE.REMOVED, null);
        if (instances == null || instances.isEmpty()) denied("KeyScopeDenied");
        ApiKeyPolicyEvaluator.Target scope = null;
        List<ApiKeyPolicyEvaluator.Target> instanceTargets = new ArrayList<>();
        for (Instance instance : instances) {
            if (!Objects.equals(instance.getImageId(), image.getId()) || instance.getRemoved() != null
                    || owner.authorizeObject(instance) == null) denied("OwnerPermissionDenied");
            var parents = instanceParents(instance, owner);
            var candidate = child("container", format("container", instance.getId()), instance.getAccountId(), parents.stackId(), owner);
            boolean belongs = root instanceof Instance container && Objects.equals(container.getId(), instance.getId())
                    || root instanceof Service service && parents.serviceIds().contains(service.getId())
                    || root instanceof Stack stack && Objects.equals(format("stack", stack.getId()), candidate.stackId());
            if (!belongs || candidate.level() == ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED) denied("KeyScopeDenied");
            if (scope != null && (!Objects.equals(scope.projectId(), candidate.projectId())
                    || !Objects.equals(scope.stackId(), candidate.stackId()))) denied("KeyScopeDenied");
            scope = candidate;
            instanceTargets.add(candidate);
        }
        ApiKeyPolicyEvaluator.Target imageScope = scope.stackId() != null
                ? ApiKeyPolicyEvaluator.Target.stackResource("image", format("image", image.getId()), scope.projectId(), scope.stackId())
                : ApiKeyPolicyEvaluator.Target.projectResource("image", format("image", image.getId()), scope.projectId());
        return new ImageDependency(imageScope, instanceTargets);
    }

    private record InstanceParents(Long stackId, Set<Long> serviceIds) { }

    /** Service-owned instances use the persisted managed or upgrade expose map; older
     * denormalized service/stack columns, when present, must agree with it. */
    private InstanceParents instanceParents(Instance instance, Policy owner) {
        Long stackId = instance.getStackId();
        boolean hasParent = stackId != null;
        Set<Long> serviceIds = new HashSet<>();
        if (instance.getServiceId() != null) {
            stackId = serviceStack(instance, instance.getServiceId(), stackId, hasParent, owner);
            hasParent = true;
            serviceIds.add(instance.getServiceId());
        }
        List<ServiceExposeMap> maps = new ArrayList<>();
        List<ServiceExposeMap> managedMaps = objectManager.find(ServiceExposeMap.class,
                SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(), SERVICE_EXPOSE_MAP.REMOVED, null,
                SERVICE_EXPOSE_MAP.MANAGED, true);
        if (managedMaps != null) maps.addAll(managedMaps);
        // UpgradeManager persists managed=false, upgrade=true before stopping
        // an old child. That precise live map still establishes its Service parent.
        List<ServiceExposeMap> upgradeMaps = objectManager.find(ServiceExposeMap.class,
                SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(), SERVICE_EXPOSE_MAP.REMOVED, null,
                SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true);
        if (upgradeMaps != null) maps.addAll(upgradeMaps);
        for (ServiceExposeMap map : maps) {
            if (!Objects.equals(instance.getId(), map.getInstanceId())
                    || !Objects.equals(instance.getAccountId(), map.getAccountId())
                    || map.getRemoved() != null || !(Boolean.TRUE.equals(map.getManaged())
                        || (Boolean.FALSE.equals(map.getManaged()) && Boolean.TRUE.equals(map.getUpgrade())))
                    || map.getServiceId() == null) denied("KeyScopeDenied");
            stackId = serviceStack(instance, map.getServiceId(), stackId, hasParent, owner);
            hasParent = true;
            serviceIds.add(map.getServiceId());
        }
        return new InstanceParents(stackId, Set.copyOf(serviceIds));
    }

    private Long serviceStack(Instance instance, Long serviceId, Long expectedStack, boolean hasParent, Policy owner) {
        Service service = objectManager.loadResource(Service.class, serviceId);
        if (service == null || service.getRemoved() != null || owner.authorizeObject(service) == null
                || !Objects.equals(instance.getAccountId(), service.getAccountId())
                || (hasParent && !Objects.equals(expectedStack, service.getStackId()))) denied("KeyScopeDenied");
        return service.getStackId();
    }

    private List<ApiKeyPolicyEvaluator.Target> resolveCreate(ApiRequest request, Policy owner) {
        List<ApiKeyPolicyEvaluator.Target> targets = new ArrayList<>();
        addDestinationParents(request, owner, targets);
        if (targets.isEmpty()) {
            Account project = objectManager.loadResource(Account.class, owner.getAccountId());
            if (project != null && AccountConstants.PROJECT_KIND.equals(project.getKind()) && owner.authorizeObject(project) != null) {
                String projectId = format("project", project.getId());
                targets.add(ApiKeyPolicyEvaluator.Target.projectResource("project", projectId, projectId));
            }
        }
        return targets;
    }

    private void addDestinationParents(ApiRequest request, Policy owner, List<ApiKeyPolicyEvaluator.Target> targets) {
        Map<?, ?> body = request.getRequestObject() instanceof Map<?, ?> m ? m : Map.of();
        Schema input = inputSchema(request);
        Long declaredAccount = acceptedParentId(request, input, body, "project", "accountId");
        Long declaredStack = acceptedParentId(request, input, body, "stack", "stackId");
        if (declaredStack != null) {
            Stack stack = objectManager.loadResource(Stack.class, declaredStack);
            if (stack == null || (declaredAccount != null && !declaredAccount.equals(stack.getAccountId()))) denied("KeyScopeDenied");
            targets.add(stack("stack", format("stack", stack.getId()), stack, owner));
        }
        Long serviceId = acceptedParentId(request, input, body, "service", "serviceId");
        if (serviceId != null) {
            Service service = objectManager.loadResource(Service.class, serviceId);
            if (service == null || (declaredStack != null && !declaredStack.equals(service.getStackId()))
                    || (declaredAccount != null && !declaredAccount.equals(service.getAccountId()))) denied("KeyScopeDenied");
            targets.add(resolveObject("service", service, owner));
        }
        Long instanceId = acceptedParentId(request, input, body, "container", "instanceId");
        if (instanceId != null) {
            Instance instance = objectManager.loadResource(Instance.class, instanceId);
            if (instance == null) denied("KeyScopeDenied");
            targets.add(resolveObject("container", instance, owner));
        }
        if (declaredAccount != null && targets.isEmpty()) {
            Account project = objectManager.loadResource(Account.class, declaredAccount);
            if (project == null || !AccountConstants.PROJECT_KIND.equals(project.getKind())) denied("KeyScopeDenied");
            targets.add(resolveObject("project", project, owner));
        }
    }

    /** All schema-declared references must also be readable. Never trust a URL's parent alone. */
    public List<ApiKeyPolicyEvaluator.Target> references(ApiRequest request, Policy owner) {
        List<ApiKeyPolicyEvaluator.Target> targets = new ArrayList<>();
        if (!"POST".equalsIgnoreCase(request.getMethod()) && !"PUT".equalsIgnoreCase(request.getMethod())) return targets;
        collectReferences(request, inputSchema(request), request.getRequestObject(), owner, targets, 0,
                "POST".equalsIgnoreCase(request.getMethod()));
        return targets;
    }

    /** Match the public validator's accepted input, not fields it will discard. */
    private Schema inputSchema(ApiRequest request) {
        if (request.getSchemaFactory() == null) return null;
        Schema schema = request.getSchemaFactory().getSchema(request.getType());
        if (schema == null || request.getAction() == null || !"POST".equalsIgnoreCase(request.getMethod())) return schema;
        Map<String, Action> actions = request.getId() == null ? schema.getCollectionActions() : schema.getResourceActions();
        Action action = actions == null ? null : actions.get(request.getAction());
        return action == null || action.getInput() == null ? null
                : request.getSchemaFactory().getSchema(action.getInput());
    }

    private Long acceptedParentId(ApiRequest request, Schema schema, Map<?, ?> body, String type, String name) {
        if (schema == null || schema.getResourceFields() == null) return null;
        Field field = schema.getResourceFields().get(name);
        boolean create = "POST".equalsIgnoreCase(request.getMethod());
        if (!acceptedField(field, create) || (!create && !"PUT".equalsIgnoreCase(request.getMethod()))) return null;
        List<FieldType.TypeAndName> parts = FieldType.parse(field.getType());
        if (parts.size() != 2 || parts.getFirst().getType() != FieldType.REFERENCE) return null;
        String reference = ApiKeyQueryScopes.canonical(parts.get(1).getName());
        if (!(type.equals(reference) || ("project".equals(type) && "account".equals(reference)))) return null;
        return parentId(type, body.get(name));
    }

    private boolean acceptedField(Field field, boolean create) {
        return field != null && (create ? field.isCreate() : field.isUpdate());
    }

    private void collectReferences(ApiRequest request, Schema schema, Object raw, Policy owner,
            List<ApiKeyPolicyEvaluator.Target> targets, int depth, boolean create) {
        if (!(raw instanceof Map<?, ?> body) || schema == null) return;
        if (depth > 16 || targets.size() > 1024) denied("KeyScopeDenied");
        if (schema.getResourceFields() == null) return;
        for (Map.Entry<String, Field> entry : schema.getResourceFields().entrySet()) {
            Field field = entry.getValue();
            if (!acceptedField(field, create)) continue;
            Object value = body.get(entry.getKey());
            if (value == null) continue;
            List<FieldType.TypeAndName> parts = FieldType.parse(field.getType());
            String referenceType = null;
            for (int i = 0; i + 1 < parts.size(); i++) {
                if (parts.get(i).getType() == FieldType.REFERENCE) referenceType = parts.get(i + 1).getName();
            }
            List<?> values = value instanceof List<?> list ? list : List.of(value);
            for (Object item : values) {
                if (item == null) continue;
                if (referenceType != null) {
                    Long id = parentId(referenceType, item);
                    Object referenced = objectManager.loadResource(referenceType, id.toString());
                    if (referenced == null) denied("KeyScopeDenied");
                    targets.add(resolveObject(referenceType, referenced, owner));
                } else {
                    String nested = parts.get(parts.size() - 1).getName();
                    // ValidationHandler converts complex values as newly supplied
                    // input even when their enclosing resource is being updated.
                    collectReferences(request, request.getSchemaFactory().getSchema(nested), item, owner, targets, depth + 1, true);
                }
            }
        }
    }

    private ApiKeyPolicyEvaluator.Target child(String type, String id, Long accountId, Long stackId, Policy owner) {
        if (accountId == null) return ApiKeyPolicyEvaluator.Target.unresolved(type, id);
        Account project = objectManager.loadResource(Account.class, accountId);
        if (project == null || !AccountConstants.PROJECT_KIND.equals(project.getKind())) return ApiKeyPolicyEvaluator.Target.unresolved(type, id);
        if (stackId == null) return ApiKeyPolicyEvaluator.Target.projectResource(type, id, format("project", accountId));
        Stack stack = objectManager.loadResource(Stack.class, stackId);
        if (stack == null || !accountId.equals(stack.getAccountId()) || owner.authorizeObject(stack) == null) denied("KeyScopeDenied");
        return ApiKeyPolicyEvaluator.Target.stackResource(type, id, format("project", accountId), format("stack", stackId));
    }

    private ApiKeyPolicyEvaluator.Target stack(String type, String id, Stack stack, Policy owner) {
        return child(type, id, stack.getAccountId(), stack.getId(), owner);
    }

    private Long parentId(String type, Object raw) {
        if (raw == null) return null;
        try {
            String value = raw.toString();
            Long id = Long.valueOf(idFormatter.parseId(value));
            // Raw numeric IDs are valid for internal requests. External typed IDs
            // must round-trip as the expected schema, not an unrelated prefix.
            if (!value.matches("[0-9]+") && !value.equals(format(type, id))) denied("KeyScopeDenied");
            return id;
        }
        catch (RuntimeException e) { denied("KeyScopeDenied"); return null; }
    }

    private String format(String type, Object id) {
        if (id == null) denied("KeyScopeDenied");
        return String.valueOf(idFormatter.formatId(type, id));
    }

    public Long parseScopeId(String type, String raw) {
        Long id = parentId(type, raw);
        if (!raw.equals(format(type, id))) denied("KeyScopeDenied");
        return id;
    }

    private static void denied(String code) {
        throw new ClientVisibleException(ResponseCodes.FORBIDDEN, code, "This API key cannot access the requested resource scope.", null);
    }
}
