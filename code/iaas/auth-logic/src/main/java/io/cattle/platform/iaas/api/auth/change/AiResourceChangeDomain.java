package io.cattle.platform.iaas.api.auth.change;

import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.HostTable.HOST;
import static io.cattle.platform.core.model.tables.AccountTable.ACCOUNT;
import static io.cattle.platform.core.model.tables.CertificateTable.CERTIFICATE;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import static io.cattle.platform.core.model.tables.StackTable.STACK;
import static io.cattle.platform.core.model.tables.ProcessInstanceTable.PROCESS_INSTANCE;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Certificate;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.InstanceLink;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.Nic;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.core.model.ProcessInstance;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.TransactionDefinition;
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
import io.cattle.platform.servicediscovery.api.service.ServiceCreateValidation;
import io.cattle.platform.iaas.api.port.InstanceCreatePortValidation;
import io.cattle.platform.servicediscovery.api.service.ResourceArchiveChange;
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
    private static final String LB_CREATE_REFERENCE = "ai.resource.change.lb.create.reference";
    private record LoadBalancerReference(ApiRequest request, String type, String publicId, String nativeId) { }
    private static final String ALIAS_REFERENCE = "ai.resource.change.alias.reference";
    private static final String LB_METADATA_STACK = "ai.resource.change.lb.metadata.stack";
    private static final String UPGRADE_LIFECYCLE_STACK = "ai.resource.change.upgrade.lifecycle.stack";
    private record MetadataStackRead(ApiRequest request, String publicId, String nativeId) { }
    private record AliasReference(String type, String publicId, String nativeId) { }
    @Inject ObjectManager objects;
    @Inject AiResourceChangeProcessObserver processObserver;
    @Inject ResourceManagerLocator managers;
    @Inject ApiAuthenticator authenticator;
    @Inject AiKeyAuthorization keys;
    @Inject AiProjectMetadataChange projectMetadata;
    @Inject ApiKeyAuthorizationService keyAccess;
    @Inject ValidationHandler validation;
    @Inject JsonMapper json;
    @Inject ApiKeyTargetResolver targets;
    @Inject ServiceDeploymentReadiness deploymentReadiness;
    @Inject ServiceCreateValidation serviceCreateValidation;
    @Inject InstanceCreatePortValidation instanceCreatePortValidation;
    @Inject ResourceArchiveChange archiveChanges;

    /** This marker only exists on a fresh, server-created domain request. */
    public static boolean trustedDomain(ApiRequest request) {
        Object marker = request.getAttribute(DOMAIN);
        if (!(marker instanceof AiResourceChangeDescriptor descriptor) || request.getLink() != null
                || AiResourceChangeDescriptor.require(descriptor.resourceType(), descriptor.operation()) != descriptor) return false;
        boolean parent = Boolean.TRUE.equals(request.getAttribute(PARENT_READ)) && descriptor.requiresStackParent() && "stack".equals(request.getType());
        if (("dnsService".equals(descriptor.resourceType()) || AiAliasServiceChange.ordinaryLinks(descriptor))
                && "GET".equals(request.getMethod()) && request.getAction() == null
                && request.getAttribute(ALIAS_REFERENCE) instanceof AliasReference reference
                && Objects.equals(reference.type(), request.getType()) && (Objects.equals(reference.publicId(), request.getId())
                        || Objects.equals(reference.nativeId(), request.getId()))) return true;
        if (loadBalancerOwned(descriptor) && request.getAttribute(LB_METADATA_STACK) instanceof MetadataStackRead scope
                && scope.request() == request && "stack".equals(request.getType()) && "GET".equals(request.getMethod())
                && request.getAction() == null && (Objects.equals(scope.publicId(), request.getId())
                        || Objects.equals(scope.nativeId(), request.getId()))) return true;
        if (AiServiceUpgradeLifecycleChange.supports(descriptor) && request.getAttribute(UPGRADE_LIFECYCLE_STACK) instanceof MetadataStackRead scope
                && scope.request() == request && "stack".equals(request.getType()) && "GET".equals(request.getMethod())
                && request.getAction() == null && (Objects.equals(scope.publicId(), request.getId())
                        || Objects.equals(scope.nativeId(), request.getId()))) return true;
        if (AiLoadBalancerCreateChange.supports(descriptor) && "GET".equals(request.getMethod()) && request.getAction() == null
                && request.getAttribute(LB_CREATE_REFERENCE) instanceof LoadBalancerReference reference
                && reference.request() == request && Set.of("service", "externalService").contains(reference.type())
                && reference.type().equals(request.getType()) && (reference.publicId().equals(request.getId())
                        || reference.nativeId().equals(request.getId()))) return true;
        if (!parent && !descriptor.resourceType().equals(request.getType())) return false;
        if ("GET".equals(request.getMethod()) && request.getAction() == null) return true;
        return !parent && descriptor.method().equals(request.getMethod()) && Objects.equals(descriptor.action(), request.getAction())
                && (descriptor.createsResource() ? request.getId() == null : request.getId() != null);
    }

    public static boolean trustedOrdinaryLinks(ApiRequest request) {
        return request.getAttribute(DOMAIN) instanceof AiResourceChangeDescriptor descriptor
                && AiAliasServiceChange.ordinaryLinks(descriptor) && "POST".equals(request.getMethod())
                && "setservicelinks".equals(request.getAction()) && trustedDomain(request);
    }

    public Policy actor(ApiRequest request) {
        Policy policy = keys.actor(request);
        Account actor = policy == null ? null : objects.loadResource(Account.class, policy.getAuthenticatedAsAccountId());
        Account account = policy == null ? null : objects.loadResource(Account.class, policy.getAccountId());
        if (actor == null || actor.getRemoved() != null || !"active".equals(actor.getState())
                || account == null || account.getRemoved() != null
                || !("active".equals(account.getState()) || "updating-active".equals(account.getState()))
                || !"project".equals(account.getKind())) throw error(403, "PermissionDenied");
        return policy;
    }

    public Object visible(ApiRequest request, AiResourceChangeDescriptor descriptor, String publicId) {
        return read(request, descriptor, descriptor.resourceType(), publicId, false);
    }

    public Object parent(ApiRequest request, AiResourceChangeDescriptor descriptor, Map<String, Object> parameters) {
        return descriptor.requiresStackParent()
                ? read(request, descriptor, "stack", (String) parameters.get("stackId"), true) : null;
    }

    private Object read(ApiRequest original, AiResourceChangeDescriptor descriptor, String type, String publicId, boolean parent) {
        ApiRequest request = domain(original, descriptor, type, publicId, "GET", null, new LinkedHashMap<>());
        if (parent && loadBalancerOwned(descriptor))
            request.setAttribute(LB_METADATA_STACK, new MetadataStackRead(request, publicId, Long.toString(internalId("stack", publicId))));
        else if (parent && AiServiceUpgradeLifecycleChange.supports(descriptor))
            request.setAttribute(UPGRADE_LIFECYCLE_STACK, new MetadataStackRead(request, publicId, Long.toString(internalId("stack", publicId))));
        else if (parent) request.setAttribute(PARENT_READ, true);
        if ("dnsService".equals(descriptor.resourceType()) && !"dnsService".equals(type) || AiAliasServiceChange.ordinaryLinks(descriptor))
            request.setAttribute(ALIAS_REFERENCE, new AliasReference(type, publicId, Long.toString(internalId(type, publicId))));
        if (AiLoadBalancerCreateChange.supports(descriptor) && Set.of("service", "externalService").contains(type))
            request.setAttribute(LB_CREATE_REFERENCE, new LoadBalancerReference(request, type, publicId, Long.toString(internalId(type, publicId))));
        long selectedProject = "project".equals(type) ? actor(original).getAccountId() : -1;
        return authorized(original, request, () -> {
            Object row = manager(type).getById(type, request.getId(), new ListOptions(request));
            if ("project".equals(type)) return AiProjectMetadataChange.selectedReadable(row, selectedProject);
            if (row == null || ObjectUtils.getRemoved(row) != null || !matchesResourceType(type, row)
                    || !Objects.equals(ObjectUtils.getAccountId(row), ApiUtils.getPolicy().getAccountId())) throw error(404, "NotFound");
            if (Set.of("registry", "volume").contains(type) && !Objects.equals(ObjectUtils.getId(row), internalId(type, publicId)))
                throw error(404, "NotFound");
            return row;
        });
    }

    private static boolean matchesResourceType(String type, Object target) {
        // A Host's kind identifies its native driver (for example docker), not the API resource type.
        return "volume".equals(type) ? target instanceof io.cattle.platform.core.model.Volume
                : "host".equals(type) ? target instanceof Host : "project".equals(type) ? target instanceof Account account
                && "project".equals(account.getKind()) : Objects.equals(ObjectUtils.getKind(target), type);
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
            if (loadBalancerOwned(descriptor)) {
                Map<String, Object> context = loadBalancerMetadataContext(original, descriptor, publicId);
                if (boundPlan != null && !context.equals(boundPlan.get("referenceSnapshot"))) throw error(409, "ResourceChangePlanStale");
            }
            if (AiServiceUpgradeLifecycleChange.supports(descriptor)) {
                Map<String, Object> context = upgradeLifecycleContext(original, descriptor, publicId);
                if (boundPlan != null && !context.equals(boundPlan.get("referenceSnapshot"))) throw error(409, "ResourceChangePlanStale");
            }
            if (AiLoadBalancerCreateChange.supports(descriptor)) {
                Map<String, Object> references = loadBalancerCreateReferences(original, descriptor, parameters);
                if (boundPlan != null && !references.equals(boundPlan.get("referenceSnapshot"))) throw error(409, "ResourceChangePlanStale");
                validateLoadBalancerCreate(descriptor, parameters, request);
            }
            if (AiVolumeCreateChange.supports(descriptor)) {
                Map<String, Object> scope = volumeCreateScope(original);
                if (boundPlan != null && !scope.equals(boundPlan.get("referenceSnapshot"))) throw error(409, "ResourceChangePlanStale");
            }
            validateExternalHealthCheck(descriptor, parameters, request);
            if (descriptor.createsResource() && "service".equals(descriptor.resourceType())) {
                Map<String, Object> validated = copyMap(request.getRequestObject());
                if (!(validated.get("launchConfig") instanceof Map<?, ?> launch)) throw error(403, "PermissionDenied");
                for (String field : List.of("ports", "healthCheck"))
                    if (parameters.containsKey(field) && !Objects.equals(parameters.get(field), launch.get(field)))
                        throw error(403, "PermissionDenied");
                serviceCreateValidation.validatePorts(request);
            }
            if (descriptor.createsResource() && "container".equals(descriptor.resourceType()) && parameters.containsKey("ports")) {
                Map<String, Object> validated = copyMap(request.getRequestObject());
                if (!Objects.equals(parameters.get("ports"), validated.get("ports"))) throw error(403, "PermissionDenied");
                if (instanceCreatePortValidation == null) throw error(503, "ResourceChangePreflightUnavailable");
                instanceCreatePortValidation.validateCreatePorts(request);
            }
            if (AiAliasServiceChange.referenceOperation(descriptor)) {
                Map<String, Object> references = aliasReferenceSnapshot(original, descriptor, publicId, parameters);
                if (AiAliasServiceChange.changesTargets(descriptor) && publicId != null && aliasReferences(original, descriptor, parameters).stream()
                        .anyMatch(ref -> number(ref.get("id")) == internalId(descriptor.resourceType(), publicId)))
                    throw error(422, "ResourceChangeInputInvalid");
                if (boundPlan != null && !references.equals(boundPlan.get("referenceSnapshot"))) throw error(409, "ResourceChangePlanStale");
            }
            return copyMap(request.getRequestObject());
        });
    }

    public Map<String, Object> volumeCreateScope(ApiRequest request) {
        Account project = objects.loadResource(Account.class, actor(request).getAccountId());
        if (project == null || project.getUuid() == null || project.getUuid().isBlank()) throw error(404, "NotFound");
        if (!objects.find(io.cattle.platform.core.model.StorageDriver.class, "accountId", project.getId(), "name", "local", "removed", null).isEmpty())
            throw error(422, "ResourceChangeUnsupported");
        Map<String, Object> scope = new LinkedHashMap<>();
        for (String field : List.of("id", "uuid", "kind", "name", "state")) scope.put(field, ObjectUtils.getPropertyIgnoreErrors(project, field));
        return copyMap(scope);
    }

    public Map<String, Object> loadBalancerCreateParentSnapshot(Object parent) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "uuid", "accountId", "kind", "name", "state", "removed"))
            result.put(field, ObjectUtils.getPropertyIgnoreErrors(parent, field));
        result.put("archived", Boolean.TRUE.equals(DataAccessor.field(parent, "archived", Object.class)));
        return copyMap(result);
    }

    public Map<String, Object> loadBalancerCreateReferences(ApiRequest original, AiResourceChangeDescriptor descriptor,
            Map<String, Object> parameters) {
        if (!AiLoadBalancerCreateChange.supports(descriptor)) return Map.of();
        Object destination = parent(original, descriptor, parameters);
        if (!(destination instanceof io.cattle.platform.core.model.Stack destinationStack) || destinationStack.getUuid() == null)
            throw error(404, "NotFound");
        Map<Long, Map<String, Object>> references = new LinkedHashMap<>();
        for (Map<String, Object> rule : AiLoadBalancerCreateChange.rules(parameters.get("portRules"))) {
            long id = internalId("service", (String) rule.get("serviceId"));
            if (references.containsKey(id)) continue;
            Service candidate = objects.loadResource(Service.class, id);
            if (candidate == null || !Set.of("service", "externalService").contains(candidate.getKind())) throw error(404, "NotFound");
            Service visible = (Service) read(original, descriptor, candidate.getKind(), publicId(candidate.getKind(), id), false);
            if (visible.getUuid() == null || visible.getStackId() == null || !Set.of("active", "inactive").contains(visible.getState())
                    || "externalService".equals(visible.getKind()) && DataAccessor.fieldString(visible, "hostname") != null)
                throw error(422, "ResourceChangeUnsupported");
            var stack = (io.cattle.platform.core.model.Stack) read(original, descriptor, "stack", publicId("stack", visible.getStackId()), true);
            if (stack.getUuid() == null) throw error(404, "NotFound");
            Map<String, Object> pin = new LinkedHashMap<>();
            for (String field : List.of("id", "uuid", "accountId", "stackId", "kind")) pin.put(field, ObjectUtils.getPropertyIgnoreErrors(visible, field));
            pin.put("stackUuid", stack.getUuid()); pin.put("path", Objects.toString(stack.getName(), "") + " / " + Objects.toString(visible.getName(), ""));
            references.put(id, pin);
        }
        return copyMap(Map.of("targets", new ArrayList<>(references.values())));
    }

    public List<Map<String, Object>> aliasReferences(ApiRequest original, AiResourceChangeDescriptor descriptor, Map<String, Object> parameters) {
        if (!AiAliasServiceChange.changesTargets(descriptor)) return List.of();
        if (!(parameters.get("serviceIds") instanceof List<?> selected) || selected.size() > AiAliasServiceChange.MAX_TARGETS)
            throw error(422, "ResourceChangeUnsupported");
        List<Map<String, Object>> result = new ArrayList<>(); Set<Long> ids = new java.util.LinkedHashSet<>();
        for (Object id : (List<?>) parameters.get("serviceIds")) {
            long internal = internalId("service", (String) id); Service candidate = objects.loadResource(Service.class, internal);
            if (candidate == null || !Set.of("service", "dnsService", "externalService", "loadBalancerService").contains(candidate.getKind())
                    || AiAliasServiceChange.ordinaryLinks(descriptor) && !"service".equals(candidate.getKind()))
                throw error(404, "NotFound");
            Service visible = (Service) read(original, descriptor, candidate.getKind(), publicId(candidate.getKind(), internal), false);
            if (visible.getStackId() == null || visible.getUuid() == null || !ids.add(internal)
                    || AiAliasServiceChange.ordinaryLinks(descriptor) && (AiAliasServiceChange.nativeId(visible.getId()) != internal
                        || AiAliasServiceChange.nativeId(visible.getStackId()) < 1)
                    || !Objects.equals(visible.getAccountId(), actor(original).getAccountId())
                    || AiAliasServiceChange.ordinaryLinks(descriptor) && !"service".equals(visible.getKind())) throw error(422, "ResourceChangeInputInvalid");
            var stack = (io.cattle.platform.core.model.Stack) read(original, descriptor, "stack", publicId("stack", visible.getStackId()), true);
            if (stack.getUuid() == null) throw error(404, "NotFound");
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("id", internal); snapshot.put("uuid", visible.getUuid()); snapshot.put("accountId", visible.getAccountId());
            snapshot.put("stackId", visible.getStackId()); snapshot.put("stackUuid", stack.getUuid()); snapshot.put("kind", visible.getKind());
            snapshot.put("path", Objects.toString(stack.getName(), "") + " / " + Objects.toString(visible.getName(), "")); result.add(snapshot);
        }
        return (List<Map<String, Object>>) (List<?>) copyMap(Map.of("references", result)).get("references");
    }

    public Map<String, Object> aliasReferenceSnapshot(ApiRequest original, AiResourceChangeDescriptor descriptor,
            String publicId, Map<String, Object> parameters) {
        if (!AiAliasServiceChange.referenceOperation(descriptor)) return Map.of();
        Service service = objects.loadResource(Service.class, internalId(descriptor.resourceType(), publicId));
        if (service == null || !descriptor.resourceType().equals(service.getKind()) || !Objects.equals(service.getAccountId(), actor(original).getAccountId()))
            throw error(404, "NotFound");
        List<String> ids = new ArrayList<>();
        for (ServiceConsumeMap link : objects.find(ServiceConsumeMap.class, "serviceId", service.getId(), "removed", null)) {
            if (!AiAliasServiceChange.simpleLink(link) || !Objects.equals(link.getAccountId(), service.getAccountId()))
                throw error(422, "ResourceChangeUnsupported");
            ids.add(publicId("service", link.getConsumedServiceId()));
        }
        if (ids.size() > AiAliasServiceChange.MAX_TARGETS) throw error(422, "ResourceChangeUnsupported");
        if (AiAliasServiceChange.ordinaryLinks(descriptor)) ids.sort(java.util.Comparator.comparingLong(id -> internalId("service", id)));
        var reference = AiResourceChangeDescriptor.require(descriptor.resourceType(),
                AiAliasServiceChange.ordinaryLinks(descriptor) ? "update_links" : "update_targets");
        if (AiAliasServiceChange.ordinaryLinks(descriptor)) {
            if (service.getStackId() == null || service.getUuid() == null) throw error(422, "ResourceChangeUnsupported");
            read(original, reference, "stack", publicId("stack", service.getStackId()), true);
        }
        return copyMap(Map.of("before", aliasReferences(original, reference, Map.of("serviceIds", ids)),
                "after", AiAliasServiceChange.changesTargets(descriptor) ? aliasReferences(original, reference, parameters) : List.of()));
    }

    /** Scalar metadata only. Hidden targets never contribute a display name or an identifier. */
    public Map<String, Object> aliasViewTargets(ApiRequest request, String id, boolean detail) {
        Service service = objects.loadResource(Service.class, internalId("dnsService", id));
        if (service == null || !"dnsService".equals(service.getKind()) || !Objects.equals(service.getAccountId(), actor(request).getAccountId()))
            throw error(404, "NotFound");
        int count = objects.find(ServiceConsumeMap.class, "serviceId", service.getId(), "removed", null).size();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("linkedServiceCount", count); result.put("unresolvedTargetCount", count); result.put("targetServices", "[]");
        if (!detail || count > AiAliasServiceChange.MAX_TARGETS) return result;
        try {
            Map<String, Object> references = aliasReferenceSnapshot(request, AiResourceChangeDescriptor.require("dnsService", "update_targets"),
                    id, Map.of("serviceIds", List.of()));
            List<String> names = new ArrayList<>();
            for (Object value : (List<?>) references.get("before")) names.add((String) ((Map<?, ?>) value).get("path"));
            result.put("targetServices", displayed("targetServices", names)); result.put("unresolvedTargetCount", 0);
        } catch (ClientVisibleException denied) {
            if (!Set.of(403, 404, 422).contains(denied.getStatus())) throw denied;
        }
        return result;
    }

    public Object dispatch(ApiRequest original, AiResourceChangeDescriptor descriptor, String publicId,
            Map<String, Object> parameters, String planId) {
        ApiRequest request = domain(original, descriptor, descriptor.resourceType(), publicId,
                descriptor.method(), descriptor.action(), descriptor.body(parameters));
        return authorized(original, request, () -> {
            validateExternalHealthCheck(descriptor, parameters, request);
            if (AiLoadBalancerCreateChange.supports(descriptor)) {
                validateLoadBalancerCreate(descriptor, parameters, request);
                loadBalancerCreateReferences(original, descriptor, parameters);
            }
            if (loadBalancerOwned(descriptor)) loadBalancerStack(original, descriptor, publicId);
            if (AiServiceUpgradeLifecycleChange.supports(descriptor)) upgradeLifecycleStack(original, descriptor, publicId);
            request.setAttribute("apiKey.audit.requestId", planId);
            return processObserver.dispatch(request, planId, () -> {
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
        });
    }

    private void validateLoadBalancerCreate(AiResourceChangeDescriptor descriptor, Map<String, Object> parameters, ApiRequest request) {
        if (!AiLoadBalancerCreateChange.submittedMatches(descriptor.body(parameters), copyMap(request.getRequestObject()),
                id -> internalId("service", id), id -> internalId("stack", id))) throw error(403, "PermissionDenied");
        serviceCreateValidation.validatePorts(request);
    }

    private void validateExternalHealthCheck(AiResourceChangeDescriptor descriptor, Map<String, Object> parameters, ApiRequest request) {
        if (descriptor.createsResource() && "externalService".equals(descriptor.resourceType())
                && !AiExternalServiceChange.healthCheckMatches(parameters.get("healthCheck"),
                        copyMap(request.getRequestObject()).get("healthCheck"))) throw error(403, "PermissionDenied");
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
        boolean project = "project".equals(request.getType())
                && request.getAttribute(DOMAIN) instanceof AiResourceChangeDescriptor descriptor
                && "project".equals(descriptor.resourceType()) && "update_metadata".equals(descriptor.operation());
        if (project && internalId("project", request.getId()) != actor.getAccountId()) throw error(404, "NotFound");
        var current = authenticator.currentAuthorization(actor.getAuthenticatedAsAccountId(),
                project ? actor.getAuthenticatedAsAccountId() : actor.getAccountId(), request);
        request.setSchemaFactory(current.schemas());
        Schema schema = current.schemas().getSchema(request.getType());
        if (schema == null) throw error(403, "PermissionDenied");
        if (project) {
            Object selected = objects.loadResource(Account.class, actor.getAccountId());
            Account account = "GET".equals(request.getMethod()) ? AiProjectMetadataChange.selectedReadable(selected, actor.getAccountId())
                    : AiProjectMetadataChange.selected(selected, actor.getAccountId());
            projectMetadata.authorize(account, current.policy(), schema, "PUT".equals(request.getMethod()), copyMap(request.getRequestObject()).keySet());
        }
        if (request.getAttribute(DOMAIN) instanceof AiResourceChangeDescriptor descriptor
                && (Set.of("dnsService", "certificate", "host", "project", "loadBalancerService", "registry", "volume").contains(descriptor.resourceType())
                    || AiServiceUpgradeLifecycleChange.supports(descriptor) || AiAliasServiceChange.ordinaryLinks(descriptor))) {
            if (request.getAction() != null) {
                var actions = request.getId() == null ? schema.getCollectionActions() : schema.getResourceActions();
                if (actions == null || !actions.containsKey(request.getAction())) throw error(403, "PermissionDenied");
            } else {
                List<String> methods = request.getId() == null ? schema.getCollectionMethods() : schema.getResourceMethods();
                if (methods == null || !methods.contains(request.getMethod())) throw error(403, "PermissionDenied");
            }
        }
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
        case "service", "externalService", "dnsService", "loadBalancerService" -> create().selectFrom(SERVICE).where(SERVICE.ID.eq(id)).forUpdate().fetchOne();
        case "volume" -> create().selectFrom(io.cattle.platform.core.model.tables.VolumeTable.VOLUME)
                .where(io.cattle.platform.core.model.tables.VolumeTable.VOLUME.ID.eq(id)).forUpdate().fetchOne();
        case "container" -> create().selectFrom(INSTANCE).where(INSTANCE.ID.eq(id)).forUpdate().fetchOne();
        case "certificate" -> create().selectFrom(CERTIFICATE).where(CERTIFICATE.ID.eq(id)).forUpdate().fetchOne();
        case "host" -> create().selectFrom(HOST).where(HOST.ID.eq(id)).forUpdate().fetchOne();
        case "project" -> create().selectFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).forUpdate().fetchOne();
        case "registry" -> create().selectFrom(io.cattle.platform.core.model.tables.StoragePoolTable.STORAGE_POOL)
                .where(io.cattle.platform.core.model.tables.StoragePoolTable.STORAGE_POOL.ID.eq(id)).forUpdate().fetchOne();
        default -> throw error(422, "ResourceChangeUnsupported");
        };
    }

    static boolean loadBalancerMetadata(AiResourceChangeDescriptor descriptor) {
        return "loadBalancerService".equals(descriptor.resourceType()) && "update_metadata".equals(descriptor.operation());
    }

    static boolean loadBalancerLifecycle(AiResourceChangeDescriptor descriptor) {
        return "loadBalancerService".equals(descriptor.resourceType()) && Set.of("start", "stop").contains(descriptor.operation());
    }

    static boolean loadBalancerOwned(AiResourceChangeDescriptor descriptor) {
        return loadBalancerMetadata(descriptor) || loadBalancerLifecycle(descriptor) || loadBalancerScale(descriptor);
    }

    static boolean loadBalancerScale(AiResourceChangeDescriptor descriptor) {
        return "loadBalancerService".equals(descriptor.resourceType()) && "scale".equals(descriptor.operation());
    }

    /** Only this new metadata plan uses a closed scalar snapshot; other Service/Stack snapshots stay unchanged. */
    public Map<String, Object> loadBalancerMetadataSnapshot(Object target) {
        if (target == null) return new LinkedHashMap<>();
        if (!(target instanceof Service service) || !"loadBalancerService".equals(service.getKind())) throw error(404, "NotFound");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "uuid", "accountId", "kind", "name", "description", "state", "removed", "stackId"))
            result.put(field, ObjectUtils.getPropertyIgnoreErrors(service, field));
        return copyMap(result);
    }

    public Map<String, Object> loadBalancerMetadataContext(ApiRequest request, AiResourceChangeDescriptor descriptor, String publicId) {
        if (!loadBalancerOwned(descriptor)) throw error(422, "ResourceChangeUnsupported");
        Object stack = loadBalancerStack(request, descriptor, publicId);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "uuid", "accountId", "kind", "name", "state", "removed"))
            result.put(field, ObjectUtils.getPropertyIgnoreErrors(stack, field));
        return copyMap(result);
    }

    private Object loadBalancerStack(ApiRequest request, AiResourceChangeDescriptor descriptor, String publicId) {
        Object candidate = visible(request, descriptor, publicId);
        if (!(candidate instanceof Service service) || service.getStackId() == null || service.getUuid() == null) throw error(404, "NotFound");
        Object parent = read(request, descriptor, "stack", publicId("stack", service.getStackId()), true);
        if (!(parent instanceof io.cattle.platform.core.model.Stack stack) || stack.getUuid() == null) throw error(404, "NotFound");
        Service current = objects.loadResource(Service.class, service.getId());
        if (current == null || current.getRemoved() != null || !"loadBalancerService".equals(current.getKind())
                || !Objects.equals(current.getUuid(), service.getUuid()) || !Objects.equals(current.getAccountId(), service.getAccountId())
                || !Objects.equals(current.getStackId(), service.getStackId())) throw error(409, "ResourceChangePlanStale");
        return parent;
    }

    public Map<String, Object> upgradeLifecycleSnapshot(Object target) { return AiServiceUpgradeLifecycleChange.snapshot(target, json); }

    /** Capture both the live generation and the native DAO's old upgrade set at apply, never at preview. */
    public Map<String, Object> upgradeLifecycleRetirementSnapshot(Object target) {
        Service service = AiServiceUpgradeLifecycleChange.service(target);
        List<Map<String, Object>> instances = new ArrayList<>();
        for (ServiceExposeMap link : objects.find(ServiceExposeMap.class, "serviceId", service.getId(), "removed", null)) {
            boolean priorUpgrade = Boolean.FALSE.equals(link.getManaged()) && Boolean.TRUE.equals(link.getUpgrade())
                    && Set.of("activating", "active", "requested").contains(Objects.toString(link.getState(), ""));
            if (!Boolean.TRUE.equals(link.getManaged()) && !priorUpgrade) continue;
            if (link.getInstanceId() == null) throw error(409, "ResourceChangePlanStale");
            Instance instance = objects.loadResource(Instance.class, link.getInstanceId());
            if (!Objects.equals(link.getServiceId(), service.getId()) || !Objects.equals(link.getAccountId(), service.getAccountId())
                    || link.getId() == null || link.getUuid() == null || link.getUuid().isBlank() || instance == null
                    || !Objects.equals(instance.getId(), link.getInstanceId()) || instance.getUuid() == null || instance.getUuid().isBlank()
                    || !Objects.equals(instance.getAccountId(), service.getAccountId())
                    || instance.getServiceId() != null && !Objects.equals(instance.getServiceId(), service.getId())
                    || instance.getStackId() != null && !Objects.equals(instance.getStackId(), service.getStackId()))
                throw error(409, "ResourceChangePlanStale");
            instances.add(Map.of("id", instance.getId(), "uuid", instance.getUuid(), "mapId", link.getId(), "mapUuid", link.getUuid()));
        }
        return copyMap(Map.of("instances", instances));
    }

    private boolean upgradeLifecycleRetirementSettled(Service service, Map<String, Object> before) {
        if (!(before.get("instances") instanceof List<?> instances)) return false;
        // Rollback can expose another retiring generation after the apply snapshot.
        // No pending upgrade map may be mistaken for a completely retired child.
        for (ServiceExposeMap link : objects.find(ServiceExposeMap.class, "serviceId", service.getId(), "removed", null))
            if (Boolean.TRUE.equals(link.getUpgrade())) return false;
        for (Object value : instances) {
            if (!(value instanceof Map<?, ?> row)) return false;
            long id = number(row.get("id"));
            Instance instance = objects.loadResource(Instance.class, id);
            ServiceExposeMap link = objects.loadResource(ServiceExposeMap.class, number(row.get("mapId")));
            if (instance != null && (!Objects.equals(instance.getUuid(), row.get("uuid"))
                    || !Objects.equals(instance.getAccountId(), service.getAccountId())
                    || instance.getServiceId() != null && !Objects.equals(instance.getServiceId(), service.getId())
                    || instance.getStackId() != null && !Objects.equals(instance.getStackId(), service.getStackId()))) return false;
            if (link != null && (!Objects.equals(link.getUuid(), row.get("mapUuid"))
                    || !Objects.equals(link.getServiceId(), service.getId()) || !Objects.equals(link.getInstanceId(), id)
                    || !Objects.equals(link.getAccountId(), service.getAccountId()))) return false;
            // A rollback may legitimately reuse the old instance under the same persisted map.
            if (link != null && link.getRemoved() == null && Boolean.TRUE.equals(link.getManaged()) && !Boolean.TRUE.equals(link.getUpgrade())
                    && "active".equals(link.getState()) && instance != null && instance.getRemoved() == null) continue;
            if (instance != null && (instance.getRemoved() == null || !Set.of("removed", "purged").contains(instance.getState()))) return false;
            if (!attachmentsGone(id)) return false;
        }
        return true;
    }

    /** Latest locking read while the Service and plan are locked; previews never call this. */
    public long upgradeLifecycleProcessBaseline(Object target) {
        Service service = AiServiceUpgradeLifecycleChange.service(target);
        Long latest = create().select(PROCESS_INSTANCE.ID).from(PROCESS_INSTANCE)
                .where(PROCESS_INSTANCE.RESOURCE_TYPE.eq("service").and(PROCESS_INSTANCE.RESOURCE_ID.eq(service.getId().toString())))
                .orderBy(PROCESS_INSTANCE.ID.desc()).limit(1).forUpdate().fetchOne(PROCESS_INSTANCE.ID);
        return latest == null ? 0 : latest;
    }

    /** A unique normal root process after the locked claim, never just a coincidental terminal state. */
    public String upgradeLifecycleProcessOutcome(Map<String, Object> plan, Object target) {
        Service service = AiServiceUpgradeLifecycleChange.service(target);
        AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require("service", (String) plan.get("operation"));
        if (!AiServiceUpgradeLifecycleChange.supports(descriptor) || !(plan.get("processBaseline") instanceof Number baseline)
                || baseline.longValue() < 0) return "unknown";
        var rows = create().selectFrom(PROCESS_INSTANCE).where(PROCESS_INSTANCE.RESOURCE_TYPE.eq("service")
                .and(PROCESS_INSTANCE.RESOURCE_ID.eq(service.getId().toString()))
                .and(PROCESS_INSTANCE.PROCESS_NAME.eq("service." + descriptor.action())).and(PROCESS_INSTANCE.ID.gt(baseline.longValue())))
                .orderBy(PROCESS_INSTANCE.ID.asc()).limit(2).fetch();
        if (rows.isEmpty()) return "running";
        if (rows.size() != 1) return "unknown";
        var process = rows.getFirst();
        if (!Objects.equals(process.getAccountId(), service.getAccountId())) return "unknown";
        Object raw = process.getData() == null ? null : process.getData().get(io.cattle.platform.engine.process.ProcessAuthorization.DATA_KEY);
        Map<String, Object> metadata = raw instanceof Map<?, ?> values ? copyMap(values) : Map.of();
        if (plan.get("credentialId") instanceof Number credential) {
            if (!Objects.equals(metadata.get("requestId"), plan.get("id")) || !"upgrade".equals(metadata.get("operation"))
                    || !"service".equals(metadata.get("targetType")) || !"POST".equals(metadata.get("requestMethod"))
                    || !descriptor.action().equals(metadata.get("requestAction")) || !Boolean.FALSE.equals(metadata.get("requestCollection"))
                    || !Boolean.FALSE.equals(metadata.get("preview")) || number(metadata.get("accountId")) != service.getAccountId()
                    || number(metadata.get("principalAccountId")) != number(plan.get("actorId"))
                    || number(metadata.get("policyRevision")) != number(plan.get("credentialRevision"))
                    || !(metadata.get("keyId") instanceof String keyId)
                    || !Objects.equals(service.getId().toString(), metadata.get("targetId")) || metadata.containsKey("requestLink")) return "unknown";
            try {
                if (targets.parseScopeId("credential", keyId) != credential.longValue()) return "unknown";
            } catch (ClientVisibleException invalid) { return "unknown"; }
        } else if (!metadata.isEmpty()) return "unknown";
        if (process.getEndTime() == null) return "running";
        return "SUCCESS".equals(process.getResult()) && "DONE".equals(process.getPhase()) && "DONE".equals(process.getExitReason())
                ? "succeeded" : "failed";
    }

    public Map<String, Object> upgradeLifecycleContext(ApiRequest request, AiResourceChangeDescriptor descriptor, String publicId) {
        Object stack = upgradeLifecycleStack(request, descriptor, publicId);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "uuid", "accountId", "kind", "name", "state", "removed"))
            result.put(field, ObjectUtils.getPropertyIgnoreErrors(stack, field));
        return copyMap(result);
    }

    public String upgradeLifecycleTargetPath(ApiRequest request, AiResourceChangeDescriptor descriptor, Object target) {
        return targetPath(request, target, upgradeLifecycleStack(request, descriptor, publicId("service", ObjectUtils.getId(target))), Map.of());
    }

    private Object upgradeLifecycleStack(ApiRequest request, AiResourceChangeDescriptor descriptor, String publicId) {
        Service service = AiServiceUpgradeLifecycleChange.service(visible(request, descriptor, publicId));
        if (service.getStackId() == null) throw error(404, "NotFound");
        Object parent = read(request, descriptor, "stack", publicId("stack", service.getStackId()), true);
        if (!(parent instanceof io.cattle.platform.core.model.Stack stack) || stack.getUuid() == null) throw error(404, "NotFound");
        Service current = objects.loadResource(Service.class, service.getId());
        if (current == null || current.getRemoved() != null || !"service".equals(current.getKind())
                || !Objects.equals(current.getUuid(), service.getUuid()) || !Objects.equals(current.getAccountId(), service.getAccountId())
                || !Objects.equals(current.getStackId(), service.getStackId())) throw error(409, "ResourceChangePlanStale");
        return parent;
    }

    public Map<String, Object> snapshot(Object target) {
        if (target instanceof io.cattle.platform.core.model.StoragePool pool && "registry".equals(pool.getKind()))
            return copyMap(AiRegistryLifecycleChange.snapshot(pool));
        if (target instanceof Host host) return copyMap(AiHostMetadataChange.snapshot(host));
        if (target instanceof Account project) return copyMap(AiProjectMetadataChange.snapshot(project));
        Map<String, Object> result = new LinkedHashMap<>();
        if (target != null) for (String field : List.of("id", "accountId", "kind", "name", "description", "state", "removed",
                "stackId", "startCount", "data")) {
            if (!(target instanceof Certificate) || !"data".equals(field))
                result.put(field, ObjectUtils.getPropertyIgnoreErrors(target, field));
        }
        if (target instanceof Certificate certificate) {
            // Certificate material is outside this metadata workflow and must never enter a durable plan.
            result.remove("data");
            result.put("uuid", certificate.getUuid());
            result.put("certFingerprint", DataAccessor.fieldString(certificate, "certFingerprint"));
        }
        if (target instanceof Service service) {
            if ("loadBalancerService".equals(service.getKind())) {
                result.put("uuid", service.getUuid());
                result.put("loadBalancerConfiguration", loadBalancerConfiguration(service));
            }
            List<Map<String, Object>> instances = new ArrayList<>();
            for (ServiceExposeMap link : objects.find(ServiceExposeMap.class, "serviceId", service.getId(), "removed", null)) {
                if (!Boolean.TRUE.equals(link.getManaged()) || link.getInstanceId() == null) continue;
                Instance instance = objects.loadResource(Instance.class, link.getInstanceId());
                if (instance != null) instances.add(Map.of("id", instance.getId(), "startCount", number(instance.getStartCount())));
            }
            instances.sort(java.util.Comparator.comparingLong(row -> number(row.get("id"))));
            result.put("managedInstances", instances);
            if ("dnsService".equals(service.getKind())) {
                List<Map<String, Object>> links = new ArrayList<>();
                for (ServiceConsumeMap link : objects.find(ServiceConsumeMap.class, "serviceId", service.getId(), "removed", null)) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    for (String field : List.of("id", "uuid", "accountId", "serviceId", "consumedServiceId", "name", "consumedService", "state", "data"))
                        edge.put(field, ObjectUtils.getPropertyIgnoreErrors(link, field));
                    Service consumed = objects.loadResource(Service.class, link.getConsumedServiceId());
                    edge.put("consumedUuid", consumed == null ? null : consumed.getUuid());
                    links.add(edge);
                }
                links.sort(java.util.Comparator.comparingLong(edge -> number(edge.get("id")))); result.put("aliasLinks", links);
            }
        }
        if (target instanceof io.cattle.platform.core.model.Stack stack) {
            List<Map<String, Object>> services = new ArrayList<>();
            for (Service service : objects.find(Service.class, "stackId", stack.getId(), "removed", null)) services.add(snapshot(service));
            services.sort(java.util.Comparator.comparingLong(row -> number(row.get("id"))));
            result.put("childServices", services);
        }
        return copyMap(result);
    }

    /** Same native rows, sorted before locking so two typed plans cannot invert endpoint row locks. */
    public Object lockServiceLinkTargets(Map<String, Object> plan) {
        long sourceId = AiAliasServiceChange.nativeId(plan.get("internalResourceId"));
        if (sourceId < 1) throw error(409, "ResourceChangePlanStale");
        Set<Long> ids = new java.util.TreeSet<>(); ids.add(sourceId);
        List<Map<String, Object>> pins = new ArrayList<>();
        Map<String, Object> references = copyMap(plan.get("referenceSnapshot"));
        for (String side : List.of("before", "after")) {
            if (!(references.get(side) instanceof List<?> rows) || rows.size() > AiAliasServiceChange.MAX_TARGETS)
                throw error(409, "ResourceChangePlanStale");
            for (Object row : rows) {
                Map<String, Object> pin = copyMap(row); long id = AiAliasServiceChange.nativeId(pin.get("id"));
                if (id < 1) throw error(409, "ResourceChangePlanStale"); ids.add(id); pins.add(pin);
            }
        }
        Map<Long, Service> locked = new LinkedHashMap<>();
        for (long id : ids) {
            Object row = lockServiceLinkRow(id);
            if (!(row instanceof Service service) || !"service".equals(service.getKind()) || service.getRemoved() != null
                    || !Set.of("active", "inactive").contains(String.valueOf(service.getState()))) throw error(409, "ResourceChangePlanStale");
            locked.put(id, service);
        }
        for (Map<String, Object> pin : pins) {
            Map<String, Object> identity = new LinkedHashMap<>(); Service row = locked.get(AiAliasServiceChange.nativeId(pin.get("id")));
            for (String field : List.of("id", "uuid", "accountId", "stackId", "kind"))
                identity.put(field, ObjectUtils.getPropertyIgnoreErrors(row, field));
            identity = copyMap(identity);
            for (String field : identity.keySet()) if (!pin.containsKey(field) || !Objects.equals(pin.get(field), identity.get(field)))
                throw error(409, "ResourceChangePlanStale");
        }
        return locked.get(sourceId);
    }

    protected Object lockServiceLinkRow(long id) {
        create().connection(connection -> { if (connection.getAutoCommit()) throw error(503, "ResourceChangeTransactionRequired"); });
        try { return create().selectFrom(SERVICE).where(SERVICE.ID.eq(id)).forUpdate().noWait().fetchOne(); }
        catch (org.jooq.exception.DataAccessException failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof java.sql.SQLException sql && ((sql.getSQLState() != null && Set.of("40001", "55P03", "HYT00").contains(sql.getSQLState()))
                        || sql.getErrorCode() == 3572 || sql.getErrorCode() == 1205 || sql.getErrorCode() == 50200))
                    throw error(409, "ResourceChangeTargetBusy");
                cause = cause.getCause();
            }
            throw failure;
        }
    }

    /** Spring's shipped read advice suspends the outer RR snapshot; never reload its locked source for update. */
    public Map<String, Object> currentServiceLinksState(ApiRequest request, Map<String, Object> plan, Object lockedTarget) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))
            throw error(503, "ResourceChangePreflightUnavailable");
        var descriptor = AiResourceChangeDescriptor.require((String) plan.get("resourceType"), (String) plan.get("operation"));
        if (!AiAliasServiceChange.ordinaryLinks(descriptor) || !(lockedTarget instanceof Service service)) throw error(422, "ResourceChangeUnsupported");
        visible(request, descriptor, (String) plan.get("resourceId")); ready(descriptor, lockedTarget, null);
        Map<String, Object> snapshot = serviceLinksSnapshot(lockedTarget);
        for (ServiceConsumeMap map : objects.find(ServiceConsumeMap.class, "serviceId", service.getId(), "removed", null))
            if (!objects.find(ProcessInstance.class, "resourceType", "serviceConsumeMap", "resourceId", map.getId().toString(),
                    "processName", "serviceconsumemap.remove", "endTime", null).isEmpty()) throw error(409, "ResourceChangeTargetBusy");
        Map<String, Object> body = prepare(request, descriptor, (String) plan.get("resourceId"), copyMap(plan.get("parameters")), plan);
        return Map.of("snapshot", snapshot, "body", body);
    }

    public Map<String, Object> serviceLinksSnapshot(Object target) {
        if (!(target instanceof Service service) || !"service".equals(service.getKind()) || service.getUuid() == null
                || AiAliasServiceChange.nativeId(service.getId()) < 1 || AiAliasServiceChange.nativeId(service.getAccountId()) < 1
                || AiAliasServiceChange.nativeId(service.getStackId()) < 1) throw error(422, "ResourceChangeUnsupported");
        var stack = objects.loadResource(io.cattle.platform.core.model.Stack.class, service.getStackId());
        if (stack == null || stack.getRemoved() != null || stack.getUuid() == null
                || !Objects.equals(stack.getAccountId(), service.getAccountId())) throw error(404, "NotFound");
        Map<String, Object> result = snapshot(service);
        result.put("uuid", service.getUuid()); result.put("linkStackUuid", stack.getUuid());
        for (String field : List.of("selectorContainer", "selectorLink", "vip", "externalId", "system", "skip"))
            result.put(field, ObjectUtils.getPropertyIgnoreErrors(service, field));
        List<Map<String, Object>> links = new ArrayList<>();
        for (ServiceConsumeMap link : objects.find(ServiceConsumeMap.class, "serviceId", service.getId(), "removed", null)) {
            Map<String, Object> edge = new LinkedHashMap<>();
            for (String field : List.of("id", "uuid", "accountId", "serviceId", "consumedServiceId", "name", "consumedService", "state", "data"))
                edge.put(field, ObjectUtils.getPropertyIgnoreErrors(link, field));
            Service consumed = objects.loadResource(Service.class, link.getConsumedServiceId());
            edge.put("consumedUuid", consumed == null ? null : consumed.getUuid()); links.add(edge);
        }
        links.sort(java.util.Comparator.comparingLong(edge -> number(edge.get("id")))); result.put("aliasLinks", links);
        return copyMap(result);
    }

    /** Private preservation operands, including nested ports/certificates/volumes; never accepted as action inputs. */
    private Map<String, Object> loadBalancerConfiguration(Service service) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("lbConfig", "launchConfig", "secondaryLaunchConfigs", "scale", "ports",
                "certificateIds", "defaultCertificateId", "volumes", "volumeMounts", "metadata", "labels",
                "upgrade", "assignServiceIpAddress", "retainIp")) result.put(field, DataAccessor.field(service, field, Object.class));
        result.put("selectorContainer", service.getSelectorContainer()); result.put("selectorLink", service.getSelectorLink());
        return copyMap(result);
    }

    public void ready(AiResourceChangeDescriptor descriptor, Object target, Object parent) {
        if (AiRegistryLifecycleChange.supports(descriptor)) { AiRegistryLifecycleChange.ready(descriptor, target); return; }
        if (AiServiceUpgradeLifecycleChange.supports(descriptor)) {
            AiServiceUpgradeLifecycleChange.ready(descriptor, target, json); return;
        }
        Object row = descriptor.createsResource() ? parent : target;
        if (row == null && !descriptor.createsResource()) throw error(404, "NotFound");
        if (row == null) return;
        String state = ObjectUtils.getState(row);
        if (loadBalancerScale(descriptor) && !Set.of("active", "inactive").contains(state == null ? "" : state))
            throw error(409, "ResourceChangeTargetBusy");
        if (!Set.of("active", "inactive", "running", "stopped").contains(state == null ? "" : state))
            throw error(409, "ResourceChangeTargetBusy");
        if (!descriptor.createsResource() && !"stack".equals(descriptor.resourceType())) {
            if ("start".equals(descriptor.operation()) && !(descriptor.serviceResource() ? "inactive" : "stopped").equals(state)
                    || (Set.of("stop", "restart").contains(descriptor.operation())
                        || "scale".equals(descriptor.operation()) && !loadBalancerScale(descriptor))
                    && !(descriptor.serviceResource() ? "active" : "running").equals(state))
                throw error(409, "ResourceChangeTargetBusy");
        }
        if ("scale".equals(descriptor.operation()) && !deploymentReadiness.supportsManualScale((Service) target))
            throw error(422, "ResourceChangeUnsupported");
        if (("dnsService".equals(descriptor.resourceType()) || AiAliasServiceChange.ordinaryLinks(descriptor)) && target instanceof Service service) {
            if (AiAliasServiceChange.ordinaryLinks(descriptor) && service.getSelectorLink() != null && !service.getSelectorLink().isBlank())
                throw error(422, "ResourceChangeUnsupported");
            List<? extends ServiceConsumeMap> links = objects.find(ServiceConsumeMap.class, "serviceId", service.getId(), "removed", null);
            if (links.size() > AiAliasServiceChange.MAX_TARGETS) throw error(422, "ResourceChangeUnsupported");
            for (ServiceConsumeMap link : links)
                if (!AiAliasServiceChange.simpleLink(link) || Objects.equals(link.getConsumedServiceId(), service.getId()))
                    throw error(422, "ResourceChangeUnsupported");
            if ("remove".equals(descriptor.operation()) && !objects.find(ServiceConsumeMap.class,
                    "consumedServiceId", service.getId(), "removed", null).isEmpty()) throw error(422, "ResourceChangeUnsupported");
        }
    }

    public String publicId(String type, Object id) {
        return id == null ? null : String.valueOf(ApiContext.getContext().getIdFormatter().formatId(type, id));
    }

    public String targetPath(ApiRequest request, Object target, Object parent, Map<String, Object> parameters) {
        if (target instanceof Service service && ("dnsService".equals(service.getKind())
                || "service".equals(service.getKind()) && parameters.containsKey("serviceIds"))
                && parent == null && service.getStackId() != null)
            parent = read(request, AiResourceChangeDescriptor.require(service.getKind(),
                    "service".equals(service.getKind()) ? "update_links" : "update_targets"),
                    "stack", publicId("stack", service.getStackId()), true);
        if (target instanceof Service service && "loadBalancerService".equals(service.getKind()) && parent == null)
            parent = loadBalancerStack(request, AiResourceChangeDescriptor.require("loadBalancerService", "update_metadata"),
                    publicId("loadBalancerService", service.getId()));
        if (target instanceof Account selected)
            return Objects.toString(AiProjectMetadataChange.selected(selected, actor(request).getAccountId()).getName(), "");
        Policy actor = actor(request); Account project = objects.loadResource(Account.class, actor.getAccountId());
        String name = AiRegistryLifecycleChange.registry(target) ? AiRegistryLifecycleChange.address(DataAccessor.field(target, "serverAddress", Object.class))
                : target instanceof Host host ? AiHostMetadataChange.displayName(host)
                : target == null ? String.valueOf(parameters.get("name")) : String.valueOf(ObjectUtils.getPropertyIgnoreErrors(target, "name"));
        return Objects.toString(project.getName(), "") + " / "
                + (parent == null ? "" : Objects.toString(ObjectUtils.getPropertyIgnoreErrors(parent, "name"), "") + " / ") + name;
    }

    public Object load(String type, long id) { return "project".equals(type) ? objects.loadResource(Account.class, id) : objects.loadResource(type, id); }

    public long internalId(String type, String id) { return targets.parseScopeId(type, id); }

    /** Removal receipts outlive their resource. This never returns the removed object to a client. */
    public Object receiptTarget(ApiRequest request, AiResourceChangeDescriptor descriptor, Map<String, Object> plan) {
        Object target = load(descriptor.resourceType(), number(plan.get("internalResourceId")));
        if ("project".equals(descriptor.resourceType())) {
            AiProjectMetadataChange.selectedReadable(target, actor(request).getAccountId());
            return visible(request, descriptor, (String) plan.get("resourceId"));
        }
        Policy actor = actor(request);
        ApiRequest domain = domain(request, descriptor, descriptor.resourceType(), (String) plan.get("resourceId"), "GET", null, new LinkedHashMap<>());
        var current = authenticator.currentAuthorization(actor.getAuthenticatedAsAccountId(), actor.getAccountId(), domain);
        Schema schema = current.schemas().getSchema(descriptor.resourceType());
        if (schema == null || schema.getResourceMethods() == null || !schema.getResourceMethods().contains("GET")) throw error(403, "PermissionDenied");
        if (target == null || !Objects.equals(ObjectUtils.getAccountId(target), actor.getAccountId())
                || !matchesResourceType(descriptor.resourceType(), target) || current.policy().authorizeObject(target) == null)
            throw error(403, "PermissionDenied");
        if (AiRegistryLifecycleChange.supports(descriptor)) {
            Map<String, Object> identity = copyMap(plan.containsKey("registryIdentity") ? plan.get("registryIdentity") : plan.get("snapshot"));
            if (!AiRegistryLifecycleChange.registry(target) || number(identity.get("id")) != number(ObjectUtils.getId(target))
                    || !Objects.equals(identity.get("uuid"), ObjectUtils.getPropertyIgnoreErrors(target, "uuid")))
                throw error(409, "ResourceChangePlanStale");
        }
        if (descriptor.createsResource() && Set.of("service", "loadBalancerService", "volume").contains(descriptor.resourceType())
                && (number(ObjectUtils.getId(target)) != number(plan.get("internalResourceId"))
                    || !(plan.get("createdResourceUuid") instanceof String uuid) || uuid.isBlank()
                    || !uuid.equals(ObjectUtils.getPropertyIgnoreErrors(target, "uuid")))) throw error(403, "PermissionDenied");
        if (ObjectUtils.getRemoved(target) == null) visible(request, descriptor, (String) plan.get("resourceId"));
        if (AiLoadBalancerCreateChange.supports(descriptor)) {
            Object parent = parent(request, descriptor, copyMap(plan.get("parameters")));
            Map<String, Object> originalParent = copyMap(plan.get("parentSnapshot"));
            if (!Set.of("succeeded", "failed", "cancelled").contains(Objects.toString(plan.get("status"), ""))
                    && (!Objects.equals(ObjectUtils.getPropertyIgnoreErrors(parent, "uuid"), originalParent.get("uuid"))
                        || !Objects.equals(ObjectUtils.getPropertyIgnoreErrors(target, "stackId"), ObjectUtils.getId(parent))))
                throw error(409, "ResourceChangePlanStale");
        }
        if (loadBalancerOwned(descriptor)) {
            Map<String, Object> stack = loadBalancerMetadataContext(request, descriptor, (String) plan.get("resourceId"));
            // Completed receipts are historical; pending receipts must still prove the approved identity.
            if (!Set.of("succeeded", "failed", "cancelled").contains(Objects.toString(plan.get("status"), ""))) {
                Map<String, Object> snapshot = copyMap(plan.get("snapshot"));
                Map<String, Object> currentTarget = loadBalancerMetadataSnapshot(target);
                for (String field : List.of("id", "uuid", "accountId", "kind", "stackId"))
                    if (!snapshot.containsKey(field) || !Objects.equals(snapshot.get(field), currentTarget.get(field)))
                        throw error(409, "ResourceChangePlanStale");
                Map<String, Object> reference = copyMap(plan.get("referenceSnapshot"));
                for (String field : List.of("id", "uuid", "accountId", "kind"))
                    if (!reference.containsKey(field) || !Objects.equals(reference.get(field), stack.get(field)))
                        throw error(409, "ResourceChangePlanStale");
            }
        }
        if (AiAliasServiceChange.ordinaryLinks(descriptor)) {
            Map<String, Object> before = copyMap(plan.containsKey("snapshot") ? plan.get("snapshot") : plan.get("linkIdentity"));
            Map<String, Object> currentTarget = serviceLinksSnapshot(target);
            for (String field : List.of("id", "uuid", "accountId", "kind", "stackId", "linkStackUuid"))
                if (!before.containsKey(field) || !Objects.equals(before.get(field), currentTarget.get(field)))
                    throw error(409, "ResourceChangePlanStale");
            // Both reviewed sides remain confidential after the full configuration snapshot is erased.
            // Re-read the source and every old/new target through fresh native owner and Key ceilings.
            aliasReferences(request, descriptor, Map.of("serviceIds", List.of((String) plan.get("resourceId"))));
            Map<String, Object> references = copyMap(plan.get("referenceSnapshot"));
            for (String side : List.of("before", "after")) {
                if (!(references.get(side) instanceof List<?> pins) || pins.size() > AiAliasServiceChange.MAX_TARGETS)
                    throw error(409, "ResourceChangePlanStale");
                List<String> ids = new ArrayList<>();
                for (Object value : pins) {
                    if (!(value instanceof Map<?, ?> pin) || AiAliasServiceChange.nativeId(pin.get("id")) < 1)
                        throw error(409, "ResourceChangePlanStale");
                    ids.add(publicId("service", AiAliasServiceChange.nativeId(pin.get("id"))));
                }
                List<Map<String, Object>> visible = aliasReferences(request, descriptor, Map.of("serviceIds", ids));
                if (visible.size() != pins.size()) throw error(409, "ResourceChangePlanStale");
                for (int index = 0; index < pins.size(); index++) {
                    Map<?, ?> pin = (Map<?, ?>) pins.get(index);
                    for (String field : List.of("id", "uuid", "accountId", "kind", "stackId", "stackUuid"))
                        if (!pin.containsKey(field) || !Objects.equals(pin.get(field), visible.get(index).get(field)))
                            throw error(409, "ResourceChangePlanStale");
                }
            }
        }
        if (AiServiceUpgradeLifecycleChange.supports(descriptor)) {
            Map<String, Object> identity = copyMap(plan.get("lifecycleIdentity"));
            Map<String, Object> currentSnapshot = upgradeLifecycleSnapshot(target);
            for (String field : List.of("id", "uuid", "accountId", "kind", "stackId"))
                if (!identity.containsKey(field) || !Objects.equals(identity.get(field), currentSnapshot.get(field)))
                    throw error(409, "ResourceChangePlanStale");
            Map<String, Object> stack = upgradeLifecycleContext(request, descriptor, (String) plan.get("resourceId"));
            if (!Objects.equals(identity.get("stackUuid"), stack.get("uuid"))) throw error(409, "ResourceChangePlanStale");
        }
        return target;
    }

    public boolean complete(AiResourceChangeDescriptor descriptor, Object target, Map<String, Object> parameters,
            Map<String, Object> before) {
        if (target == null) return false;
        if (AiVolumeCreateChange.supports(descriptor)) {
            return target instanceof io.cattle.platform.core.model.Volume volume
                    && AiVolumeCreateChange.complete(volume, parameters, number(before.get("volumeCreateId")),
                        (String) before.get("volumeCreateUuid"), number(before.get("volumeCreateAccountId")))
                    && objects.find(Mount.class, "volumeId", volume.getId()).isEmpty()
                    && objects.find(io.cattle.platform.core.model.VolumeStoragePoolMap.class, "volumeId", volume.getId()).isEmpty();
        }
        if (AiRegistryLifecycleChange.supports(descriptor))
            return parameters.isEmpty() && AiRegistryLifecycleChange.complete(descriptor, target, before, snapshot(target));
        if (AiServiceUpgradeLifecycleChange.supports(descriptor)) {
            if (!AiServiceUpgradeLifecycleChange.configurationComplete(descriptor, target, before, json)) return false;
            return "cancel_upgrade".equals(descriptor.operation()) || serviceReady((Service) target, true)
                    && before.get("retirementSnapshot") instanceof Map<?, ?> retirement
                    && upgradeLifecycleRetirementSettled((Service) target, copyMap(retirement));
        }
        if (AiLoadBalancerCreateChange.supports(descriptor)) {
            Map<String, Object> approved = copyMap(before.get("loadBalancerCreateBody"));
            if (!(target instanceof Service service) || !(approved.get("launchConfig") instanceof Map<?, ?> launch)
                    || !(launch.get("imageUuid") instanceof String image)) return false;
            boolean start = Boolean.TRUE.equals(parameters.get("startOnCreate"));
            return AiLoadBalancerCreateChange.configurationMatches(service, approved, internalId("stack", (String) parameters.get("stackId")),
                    serviceCreateValidation.imageUuid(image), id -> internalId("service", id))
                    && (start ? "active".equals(service.getState()) && serviceReady(service, true) : "inactive".equals(service.getState()));
        }
        if (AiAliasServiceChange.ordinaryLinks(descriptor)) {
            Map<String, Object> current = serviceLinksSnapshot(target);
            for (String field : List.of("id", "uuid", "accountId", "kind", "stackId", "linkStackUuid", "name", "description",
                    "state", "data", "managedInstances", "selectorContainer", "selectorLink", "vip", "externalId", "system", "skip"))
                if (!before.containsKey(field) || !Objects.equals(before.get(field), current.get(field))) return false;
            Set<Long> expected = new java.util.LinkedHashSet<>();
            for (Object id : (List<?>) parameters.get("serviceIds")) expected.add(internalId("service", (String) id));
            return target instanceof Service service && AiAliasServiceChange.complete("update_links", service, parameters,
                    expected, objects.find(ServiceConsumeMap.class, "serviceId", service.getId()));
        }
        if ("dnsService".equals(descriptor.resourceType())) {
            Set<Long> expected = new java.util.LinkedHashSet<>();
            if (parameters.get("serviceIds") instanceof List<?> ids) for (Object id : ids) expected.add(internalId("service", (String) id));
            return target instanceof Service service && AiAliasServiceChange.complete(descriptor.operation(), service, parameters,
                    expected, objects.find(ServiceConsumeMap.class, "serviceId", service.getId()));
        }
        if (descriptor.changesArchive()) return ObjectUtils.getRemoved(target) == null
                && Boolean.TRUE.equals(DataAccessor.field(target, "archived", Object.class)) == "archive".equals(descriptor.operation());
        if ("externalService".equals(descriptor.resourceType())) return target instanceof Service service
                && AiExternalServiceChange.complete(descriptor.operation(), service, parameters,
                        objects.find(ServiceExposeMap.class, "serviceId", service.getId()));
        String state = ObjectUtils.getState(target);
        if ("remove".equals(descriptor.operation())) return ObjectUtils.getRemoved(target) != null
                && Set.of("removed", "purged").contains(state) && removalSettled(descriptor.resourceType(), target)
                && priorInstancesRemoved(before);
        if (ObjectUtils.getRemoved(target) != null) return false;
        if ("project".equals(descriptor.resourceType()) && "update_metadata".equals(descriptor.operation()) && !"active".equals(state)) return false;
        if ("update_metadata".equals(descriptor.operation())) return parameters.entrySet().stream()
                .allMatch(entry -> Objects.equals(entry.getValue(), ObjectUtils.getPropertyIgnoreErrors(target, entry.getKey())));
        if (loadBalancerScale(descriptor)) {
            if (!(target instanceof Service service) || !"loadBalancerService".equals(service.getKind())) return false;
            Map<String, Object> current = snapshot(service);
            for (String field : List.of("id", "uuid", "accountId", "kind", "stackId", "name", "description", "state"))
                if (!before.containsKey(field) || !Objects.equals(before.get(field), current.get(field))) return false;
            Map<String, Object> previousConfig = copyMap(before.get("loadBalancerConfiguration"));
            Map<String, Object> currentConfig = copyMap(current.get("loadBalancerConfiguration"));
            if (!previousConfig.containsKey("scale") || !currentConfig.containsKey("scale")
                    || !Objects.equals(parameters.get("scale"), DataAccessor.fieldInteger(service, "scale"))) return false;
            currentConfig.put("scale", previousConfig.get("scale"));
            if (!previousConfig.equals(currentConfig)) return false;
            return "active".equals(state) ? serviceReady(service, true) && retiredInstancesSettled(service, before)
                    : "inactive".equals(state) && serviceReady(service, false) && priorInstancesStopped(service, before);
        }
        if ("scale".equals(descriptor.operation())) return "active".equals(state)
                && Objects.equals(parameters.get("scale"), DataAccessor.fieldInteger(target, "scale")) && serviceReady((Service) target, true)
                && retiredInstancesSettled((Service) target, before);
        if ("create".equals(descriptor.operation())) {
            if ("service".equals(descriptor.resourceType())) {
                Map<String, Object> expected = descriptor.body(parameters);
                String image = (String) ((Map<?, ?>) expected.get("launchConfig")).get("imageUuid");
                return target instanceof Service service && AiServiceCreateChange.complete(service, expected,
                        internalId("stack", (String) parameters.get("stackId")), serviceCreateValidation.imageUuid(image));
            }
            return "container".equals(descriptor.resourceType()) ? "stopped".equals(state)
                    && (!parameters.containsKey("ports") || target instanceof Instance instance
                        && AiCreatePorts.configuredMatch(parameters.get("ports"), DataAccessor.field(instance, "ports", Object.class)))
                    : "stack".equals(descriptor.resourceType()) && Set.of("active", "inactive").contains(state);
        }
        if (loadBalancerLifecycle(descriptor)) {
            if (!(target instanceof Service service) || !parameters.isEmpty()) return false;
            Map<String, Object> current = snapshot(service);
            for (String field : List.of("id", "uuid", "accountId", "kind", "stackId", "name", "description", "loadBalancerConfiguration"))
                if (!before.containsKey(field) || !Objects.equals(before.get(field), current.get(field))) return false;
        }
        if ("container".equals(descriptor.resourceType())) return "stop".equals(descriptor.operation()) ? "stopped".equals(state)
                : "running".equals(state) && (!"restart".equals(descriptor.operation())
                    || number(ObjectUtils.getPropertyIgnoreErrors(target, "startCount")) > number(before.get("startCount")));
        if ("service".equals(descriptor.resourceType()) || loadBalancerLifecycle(descriptor)) return "stop".equals(descriptor.operation())
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
            if (!Boolean.TRUE.equals(link.getManaged()) || link.getInstanceId() == null) continue;
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
        if (AiVolumeCreateChange.supports(descriptor)) {
            List<Map<String, Object>> result = new ArrayList<>();
            result.add(change("name", null, parameters.get("name")));
            if (parameters.containsKey("description")) result.add(change("description", null, parameters.get("description")));
            result.add(change("driver", null, "local")); result.add(change("state", null, "detached")); return result;
        }
        if (AiServiceUpgradeLifecycleChange.supports(descriptor)) return AiServiceUpgradeLifecycleChange.changes(descriptor, target);
        List<Map<String, Object>> result = new ArrayList<>();
        if (AiLoadBalancerCreateChange.supports(descriptor)) {
            Map<String, Object> reference = loadBalancerCreateReferences(ApiContext.getContext().getApiRequest(), descriptor, parameters);
            Map<Long, String> paths = new LinkedHashMap<>();
            for (Object item : (List<?>) reference.get("targets")) {
                Map<?, ?> pin = (Map<?, ?>) item; paths.put(number(pin.get("id")), (String) pin.get("path"));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> rule : AiLoadBalancerCreateChange.rules(parameters.get("portRules"))) {
                Map<String, Object> row = new LinkedHashMap<>(rule);
                row.put("target", paths.get(internalId("service", (String) row.remove("serviceId")))); rows.add(row);
            }
            result.add(change("name", null, parameters.get("name")));
            if (parameters.containsKey("description")) result.add(change("description", null, parameters.get("description")));
            result.add(change("portRules", null, displayed("portRules", rows)));
            result.add(change("startOnCreate", null, parameters.get("startOnCreate")));
            result.add(change("scale", null, 1));
            result.add(change("image", null, ((Map<?, ?>) descriptor.body(parameters).get("launchConfig")).get("imageUuid")));
            return result;
        }
        if (descriptor.changesArchive()) return List.of(change("archived",
                Boolean.TRUE.equals(DataAccessor.field(target, "archived", Object.class)), "archive".equals(descriptor.operation())));
        if (AiAliasServiceChange.changesTargets(descriptor)) {
            List<String> beforeIds = new ArrayList<>();
            for (ServiceConsumeMap link : objects.find(ServiceConsumeMap.class, "serviceId", ObjectUtils.getId(target), "removed", null)) {
                if (!AiAliasServiceChange.simpleLink(link) || !Objects.equals(link.getAccountId(), ObjectUtils.getAccountId(target)))
                    throw error(422, "ResourceChangeUnsupported");
                beforeIds.add(publicId("service", link.getConsumedServiceId()));
            }
            ApiRequest request = ApiContext.getContext().getApiRequest();
            List<String> beforePaths = aliasReferences(request, descriptor, Map.of("serviceIds", beforeIds)).stream()
                    .map(reference -> (String) reference.get("path")).toList();
            List<String> afterPaths = aliasReferences(request, descriptor, parameters).stream()
                    .map(reference -> (String) reference.get("path")).toList();
            return List.of(change("targetServices", displayed("targetServices", beforePaths), displayed("targetServices", afterPaths)));
        }
        if ("dnsService".equals(descriptor.resourceType()) && descriptor.createsResource())
            result.add(change("targetServices", null, displayed("targetServices", List.of())));
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
                if (Boolean.TRUE.equals(link.getManaged()) && link.getInstanceId() != null) instances.add(link.getInstanceId());
            int mounts = 0; for (Long id : instances) mounts += objects.find(Mount.class, "instanceId", id, "removed", null).size();
            result.add(change("affectedServiceCount", services.size(), 0));
            result.add(change("affectedInstanceCount", instances.size(), 0)); result.add(change("affectedMountCount", mounts, null));
            result.add(change("mountedDataRule", null, "EXISTING_DOMAIN_RETENTION_NO_ADDITIONAL_PURGE"));
        }
        if ("externalService".equals(descriptor.resourceType()) && Set.of("create", "update_targets").contains(descriptor.operation())) {
            String cleared = parameters.containsKey("hostname") ? "externalIpAddresses" : "hostname";
            result.add(change(cleared, target == null ? null : displayed(cleared,
                    DataAccessor.field(target, cleared, Object.class)), null));
        }
        for (var entry : parameters.entrySet()) {
            if (Set.of("externalService", "dnsService").contains(descriptor.resourceType()) && "stackId".equals(entry.getKey())) continue;
            result.add(change(entry.getKey(), target == null ? null
                : "scale".equals(entry.getKey()) ? DataAccessor.fieldInteger(target, "scale")
                : Set.of("hostname", "externalIpAddresses").contains(entry.getKey())
                    ? displayed(entry.getKey(), DataAccessor.field(target, entry.getKey(), Object.class))
                    : ObjectUtils.getPropertyIgnoreErrors(target, entry.getKey()), displayed(entry.getKey(), entry.getValue())));
        }
        if (descriptor.createsResource() && !"stack".equals(descriptor.resourceType())) result.add(change("startOnCreate", null, false));
        return result;
    }

    private Object displayed(String field, Object value) {
        if ("hostname".equals(field) && (value == null || !(value instanceof String hostname)
                || !AiExternalServiceChange.hostname(hostname))) return null;
        if ("externalIpAddresses".equals(field)) {
            if (value == null) return null;
            try { value = AiExternalServiceChange.addresses(value); }
            catch (ClientVisibleException unsafe) { return null; }
        }
        if (!Set.of("command", "entryPoint", "ports", "healthCheck", "externalIpAddresses", "targetServices", "portRules").contains(field)) return value;
        try { return json.writeValueAsString(value); }
        catch (IOException failure) { throw new IllegalStateException("Resource-change summary unavailable", failure); }
    }

    public <T> T withArchiveChange(AiResourceChangeDescriptor descriptor, Object lockedTarget, Supplier<T> change) {
        return archiveChanges.withChange(lockedTarget, "archive".equals(descriptor.operation()), change);
    }

    /** Called only after complete() verified this created Service and its native port allocation. */
    public Map<String, Object> serviceCreateResult(Object target) {
        Object launch = DataAccessor.field(target, "launchConfig", Object.class);
        Object ports = launch instanceof Map<?, ?> map ? map.get("ports") : null;
        return Map.of("assignedPorts", ports == null ? List.of() : AiServiceCreateChange.ports(ports));
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
