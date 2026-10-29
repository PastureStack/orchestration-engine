package io.cattle.platform.iaas.api.auth.change;

import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import static io.cattle.platform.core.model.tables.StackTable.STACK;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.InstanceLink;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.Nic;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.ObjectUtils;
import io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Three reviewed domain adapters using the normal manager and validation chain. */
public class AiResourceChangeDomain extends AbstractJooqDao {
    private static final String DOMAIN = "ai.resource.change.domain.descriptor";
    private static final String PARENT_READ = "ai.resource.change.parent.read";
    @Inject ObjectManager objects;
    @Inject ResourceManagerLocator managers;
    @Inject ApiAuthenticator authenticator;
    @Inject AiKeyAuthorization keys;
    @Inject ApiKeyAuthorizationService keyAccess;
    @Inject ValidationHandler validation;
    @Inject JsonMapper json;
    @Inject ApiKeyTargetResolver targets;
    @Inject ServiceDeploymentReadiness deploymentReadiness;

    /** This marker only exists on a fresh, server-created domain request. */
    public static boolean trustedDomain(ApiRequest request) {
        Object marker = request.getAttribute(DOMAIN);
        if (!(marker instanceof AiResourceChangeDescriptor descriptor) || request.getLink() != null
                || AiResourceChangeDescriptor.require(descriptor.resourceType(), descriptor.operation()) != descriptor) return false;
        boolean parent = Boolean.TRUE.equals(request.getAttribute(PARENT_READ)) && descriptor.createsResource()
                && "service".equals(descriptor.resourceType()) && "stack".equals(request.getType());
        if (!parent && !descriptor.resourceType().equals(request.getType())) return false;
        if ("GET".equals(request.getMethod()) && request.getAction() == null) return true;
        return !parent && descriptor.method().equals(request.getMethod()) && Objects.equals(descriptor.action(), request.getAction())
                && (descriptor.createsResource() ? request.getId() == null : request.getId() != null);
    }

    public Policy actor(ApiRequest request) {
        Policy policy = keys.actor(request);
        Account actor = policy == null ? null : objects.loadResource(Account.class, policy.getAuthenticatedAsAccountId());
        Account account = policy == null ? null : objects.loadResource(Account.class, policy.getAccountId());
        if (actor == null || actor.getRemoved() != null || !"active".equals(actor.getState())
                || account == null || account.getRemoved() != null || !"active".equals(account.getState())
                || !"project".equals(account.getKind())) throw error(403, "PermissionDenied");
        return policy;
    }

    public Object visible(ApiRequest request, AiResourceChangeDescriptor descriptor, String publicId) {
        return read(request, descriptor, descriptor.resourceType(), publicId, false);
    }

    public Object parent(ApiRequest request, AiResourceChangeDescriptor descriptor, Map<String, Object> parameters) {
        return descriptor.createsResource() && "service".equals(descriptor.resourceType())
                ? read(request, descriptor, "stack", (String) parameters.get("stackId"), true) : null;
    }

    private Object read(ApiRequest original, AiResourceChangeDescriptor descriptor, String type, String publicId, boolean parent) {
        ApiRequest request = domain(original, descriptor, type, publicId, "GET", null, new LinkedHashMap<>());
        if (parent) request.setAttribute(PARENT_READ, true);
        return authorized(original, request, () -> {
            Object row = manager(type).getById(type, request.getId(), new ListOptions(request));
            if (row == null || ObjectUtils.getRemoved(row) != null || !Objects.equals(ObjectUtils.getKind(row), type)
                    || !Objects.equals(ObjectUtils.getAccountId(row), ApiUtils.getPolicy().getAccountId())) throw error(404, "NotFound");
            return row;
        });
    }

    public Map<String, Object> prepare(ApiRequest original, AiResourceChangeDescriptor descriptor,
            String publicId, Map<String, Object> parameters) {
        return prepare(original, descriptor, publicId, parameters, null);
    }

    public Map<String, Object> prepare(ApiRequest original, AiResourceChangeDescriptor descriptor,
            String publicId, Map<String, Object> parameters, Map<String, Object> boundPlan) {
        Map<String, Object> body = descriptor.body(parameters);
        ApiRequest request = domain(original, descriptor, descriptor.resourceType(), publicId,
                descriptor.method(), descriptor.action(), body);
        return authorized(original, request, () -> {
            if (boundPlan != null) keys.authorizeBoundDomain(boundPlan, request);
            return copyMap(request.getRequestObject());
        });
    }

