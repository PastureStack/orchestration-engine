package io.cattle.platform.servicediscovery.api.filter;

import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;

import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.engine.process.ProcessStateTransitionGuard;
import io.cattle.platform.iaas.api.filter.common.AbstractDefaultResourceManagerFilter;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.AbstractLockDefinition;
import io.cattle.platform.lock.definition.BlockingLockDefinition;
import io.cattle.platform.lock.definition.Namespace;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.ObjectUtils;
import io.cattle.platform.process.common.lock.ResourceChangeLock;
import io.cattle.platform.servicediscovery.api.service.ServiceInstanceAssociationGuard;
import io.cattle.platform.servicediscovery.api.service.ArchivedAssociationException;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jooq.Condition;

/** Archive metadata and lifecycle transitions share short, per-stack locks. */
@Named
public class ArchiveValidationFilter extends AbstractDefaultResourceManagerFilter implements ProcessStateTransitionGuard, ServiceInstanceAssociationGuard {
    @Inject ObjectManager objectManager;
    @Inject LockManager lockManager;
    @Inject ArchiveInstances instances;
    private final ThreadLocal<Set<Long>> held = ThreadLocal.withInitial(HashSet::new);
    private static final Set<String> SAFE_SERVICE_STATES = Set.of("inactive", "updating-inactive", "removing", "removed", "purging", "purged");
    private static final Set<String> SAFE_INSTANCE_STATES = Set.of("stopped", "stopping", "removing", "removed", "purging", "purged");

    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { Stack.class, Service.class, Instance.class }; }
    @Override public String[] getTypes() {
        return new String[] { "stack", "environment", "composeProject", "kubernetesStack",
                "service", "loadBalancerService", "externalService", "dnsService", "networkDriverService",
                "storageDriverService", "kubernetesService", "composeService", "instance", "container", "virtualMachine" };
    }

    @Override
    public Object create(String type, ApiRequest request, ResourceManager next) {
        Map<String, Object> body = CollectionUtils.toMap(request.getRequestObject());
        if (Boolean.TRUE.equals(body.get("archived"))) throw error(422, "ArchiveCreateUnsupported", "Create the resource normally, then stop it before archiving.");
        Long stackId = parentId(body);
        if (stackId == null) return super.create(type, request, next);
        return withLocks(Set.of(stackId), () -> {
            assertParentAvailable(stackId);
            return super.create(type, request, next);
        });
    }

    @Override
    public Object update(String type, String id, ApiRequest request, ResourceManager next) {
        Map<String, Object> body = CollectionUtils.toMap(request.getRequestObject());
        if (!body.containsKey("archived") && !body.containsKey("stackId") && !body.containsKey("environmentId")
                && !body.containsKey("selectorContainer") && !body.containsKey("selectorLink")) {
            return super.update(type, id, request, next);
        }
        // Use the same mutation-target authorization as the final manager before any domain checks.
        Object target = next.getById(type, id, ListOptions.forMutationTarget(request, type, id));
        if (target == null) return super.update(type, id, request, next);
        boolean archiveField = (target instanceof Stack || target instanceof Service) && body.containsKey("archived");
        boolean parentField = !(target instanceof Stack) && (body.containsKey("stackId") || body.containsKey("environmentId"));
        boolean selectorFields = target instanceof Service && (body.containsKey("selectorContainer") || body.containsKey("selectorLink"));
        if (archiveField && !(body.get("archived") instanceof Boolean)) throw error(422, "InvalidOption", "archived must be a boolean.");
        Long destination = parentId(body);
        if (!archiveField && !parentField && !selectorFields) return super.update(type, id, request, next);
        Set<Long> locks = stackIds(target);
        if (destination != null) locks.add(destination);
        Supplier<Object> update = () -> {
          boolean[] ordinaryUpdate = { false };
          Object result = withLocks(locks, () -> {
            Object current = objectManager.reload(target);
            if (!locks.containsAll(stackIds(current))) throw error(409, "ArchiveConcurrentChange", "The resource moved while it was being updated. Reload and try again.");
            boolean changed = archiveField && archived(current) != Boolean.TRUE.equals(body.get("archived"));
            boolean moved = parentField && !Objects.equals(destination, stackId(current));
            boolean selectorChanged = selectorFields && (body.containsKey("selectorContainer") && !Objects.equals(body.get("selectorContainer"), ((Service) current).getSelectorContainer())
                    || body.containsKey("selectorLink") && !Objects.equals(body.get("selectorLink"), ((Service) current).getSelectorLink()));
            if (selectorChanged) assertNotArchived(current);
            if (!changed && !moved && !selectorChanged) {
                // Do not replay an old full DTO's identical archive value after releasing the lock.
                Map<String, Object> ordinary = new LinkedHashMap<>(body);
                ordinary.remove("archived");
                if (parentField) { ordinary.remove("stackId"); ordinary.remove("environmentId"); }
                if (selectorFields) { ordinary.remove("selectorContainer"); ordinary.remove("selectorLink"); }
                request.setRequestObject(ordinary);
                ordinaryUpdate[0] = true;
                return null;
            }
            if (changed) {
                assertMetadataOnly(current, body, "archived");
                if (Boolean.TRUE.equals(body.get("archived"))) assertArchivable(current);
            }
            if (moved) {
                assertMetadataOnly(current, body, body.containsKey("stackId") ? "stackId" : "environmentId");
                assertNotArchived(current);
                assertParentAvailable(destination);
            }
            // The existing manager remains the only writer and repeats its normal authorization.
            return super.update(type, id, request, next);
          });
          return ordinaryUpdate[0] ? super.update(type, id, request, next) : result;
        };
        // Match the scheduler's lock order, rather than taking archive -> schedule in a selector update.
        return selectorFields ? lockManager.lock(new Namespace("schedule").getLockDefinition(
                new ResourceChangeLock("service", ((Service) target).getId())), update::get) : update.get();
    }

    @Override public Object resourceAction(String type, ApiRequest request, ResourceManager next) {
        if ("activateservices".equals(request.getAction())) {
            Object target = next.getById(type, request.getId(), ListOptions.forResourceActionTarget(request,type,request.getId()));
            if (target instanceof Stack stack) withLocks(stackIds(stack), () -> {
                assertNotArchived(objectManager.reload(stack));
                for (Service child : services(stack)) assertNotArchived(child);
                return null;
            });
        }
        return super.resourceAction(type, request, next);
    }

    @Override public ServiceExposeMap associate(Service service, Instance instance, Supplier<ServiceExposeMap> next) {
        return withInstanceLock(instance, () -> {
            Set<Long> ids=stackIds(service);
            return withLocks(ids, () -> {
                Service current=objectManager.reload(service);
                if (!ids.containsAll(stackIds(current))) throw error(409,"ArchiveConcurrentChange","The service moved. Reload and try again.");
                if (blocksNewAssociations(current)) throw new ArchivedAssociationException();
                return next.get();
            });
        });
    }

    @Override public boolean blocksNewAssociations(Service service) {
        if (archived(service)) return true;
        Stack parent=objectManager.loadResource(Stack.class,service.getStackId());
        return archived(parent);
    }

    @Override
    public boolean transition(Object resource, String stateField, String newState, Supplier<Boolean> next) {
        if (!"state".equals(stateField) || !(resource instanceof Service || resource instanceof Instance)
                || resource instanceof Service && SAFE_SERVICE_STATES.contains(newState)
                || resource instanceof Instance && SAFE_INSTANCE_STATES.contains(newState)) return next.get();
        Supplier<Boolean> check = () -> {
            Set<Long> ids = stackIds(resource);
            return withLocks(ids, () -> {
                Object current = objectManager.reload(resource);
                if (!ids.containsAll(stackIds(current))) return false;
                assertNotArchived(current);
                return next.get();
            });
        };
        return resource instanceof Instance instance ? withInstanceLock(instance, check) : check.get();
    }

    private <T> T withInstanceLock(Instance instance, Supplier<T> action) {
        // A new selector association can add a parent to a previously independent, stopped instance.
        // Serialize that change with discovering parents and writing the instance's starting state.
        return lockManager.lock(new InstanceArchiveLock(instance.getId()), action::get);
    }

    void assertArchivable(Object resource) {
        if (system(resource)) throw error(422, "ArchiveSystemResource", "System and infrastructure resources cannot be archived.");
        if (resource instanceof Service service) {
            requireStopped("inactive".equals(service.getState()) && !transitioning(service));
        } else if (resource instanceof Stack stack) {
            requireStopped(stack.getState() != null && Set.of("active", "inactive").contains(stack.getState()) && !transitioning(stack));
            for (Service service : services(stack)) {
                if (system(service)) throw error(422, "ArchiveSystemResource", "System and infrastructure resources cannot be archived.");
                requireStopped("inactive".equals(service.getState()) && !transitioning(service));
            }
        }
        for (Instance instance : instances.related(resource)) {
            if (terminal(instance)) continue;
            requireStopped("stopped".equals(instance.getState()) && !transitioning(instance));
            if (system(instance)) throw error(422, "ArchiveSystemResource", "System and infrastructure resources cannot be archived.");
        }
    }

    protected List<? extends Service> services(Stack stack) {
        return objectManager.find(Service.class, SERVICE.STACK_ID, stack.getId(), SERVICE.REMOVED, null);
    }

    void assertNotArchived(Object resource) {
        if (archived(resource)) throw archivedError();
        for (Long parent : stackIds(resource)) assertParentAvailable(parent);
        if (resource instanceof Instance instance) {
            for (Service service : instances.lifecycleOwners(instance)) if (archived(service)) throw archivedError();
        }
    }

    private void assertParentAvailable(Long id) {
        if (id == null) return;
        Stack stack = objectManager.loadResource(Stack.class, id);
        if (stack != null && archived(stack)) throw archivedError();
    }

    protected boolean system(Object resource) {
        if (ObjectUtils.isSystem(resource) || flag(resource, "system") || flag(resource, "isSystem")) return true;
        if (resource instanceof Service service) {
            if (service.getKind() != null && Set.of("networkDriverService", "storageDriverService").contains(service.getKind())) return true;
            Stack parent = objectManager.loadResource(Stack.class, service.getStackId());
            if (parent != null && system(parent)) return true;
            Map<?, ?> config = DataAccessor.fieldMap(resource, "launchConfig");
            if (config.get("labels") instanceof Map<?, ?> labels && systemLabel(labels)) return true;
        }
        Object group = DataAccessor.field(resource, "group", Object.class);
        if (group != null) for (String name : group.toString().split(",")) {
            if (Set.of("infra", "infrastructure", "system").contains(name.trim().toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return resource instanceof Instance && systemLabel(DataAccessor.fieldMap(resource, "labels"));
    }

    private boolean systemLabel(Map<?, ?> labels) {
        Object value = labels.get("io.rancher.container.system");
        return value != null && !"false".equalsIgnoreCase(value.toString()) && !value.toString().isBlank();
    }

    private static boolean flag(Object resource, String name) { return Boolean.TRUE.equals(DataAccessor.field(resource, name, Object.class)); }
    static boolean archived(Object resource) { return resource != null && flag(resource, "archived"); }
    private static boolean terminal(Instance instance) { return instance.getRemoved() != null || instance.getState() != null && Set.of("removed", "purged").contains(instance.getState()); }
    private static boolean transitioning(Object resource) {
        Object value = DataAccessor.field(resource, "transitioning", Object.class);
        return value != null && !Set.of("no", "false", "").contains(value.toString());
    }
    private static void requireStopped(boolean stopped) {
        if (!stopped) throw error(422, "ArchiveRequiresStopped", "Completely stop all services and instances before archiving.");
    }

    private void assertMetadataOnly(Object resource, Map<String, Object> body, String field) {
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (entry.getKey().equals(field)) continue;
            Object old = "archived".equals(entry.getKey()) ? archived(resource) : ObjectUtils.getPropertyIgnoreErrors(resource, entry.getKey());
            if (old == null) old = DataAccessor.field(resource, entry.getKey(), Object.class);
            if (!Objects.equals(old, entry.getValue())) throw error(422, "ArchiveMixedUpdate", "Change archive status separately from other resource settings.");
        }
    }

    private Long parentId(Map<String, Object> body) {
        Object value = body.containsKey("stackId") ? body.get("stackId") : body.get("environmentId");
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        String id = value.toString();
        if (!id.matches("[0-9]+")) id = ApiContext.getContext().getIdFormatter().parseId(id);
        return id == null ? null : Long.valueOf(id);
    }

    private Long stackId(Object resource) {
        if (resource instanceof Stack stack) return stack.getId();
        if (resource instanceof Service service) return service.getStackId();
        return resource instanceof Instance instance ? instance.getStackId() : null;
    }

    protected Set<Long> stackIds(Object resource) {
        Set<Long> ids = new TreeSet<>();
        Long id = stackId(resource);
        if (id != null) ids.add(id);
        if (resource instanceof Instance instance) for (Service service : instances.lifecycleOwners(instance)) {
            if (service.getStackId() != null) ids.add(service.getStackId());
        }
        return ids;
    }

    protected <T> T withLocks(Set<Long> ids, Supplier<T> action) {
        List<Long> ordered = new ArrayList<>(new TreeSet<>(ids));
        return lock(ordered, 0, action);
    }

    private <T> T lock(List<Long> ids, int offset, Supplier<T> action) {
        if (offset == ids.size()) return action.get();
        Long id = ids.get(offset);
        if (held.get().contains(id)) return lock(ids, offset + 1, action);
        return lockManager.lock(new ArchiveLock(id), () -> {
            held.get().add(id);
            try { return lock(ids, offset + 1, action); }
            finally { held.get().remove(id); if (held.get().isEmpty()) held.remove(); }
        });
    }

    private static ClientVisibleException archivedError() { return error(409, "ResourceArchived", "Restore the service and its parent stack before starting or moving it."); }
    private static ClientVisibleException error(int status, String code, String message) { return new ClientVisibleException(status, code, message, null); }

    private static class ArchiveLock extends AbstractLockDefinition implements BlockingLockDefinition {
        ArchiveLock(Long id) { super("archive.stack." + id); }
        @Override public long getWait() { return 3000; }
    }
    private static class InstanceArchiveLock extends AbstractLockDefinition implements BlockingLockDefinition {
        InstanceArchiveLock(Long id) { super("archive.instance." + id); }
        @Override public long getWait() { return 3000; }
    }

    /** Includes old sidekicks, upgrade instances, removing maps and direct ownership columns. */
    @Named
    public static class ArchiveInstances extends AbstractJooqDao {
        public List<? extends Instance> related(Object resource) {
            Long account = resource instanceof Stack stack ? stack.getAccountId() : ((Service) resource).getAccountId();
            Condition related;
            if (resource instanceof Service service) {
                related = INSTANCE.SERVICE_ID.eq(service.getId()).or(INSTANCE.ID.in(create().select(SERVICE_EXPOSE_MAP.INSTANCE_ID)
                        .from(SERVICE_EXPOSE_MAP).where(SERVICE_EXPOSE_MAP.SERVICE_ID.eq(service.getId()))
                        .and(SERVICE_EXPOSE_MAP.ACCOUNT_ID.eq(account)).and(SERVICE_EXPOSE_MAP.REMOVED.isNull())));
            } else {
                Stack stack = (Stack) resource;
                var childIds = create().select(SERVICE.ID).from(SERVICE).where(SERVICE.STACK_ID.eq(stack.getId())).and(SERVICE.ACCOUNT_ID.eq(account));
                related = INSTANCE.STACK_ID.eq(stack.getId()).or(INSTANCE.SERVICE_ID.in(childIds))
                        .or(INSTANCE.ID.in(create().select(SERVICE_EXPOSE_MAP.INSTANCE_ID).from(SERVICE_EXPOSE_MAP)
                                .where(SERVICE_EXPOSE_MAP.SERVICE_ID.in(childIds)).and(SERVICE_EXPOSE_MAP.ACCOUNT_ID.eq(account))
                                .and(SERVICE_EXPOSE_MAP.REMOVED.isNull())));
            }
            return create().selectFrom(INSTANCE).where(INSTANCE.ACCOUNT_ID.eq(account)).and(related).fetchInto(InstanceRecord.class);
        }

        /** Selector references affect archive eligibility, but do not own independent starts. */
        public List<? extends Service> lifecycleOwners(Instance instance) {
            Condition related = SERVICE.ID.eq(instance.getServiceId()).or(SERVICE.ID.in(create().select(SERVICE_EXPOSE_MAP.SERVICE_ID)
                    .from(SERVICE_EXPOSE_MAP).where(SERVICE_EXPOSE_MAP.INSTANCE_ID.eq(instance.getId()))
                    .and(SERVICE_EXPOSE_MAP.ACCOUNT_ID.eq(instance.getAccountId())).and(SERVICE_EXPOSE_MAP.REMOVED.isNull())
                    // Unmanaged upgrade instances remain subject to service cleanup/rollback.
                    .and(SERVICE_EXPOSE_MAP.MANAGED.eq(true).or(SERVICE_EXPOSE_MAP.UPGRADE.eq(true)))));
            return create().selectFrom(SERVICE).where(SERVICE.ACCOUNT_ID.eq(instance.getAccountId())).and(related)
                    .fetchInto(io.cattle.platform.core.model.tables.records.ServiceRecord.class);
        }
    }
}