    public Object dispatch(ApiRequest original, AiResourceChangeDescriptor descriptor, String publicId,
            Map<String, Object> parameters, String planId) {
        ApiRequest request = domain(original, descriptor, descriptor.resourceType(), publicId,
                descriptor.method(), descriptor.action(), descriptor.body(parameters));
        return authorized(original, request, () -> {
            request.setAttribute("apiKey.audit.requestId", planId);
            ResourceManager manager = manager(descriptor.resourceType());
            Object result = switch (descriptor.method()) {
            case "PUT" -> manager.update(descriptor.resourceType(), request.getId(), request);
            case "DELETE" -> manager.delete(descriptor.resourceType(), request.getId(), request);
            case "POST" -> descriptor.createsResource() ? manager.create(descriptor.resourceType(), request)
                    : manager.resourceAction(descriptor.resourceType(), request);
            default -> throw error(422, "ResourceChangeUnsupported");
            };
            if (result == null) throw error(404, "NotFound");
            return result;
        });
    }

    private ApiRequest domain(ApiRequest original, AiResourceChangeDescriptor descriptor, String type,
            String id, String method, String action, Map<String, Object> body) {
        ApiRequest request = new ApiRequest(original);
        request.setRequestServletContext(original.getServletContext());
        request.setType(type); request.setId(id); request.setMethod(method); request.setAction(action); request.setLink(null);
        request.setRequestObject(body); request.setRequestParams(new LinkedHashMap<>()); request.setConditions(new LinkedHashMap<>());
        request.setPagination(null); request.setSort(null); request.setInclude(null); request.setAttribute(DOMAIN, descriptor);
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(original);
        if (key != null) ApiKeyCredentialContext.attach(request, key);
        return request;
    }

    private <T> T authorized(ApiRequest original, ApiRequest request, Supplier<T> operation) {
        Policy actor = actor(original);
        var current = authenticator.currentAuthorization(actor.getAuthenticatedAsAccountId(), actor.getAccountId(), request);
        request.setSchemaFactory(current.schemas());
        Schema schema = current.schemas().getSchema(request.getType());
        if (schema == null) throw error(403, "PermissionDenied");
        boolean create = "POST".equals(request.getMethod()) && request.getAction() == null;
        boolean update = "PUT".equals(request.getMethod());
        if (create || update) {
            for (String field : copyMap(request.getRequestObject()).keySet()) {
                if ("type".equals(field)) continue;
                var capability = schema.getResourceFields().get(field);
                if (capability == null || create && !capability.isCreate() || update && !capability.isUpdate())
                    throw error(403, "PermissionDenied");
            }
        }
        ApiContext context = ApiContext.getContext();
        if (context == null) throw error(503, "KeyAuthorizationUnavailable");
        ApiRequest previous = context.getApiRequest(); Object previousPolicy = context.getPolicy();
        context.setApiRequest(request); context.setPolicy(current.policy());
        try {
            validation.generate(request);
            keyAccess.authorize(request, current.policy());
            return operation.get();
        } catch (IOException failure) { throw error(422, "ResourceChangeInputInvalid"); }
        finally { context.setApiRequest(previous); context.setPolicy(previousPolicy); }
    }

    private ResourceManager manager(String type) {
        ResourceManager manager = managers.getResourceManagerByType(type);
        if (manager == null) throw error(422, "ResourceChangeUnsupported");
        return manager;
    }

    /** Locking reads share the existing API write transaction with domain dispatch. */
    public Object lock(String type, long id) {
        create().connection(connection -> {
            if (connection.getAutoCommit()) throw error(503, "ResourceChangeTransactionRequired");
        });
        return switch (type) {
        case "stack" -> create().selectFrom(STACK).where(STACK.ID.eq(id)).forUpdate().fetchOne();
        case "service" -> create().selectFrom(SERVICE).where(SERVICE.ID.eq(id)).forUpdate().fetchOne();
        case "container" -> create().selectFrom(INSTANCE).where(INSTANCE.ID.eq(id)).forUpdate().fetchOne();
        default -> throw error(422, "ResourceChangeUnsupported");
        };
    }

    public Map<String, Object> snapshot(Object target) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (target != null) for (String field : List.of("id", "accountId", "kind", "name", "description", "state", "removed",
                "stackId", "startCount", "data")) result.put(field, ObjectUtils.getPropertyIgnoreErrors(target, field));
        if (target instanceof Service service) {
            List<Map<String, Object>> instances = new ArrayList<>();
            for (ServiceExposeMap link : objects.find(ServiceExposeMap.class, "serviceId", service.getId(), "removed", null)) {
                if (!Boolean.TRUE.equals(link.getManaged())) continue;
                Instance instance = objects.loadResource(Instance.class, link.getInstanceId());
                if (instance != null) instances.add(Map.of("id", instance.getId(), "startCount", number(instance.getStartCount())));
            }
            instances.sort(java.util.Comparator.comparingLong(row -> number(row.get("id"))));
            result.put("managedInstances", instances);
        }
        if (target instanceof io.cattle.platform.core.model.Stack stack) {
            List<Map<String, Object>> services = new ArrayList<>();
            for (Service service : objects.find(Service.class, "stackId", stack.getId(), "removed", null)) services.add(snapshot(service));
            services.sort(java.util.Comparator.comparingLong(row -> number(row.get("id"))));
            result.put("childServices", services);
        }
        return copyMap(result);
    }

    public void ready(AiResourceChangeDescriptor descriptor, Object target, Object parent) {
        Object row = descriptor.createsResource() ? parent : target;
        if (row == null && !descriptor.createsResource()) throw error(404, "NotFound");
        if (row == null) return;
        String state = ObjectUtils.getState(row);
        if (!Set.of("active", "inactive", "running", "stopped").contains(state == null ? "" : state))
            throw error(409, "ResourceChangeTargetBusy");
        if (!descriptor.createsResource() && !"stack".equals(descriptor.resourceType())) {
            if ("start".equals(descriptor.operation()) && !("service".equals(descriptor.resourceType()) ? "inactive" : "stopped").equals(state)
                    || Set.of("stop", "restart", "scale").contains(descriptor.operation())
                    && !("service".equals(descriptor.resourceType()) ? "active" : "running").equals(state))
                throw error(409, "ResourceChangeTargetBusy");
        }
        if ("scale".equals(descriptor.operation()) && !deploymentReadiness.supportsManualScale((Service) target))
            throw error(422, "ResourceChangeUnsupported");
    }

    public String publicId(String type, Object id) {
        return id == null ? null : String.valueOf(ApiContext.getContext().getIdFormatter().formatId(type, id));
    }

    public String targetPath(ApiRequest request, Object target, Object parent, Map<String, Object> parameters) {
        Policy actor = actor(request); Account project = objects.loadResource(Account.class, actor.getAccountId());
        String name = target == null ? String.valueOf(parameters.get("name")) : String.valueOf(ObjectUtils.getPropertyIgnoreErrors(target, "name"));
        return Objects.toString(project.getName(), "") + " / "
                + (parent == null ? "" : Objects.toString(ObjectUtils.getPropertyIgnoreErrors(parent, "name"), "") + " / ") + name;
    }

    public Object load(String type, long id) { return objects.loadResource(type, id); }

    public long internalId(String type, String id) { return targets.parseScopeId(type, id); }

    /** Removal receipts outlive their resource. This never returns the removed object to a client. */
    public Object receiptTarget(ApiRequest request, AiResourceChangeDescriptor descriptor, Map<String, Object> plan) {
        Object target = load(descriptor.resourceType(), number(plan.get("internalResourceId")));
        Policy actor = actor(request);
        ApiRequest domain = domain(request, descriptor, descriptor.resourceType(), (String) plan.get("resourceId"), "GET", null, new LinkedHashMap<>());
        var current = authenticator.currentAuthorization(actor.getAuthenticatedAsAccountId(), actor.getAccountId(), domain);
        Schema schema = current.schemas().getSchema(descriptor.resourceType());
        if (schema == null || schema.getResourceMethods() == null || !schema.getResourceMethods().contains("GET")) throw error(403, "PermissionDenied");
        if (target == null || !Objects.equals(ObjectUtils.getAccountId(target), actor.getAccountId())
                || !Objects.equals(ObjectUtils.getKind(target), descriptor.resourceType()) || current.policy().authorizeObject(target) == null)
            throw error(403, "PermissionDenied");
        if (ObjectUtils.getRemoved(target) == null) visible(request, descriptor, (String) plan.get("resourceId"));
        return target;
    }

    public boolean complete(AiResourceChangeDescriptor descriptor, Object target, Map<String, Object> parameters,
            Map<String, Object> before) {
        if (target == null) return false;
        String state = ObjectUtils.getState(target);
        if ("remove".equals(descriptor.operation())) return ObjectUtils.getRemoved(target) != null
                && Set.of("removed", "purged").contains(state) && removalSettled(descriptor.resourceType(), target)
                && priorInstancesRemoved(before);
        if (ObjectUtils.getRemoved(target) != null) return false;
        if ("update_metadata".equals(descriptor.operation())) return parameters.entrySet().stream()
                .allMatch(entry -> Objects.equals(entry.getValue(), ObjectUtils.getPropertyIgnoreErrors(target, entry.getKey())));
        if ("scale".equals(descriptor.operation())) return "active".equals(state)
                && Objects.equals(parameters.get("scale"), DataAccessor.fieldInteger(target, "scale")) && serviceReady((Service) target, true)
                && retiredInstancesSettled((Service) target, before);
        if ("create".equals(descriptor.operation())) return "container".equals(descriptor.resourceType()) ? "stopped".equals(state)
                : "stack".equals(descriptor.resourceType()) ? Set.of("active", "inactive").contains(state) : "inactive".equals(state);
        if ("container".equals(descriptor.resourceType())) return "stop".equals(descriptor.operation()) ? "stopped".equals(state)
                : "running".equals(state) && (!"restart".equals(descriptor.operation())
                    || number(ObjectUtils.getPropertyIgnoreErrors(target, "startCount")) > number(before.get("startCount")));
        if ("service".equals(descriptor.resourceType())) return "stop".equals(descriptor.operation())
                ? "inactive".equals(state) && serviceReady((Service) target, false) && priorInstancesStopped((Service) target, before)
                : "active".equals(state) && serviceReady((Service) target, true)
                    && retiredInstancesSettled((Service) target, before)
                    && (!"restart".equals(descriptor.operation()) || restarted((Service) target, before));
        boolean start = "start".equals(descriptor.operation());
        List<? extends Service> children = objects.find(Service.class, "stackId", ObjectUtils.getId(target), "removed", null);
        Set<Long> expected = new java.util.HashSet<>();
        if (!(before.get("childServices") instanceof List<?> prior)) return false;
        for (Object value : prior) {
            if (!(value instanceof Map<?, ?> row)) return false;
            expected.add(number(row.get("id")));
        }
        Set<Long> actual = new java.util.HashSet<>(); for (Service child : children) actual.add(child.getId());
        if (!expected.equals(actual)) return false;
        for (Service service : children) {
            if (!(start ? "active" : "inactive").equals(service.getState()) || !serviceReady(service, start)) return false;
            for (Object value : prior) {
                Map<?, ?> row = (Map<?, ?>) value;
                if (number(row.get("id")) == service.getId()) {
                    Map<String, Object> child = new LinkedHashMap<>();
                    for (var entry : row.entrySet()) if (entry.getKey() instanceof String key) child.put(key, entry.getValue());
                    if (start ? !retiredInstancesSettled(service, child) : !priorInstancesStopped(service, child)) return false;
                }
            }
        }
        return "active".equals(state) || "inactive".equals(state);
    }

    private boolean restarted(Service service, Map<String, Object> before) {
        if (!(before.get("managedInstances") instanceof List<?> prior) || prior.isEmpty()) return false;
        for (Object item : prior) {
            if (!(item instanceof Map<?, ?> row)) return false;
            Instance instance = objects.loadResource(Instance.class, number(row.get("id")));
            if (instance != null && instance.getRemoved() == null && number(instance.getStartCount()) <= number(row.get("startCount"))) return false;
        }
        return true;
    }

    private boolean priorInstancesRemoved(Map<String, Object> before) {
        if (before.get("managedInstances") instanceof List<?> prior) for (Object value : prior) {
            if (!(value instanceof Map<?, ?> row)) return false;
            long id = number(row.get("id"));
            Instance instance = objects.loadResource(Instance.class, id);
            if (instance != null && (instance.getRemoved() == null || !Set.of("removed", "purged").contains(instance.getState()))) return false;
            if (!attachmentsGone(id)) return false;
        }
        if (before.get("childServices") instanceof List<?> services) for (Object value : services) {
            if (!(value instanceof Map<?, ?> row)) return false;
            Service service = objects.loadResource(Service.class, number(row.get("id")));
            if (service != null && (service.getRemoved() == null || !Set.of("removed", "purged").contains(service.getState()))) return false;
            Map<String, Object> child = new LinkedHashMap<>();
            for (var entry : row.entrySet()) if (entry.getKey() instanceof String key) child.put(key, entry.getValue());
            if (!priorInstancesRemoved(child)) return false;
        }
        return true;
    }

    private boolean serviceReady(Service service, boolean running) {
        if (running) return deploymentReadiness.isReady(service);
        for (ServiceExposeMap link : objects.find(ServiceExposeMap.class, "serviceId", service.getId(), "removed", null)) {
            if (!Boolean.TRUE.equals(link.getManaged())) continue;
            Instance instance = objects.loadResource(Instance.class, link.getInstanceId());
            if (instance == null || !Objects.equals(instance.getAccountId(), service.getAccountId())) return false;
            if (instance.getRemoved() == null && !"stopped".equals(instance.getState())) return false;
        }
        return true;
    }

    private boolean retiredInstancesSettled(Service service, Map<String, Object> before) {
        if (!(before.get("managedInstances") instanceof List<?> prior)) return true;
        Set<Long> current = new java.util.HashSet<>();
        for (ServiceExposeMap link : objects.find(ServiceExposeMap.class, "serviceId", service.getId(), "removed", null))
            if (Boolean.TRUE.equals(link.getManaged())) current.add(link.getInstanceId());
        for (Object value : prior) {
            if (!(value instanceof Map<?, ?> row)) return false;
            long id = number(row.get("id"));
            Instance instance = objects.loadResource(Instance.class, id);
            if (instance != null && !Objects.equals(instance.getAccountId(), service.getAccountId())) return false;
            if (current.contains(id) && instance != null && instance.getRemoved() == null) continue;
            if (instance != null && (instance.getRemoved() == null || !Set.of("removed", "purged").contains(instance.getState()))) return false;
            if (!attachmentsGone(id)) return false;
        }
        return true;
    }

    private boolean priorInstancesStopped(Service service, Map<String, Object> before) {
        if (!(before.get("managedInstances") instanceof List<?> prior)) return true;
        for (Object value : prior) {
            if (!(value instanceof Map<?, ?> row)) return false;
            long id = number(row.get("id"));
            Instance instance = objects.loadResource(Instance.class, id);
            if (instance != null && !Objects.equals(instance.getAccountId(), service.getAccountId())) return false;
            if (instance != null && instance.getRemoved() == null && "stopped".equals(instance.getState())) continue;
            if (instance != null && (instance.getRemoved() == null || !Set.of("removed", "purged").contains(instance.getState()))) return false;
            if (!attachmentsGone(id)) return false;
        }
        return true;
    }

    private boolean removalSettled(String type, Object target) {
        long id = number(ObjectUtils.getId(target));
        if ("container".equals(type)) return attachmentsGone(id);
        if ("service".equals(type)) return objects.find(ServiceExposeMap.class, "serviceId", id, "removed", null).isEmpty();
        return objects.find(Service.class, "stackId", id, "removed", null).isEmpty();
    }

    private boolean attachmentsGone(long id) {
        if (!objects.find(ServiceExposeMap.class, "instanceId", id, "removed", null).isEmpty()
                    || !objects.find(InstanceHostMap.class, "instanceId", id, "removed", null).isEmpty()
                    || !objects.find(Port.class, "instanceId", id, "removed", null).isEmpty()
                    || !objects.find(Nic.class, "instanceId", id, "removed", null).isEmpty()
                || !objects.find(InstanceLink.class, "instanceId", id, "removed", null).isEmpty()) return false;
        for (Mount mount : objects.find(Mount.class, "instanceId", id, "removed", null))
            if (!Set.of("inactive", "removed").contains(String.valueOf(mount.getState()))) return false;
        return true;
    }

    public List<Map<String, Object>> changes(AiResourceChangeDescriptor descriptor, Object target, Map<String, Object> parameters) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (parameters.isEmpty()) {
            if ("restart".equals(descriptor.operation())) result.add(change("operation", null, "restart"));
            else if ("stack".equals(descriptor.resourceType()) && Set.of("start", "stop").contains(descriptor.operation())) {
                List<Service> services = objects.find(Service.class, "stackId", ObjectUtils.getId(target), "removed", null);
                Set<String> states = new java.util.HashSet<>(); for (Service service : services) states.add(service.getState());
                String before = states.isEmpty() ? "empty" : states.size() == 1 ? states.iterator().next() : "mixed";
                result.add(change("childServiceState", before, "start".equals(descriptor.operation()) ? "active" : "inactive"));
            } else {
                String after = "remove".equals(descriptor.operation()) ? "removed" : "container".equals(descriptor.resourceType())
                        ? "start".equals(descriptor.operation()) ? "running" : "stopped"
                        : "start".equals(descriptor.operation()) ? "active" : "inactive";
                result.add(change("state", target == null ? null : ObjectUtils.getState(target), after));
            }
        }
        if ("stack".equals(descriptor.resourceType()) && Set.of("start", "stop").contains(descriptor.operation())) {
            int count = objects.find(Service.class, "stackId", ObjectUtils.getId(target), "removed", null).size();
            result.add(change("affectedServiceCount", count, count));
            result.add(change("emptyStack", count == 0, count == 0));
        }
        if ("remove".equals(descriptor.operation())) {
            List<? extends Service> services = "stack".equals(descriptor.resourceType())
                    ? objects.find(Service.class, "stackId", ObjectUtils.getId(target), "removed", null)
                    : target instanceof Service service ? List.of(service) : List.of();
            Set<Long> instances = new java.util.HashSet<>();
            if (target instanceof Instance instance) instances.add(instance.getId());
            for (Service service : services) for (ServiceExposeMap link : objects.find(ServiceExposeMap.class, "serviceId", service.getId(), "removed", null))
                if (Boolean.TRUE.equals(link.getManaged())) instances.add(link.getInstanceId());
            int mounts = 0; for (Long id : instances) mounts += objects.find(Mount.class, "instanceId", id, "removed", null).size();
            result.add(change("affectedServiceCount", services.size(), 0));
            result.add(change("affectedInstanceCount", instances.size(), 0)); result.add(change("affectedMountCount", mounts, null));
            result.add(change("mountedDataRule", null, "EXISTING_DOMAIN_RETENTION_NO_ADDITIONAL_PURGE"));
        }
        for (var entry : parameters.entrySet()) result.add(change(entry.getKey(), target == null ? null
                : "scale".equals(entry.getKey()) ? DataAccessor.fieldInteger(target, "scale")
                : ObjectUtils.getPropertyIgnoreErrors(target, entry.getKey()), displayed(entry.getKey(), entry.getValue())));
        if (descriptor.createsResource() && !"stack".equals(descriptor.resourceType())) result.add(change("startOnCreate", null, false));
        return result;
    }

    private Object displayed(String field, Object value) {
        if (!Set.of("command", "entryPoint").contains(field)) return value;
        try { return json.writeValueAsString(value); }
        catch (IOException failure) { throw new IllegalStateException("Resource-change summary unavailable", failure); }
    }

    private Map<String, Object> change(String field, Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>(); change.put("field", field);
        change.put("before", scalar(before)); change.put("after", scalar(after)); return change;
    }

    private Object scalar(Object value) { return value == null || value instanceof String || value instanceof Number || value instanceof Boolean ?
            value : null; }

    private Map<String, Object> copyMap(Object value) {
        try { return json.readValue(json.writeValueAsString(value)); }
        catch (IOException failure) { throw new IllegalStateException("Resource-change data unavailable", failure); }
    }

    static long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }
    static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
