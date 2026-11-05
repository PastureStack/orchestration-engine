package io.cattle.platform.iaas.api.auth.ai.view;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.dao.VolumeDao;
import io.cattle.platform.core.dao.HostDao;
import io.cattle.platform.core.dao.ServiceDao;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Stack;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.model.impl.WrappedResource;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Pagination;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.Sort;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.commons.lang3.StringUtils;

/**
 * Metadata is projected on the server before it reaches a dedicated AI Key.
 * Each read still traverses its ordinary, freshly authorized domain manager.
 */
public class AiResourceViewService {
    public static final String DOMAIN_READ_ATTRIBUTE = "ai.resource.view.domain.read";
    public static final Set<String> READ_TYPES = Set.of("project", "projectTemplate", "account", "stack", "service",
            "loadBalancerService", "dnsService", "externalService", "container", "virtualMachine",
            "host", "volume", "snapshot", "backup", "backupTarget", "storagePool", "storageDriver",
            "network", "networkDriver", "certificate", "registry", "registryCredential", "apiKey",
            "apiKeyRestricted", "setting", "machineDriver", "secret", "processExecution", "processPool", "auditLog");
    private static final Set<String> GLOBAL_TYPES = Set.of("project", "projectTemplate", "account", "apiKey", "apiKeyRestricted",
            "setting", "machineDriver", "processExecution", "processPool", "auditLog");
    private static final Set<String> MEMORY_COLLECTIONS = Set.of("project", "setting", "processPool");
    private static final Set<String> DATABASE_NAME_COLLECTIONS = Set.of("projectTemplate", "account", "stack", "service",
            "loadBalancerService", "dnsService", "externalService", "container", "host", "volume", "snapshot", "backup",
            "backupTarget", "storagePool", "storageDriver", "network", "networkDriver", "certificate", "registry",
            "registryCredential", "apiKey", "apiKeyRestricted", "machineDriver", "secret");
    private static final List<String> COMMON_FIELDS = List.of("name", "description", "state", "kind", "created", "healthState");
    private static final Map<String, List<String>> FIELDS = Map.ofEntries(
            Map.entry("project", List.of("orchestration")),
            Map.entry("stack", List.of("archived")),
            Map.entry("service", List.of("scale", "stackId", "archived")),
            Map.entry("loadBalancerService", List.of("scale", "stackId", "archived")),
            Map.entry("dnsService", List.of("stackId", "archived")), Map.entry("externalService", List.of("stackId", "archived")),
            Map.entry("container", List.of("imageUuid", "memory", "cpuShares", "startCount", "networkMode")),
            Map.entry("virtualMachine", List.of("imageUuid", "memory", "cpuShares")),
            Map.entry("host", List.of("hostname", "agentIpAddress", "dockerVersion", "memTotal", "cpuCount",
                    "agentState", "memory", "milliCpu", "localStorageMb")),
            Map.entry("volume", List.of("driver", "accessMode", "size")),
            Map.entry("certificate", List.of("cn", "expiresAt", "issuedAt")),
            Map.entry("registry", List.of()), Map.entry("registryCredential", List.of()),
            Map.entry("apiKey", List.of("apiKeyPolicyRevision")),
            Map.entry("apiKeyRestricted", List.of("apiKeyPolicyRevision")),
            Map.entry("setting", List.of("source", "inDb")),
            Map.entry("machineDriver", List.of("builtin", "active")),
            Map.entry("processExecution", List.of("processName", "startTime", "endTime", "exitReason")),
            Map.entry("processPool", List.of("activeTasks", "poolSize", "queueSize")),
            Map.entry("auditLog", List.of("eventType", "resourceType", "responseCode", "httpStatus", "authType",
                    "interactionChannel", "decision", "outcome", "operation")));

    @Inject AiKeyAuthorization keyAuthorization;
    @Inject ApiKeyAuthorizationService keyAccess;
    @Inject ApiAuthenticator authenticator;
    @Inject ResourceManagerLocator managers;
    @Inject ObjectManager objects;
    @Inject io.cattle.platform.iaas.api.auth.change.AiResourceChangeDomain resourceChanges;
    @Inject VolumeDao volumes;
    @Inject HostDao hosts;
    @Inject ServiceDao services;

    private record AuthorizedTarget(Resource resource, boolean serviceIdsReadable, boolean missingPersistedStack, String instanceUuid) { }

    public Map<String, Object> read(ApiRequest request) {
        Map<String, Object> input = input(request);
        AiAuditSourcePage source = input.containsKey("sourcePage") ? AiAuditSourcePage.parse(input) : null;
        String type = source == null ? (String) input.get("resourceType") : source.type;
        Policy current = keyAuthorization.actor(request);
        if (current == null || current.getAuthenticatedAsAccountId() <= 0) throw error(403, "PermissionDenied");
        long principal = current.getAuthenticatedAsAccountId();
        long account = GLOBAL_TYPES.contains(type) ? principal : current.getAccountId();
        if (source != null && source.projectId != null) {
            account = AiAuditSourcePage.number("project", source.projectId);
            Account project = objects.loadResource(Account.class, account);
            if (project == null || project.getRemoved() != null || !"project".equals(project.getKind()) || !"active".equals(project.getState()))
                throw error(403, "OwnerPermissionDenied");
        }
        ApiRequest domain = new ApiRequest(request);
        domain.setRequestServletContext(request.getServletContext());
        domain.setType(type); domain.setId(input.get("resourceId") == null || "processPool".equals(type)
                ? null : rawId((String) input.get("resourceId")).toString());
        domain.setMethod("GET"); domain.setAction(null); domain.setLink(null); domain.setRequestObject(null);
        domain.setRequestParams(new LinkedHashMap<>()); domain.setConditions(new LinkedHashMap<>());
        // Ask the ordinary Project owner for its normal Admin view; native RBAC and
        // the common Key collection filter still decide every returned Project.
        if (source == null && "project".equals(type) && domain.getId() == null)
            domain.getRequestParams().put("all", new String[] {"true"});
        domain.setInclude(null);
        domain.setSort(new Sort("id", Sort.SortOrder.ASC, null));
        domain.setPagination(new Pagination(MEMORY_COLLECTIONS.contains(type) ? 10001 : 51));
        if (source != null) source.configure(domain);
        // ApiRequest's copy constructor gives attributes a fresh map. This is
        // an internal marker, never derived from an HTTP header or input map.
        domain.setAttribute(DOMAIN_READ_ATTRIBUTE, Boolean.TRUE);
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key != null) {
            // Source pages are one bounded query snapshot even for GENERAL
            // Keys. A later revision cannot silently enlarge this query.
            if (source != null && (request.getServletContext() == null
                    || !Long.toString(key.revision()).equals(request.getServletContext().getRequest()
                            .getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)))) throw error(403, "ApiKeyPolicyChanged");
            ApiKeyCredentialContext.attach(domain, key);
        }
        var authority = authenticator.currentAuthorization(principal, account, domain);
        domain.setSchemaFactory(authority.schemas());
        Schema schema = authority.schemas().getSchema(type);
        List<String> methods = schema == null ? null : domain.getId() == null ? schema.getCollectionMethods() : schema.getResourceMethods();
        if (methods == null || !methods.contains("GET")) throw error(403, "OwnerPermissionDenied");
        ApiContext context = ApiContext.getContext();
        ApiRequest previous = context.getApiRequest(); Object previousPolicy = context.getPolicy();
        context.setApiRequest(domain); context.setPolicy(authority.policy());
        try {
            keyAccess.authorize(domain, authority.policy());
            if (source != null) {
                ResourceManager manager = managers.getResourceManagerByType(type);
                if (manager == null) throw error(404, "NotFound");
                return source.fetch(manager, domain, authority.policy());
            }
            return fetch(domain, input);
        } finally {
            context.setApiRequest(previous); context.setPolicy(previousPolicy);
        }
    }

    protected Map<String, Object> fetch(ApiRequest domain, Map<String, Object> input) {
        String type = domain.getType();
        ResourceManager manager = managers.getResourceManagerByType(type);
        if (manager == null) throw error(404, "NotFound");
        String query = ((String) input.getOrDefault("query", "")).toLowerCase(Locale.ROOT);
        if (domain.getId() == null && !query.isEmpty() && DATABASE_NAME_COLLECTIONS.contains(type)) {
            // Retain native tenant/Key restrictions and bounded pagination. A
            // name search must not page through unrelated rows before filtering.
            domain.getConditions().put("name", List.of(new Condition(ConditionType.LIKE, namePattern(query))));
        }
        String parent = (String) input.get("parentId");
        if (parent != null) {
            if (!Set.of("service", "loadBalancerService", "dnsService", "externalService").contains(type))
                throw error(422, "ResourceViewInputInvalid");
            // Parent references are never a substitute for child authorization.
            domain.getConditions().put("stackId", List.of(new Condition(ConditionType.EQ, rawId(parent))));
        }
        String marker = (String) input.get("marker");
        if (marker != null && !MEMORY_COLLECTIONS.contains(type))
            domain.getConditions().put("id", List.of(new Condition(ConditionType.GT, rawId(marker))));
        List<?> rows;
        if (domain.getId() != null) {
            Object row = manager.getById(type, domain.getId(), new ListOptions(domain));
            if (row == null) throw error(404, "NotFound");
            rows = List.of(row);
        } else {
            Object listed = manager.list(type, domain);
            if (!(listed instanceof List<?> list) || list.size() > 10000) throw error(422, "ResourceViewNarrowQuery");
            rows = list;
        }
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Object row : rows) {
            Resource resource = Set.of("volume", "host").contains(type) && domain.getId() != null
                    ? coreDetail(row, domain) : manager.convertResponse(row, domain);
            if (resource == null) throw error(503, "ResourceViewUnavailable");
            String id = "processPool".equals(type) ? poolId(resource.getFields()) : resource.getId();
            if (id == null) throw error(503, "ResourceViewUnavailable");
            Map<String, Object> safe = project(type, id, resource.getFields());
            if ("volume".equals(type) && domain.getId() != null) {
                for (String field : List.of("isHostPath", "isNative", "sizeMb")) scalar(safe, resource.getFields(), field);
                if (resource.getFields().get("created") instanceof java.util.Date created) safe.put("created", created);
                if (resource.getFields().containsKey("removed")) safe.put("removed", resource.getFields().get("removed"));
                volumeUsage(domain, id, safe);
            }
            if ("host".equals(type) && domain.getId() != null) hostWorkloads(domain, id, safe);
            if ("dnsService".equals(type)) safe.putAll(resourceChanges.aliasViewTargets(domain, id, domain.getId() != null));
            if ("processPool".equals(type) && (!(safe.get("name") instanceof String name) || name.isBlank()))
                safe.put("name", "Process pool " + id.substring(5, 17));
            if (marker == null || compareIds((String) safe.get("id"), marker) > 0) visible.add(safe);
        }
        if ("processPool".equals(type) && input.get("resourceId") instanceof String selected) {
            visible.removeIf(item -> !selected.equals(item.get("id")));
            if (visible.size() != 1) throw error(404, "NotFound");
        }
        if (MEMORY_COLLECTIONS.contains(type)) visible.sort((a, b) -> compareIds((String) a.get("id"), (String) b.get("id")));
        if (MEMORY_COLLECTIONS.contains(type) && !query.isEmpty())
            visible.removeIf(row -> !((String) row.getOrDefault("name", "")).toLowerCase(Locale.ROOT).contains(query));
        boolean hasMore = visible.size() > 50;
        List<Map<String, Object>> page = visible.subList(0, Math.min(50, visible.size()));
        String next = hasMore ? (String) page.getLast().get("id") : null;
        if (Set.of("service", "loadBalancerService", "dnsService", "externalService").contains(type)) enrichParents(domain, page);
        List<Map<String, Object>> matching = page.stream().filter(row -> query.isEmpty()
                || ((String) row.getOrDefault("name", "")).toLowerCase(Locale.ROOT).contains(query)).toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", "aiview-" + UUID.randomUUID()); output.put("resourceType", type);
        output.put("data", matching); output.put("nextMarker", next);
        return output;
    }

    static String namePattern(String value) {
        // The native LIKE owner binds this as data. Escape pattern characters
        // so the public substring search keeps its literal-name semantics.
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** One authorized parent query per page, rather than one request per candidate. */
    private void enrichParents(ApiRequest domain, List<Map<String, Object>> page) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> item : page) {
            Object id = item.get("stackId");
            if (id instanceof String || id instanceof Number) ids.add(id.toString());
            item.put("parentResolution", "unavailable");
        }
        if (ids.isEmpty()) return;
        Schema schema = domain.getSchemaFactory().getSchema("stack");
        if (schema == null || schema.getCollectionMethods() == null || !schema.getCollectionMethods().contains("GET")) return;
        ApiRequest parents = new ApiRequest(domain);
        parents.setRequestServletContext(domain.getServletContext());
        parents.setType("stack"); parents.setId(null);
        parents.setConditions(new LinkedHashMap<>());
        parents.getConditions().put("id", List.of(new Condition(ConditionType.IN, ids.stream().map(AiResourceViewService::rawId).toList())));
        parents.setPagination(new Pagination(50));
        parents.setAttribute(DOMAIN_READ_ATTRIBUTE, Boolean.TRUE);
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(domain);
        if (key != null) ApiKeyCredentialContext.attach(parents, key);
        ApiContext context = ApiContext.getContext();
        context.setApiRequest(parents);
        try {
            keyAccess.authorize(parents, ApiUtils.getPolicy());
            ResourceManager manager = managers.getResourceManagerByType("stack");
            if (manager == null) return;
            Object result = manager.list("stack", parents);
            if (!(result instanceof List<?> rows) || rows.size() > 50) throw error(503, "ResourceViewUnavailable");
            Map<String, String> names = new LinkedHashMap<>();
            Set<String> requested = new LinkedHashSet<>();
            for (String id : ids) requested.add(rawId(id).toString());
            for (Object row : rows) {
                Resource resource = manager.convertResponse(row, parents);
                if (resource == null || resource.getId() == null) continue;
                String id = rawId(resource.getId()).toString();
                Object name = resource.getFields().get("name");
                if (requested.contains(id) && name instanceof String text) names.put(id, text.substring(0, Math.min(text.length(), 512)));
            }
            for (Map<String, Object> item : page) {
                Object parent = item.get("stackId");
                String name = parent == null ? null : names.get(rawId(parent.toString()).toString());
                if (name != null) { item.put("stackName", name); item.put("parentResolution", "resolved"); }
            }
        } catch (ClientVisibleException denied) {
            // A child read grant need not include a parent name. Revocation,
            // expiry, revision changes and other failures still invalidate it.
            if (!Set.of("KeyScopeDenied", "KeyPolicyDenied", "OwnerPermissionDenied", "NotFound").contains(denied.getCode())) throw denied;
        } finally {
            context.setApiRequest(domain);
        }
    }

    private void volumeUsage(ApiRequest domain, String id, Map<String, Object> safe) {
        // Never infer unused, a hidden count or completeness from this bounded,
        // permission-filtered snapshot. The native deletion oracle is separate.
        safe.put("usageReferencesStatus", "unknown");
        List<Map<String, Object>> references = new ArrayList<>();
        safe.put("usageReferences", references);
        if (volumes == null) return;
        long volumeId;
        try { volumeId = Long.parseLong(rawId(id).toString()); }
        catch (NumberFormatException invalid) { throw error(503, "ResourceViewUnavailable"); }
        if (volumeId <= 0 || !sameId(domain.getId(), id)) throw error(503, "ResourceViewUnavailable");
        List<? extends Mount> mounts = volumes.getUsageMounts(ApiUtils.getPolicy().getAccountId(), volumeId);
        if (mounts == null || mounts.size() > 51) throw error(503, "ResourceViewUnavailable");
        for (Mount mount : mounts.subList(0, Math.min(50, mounts.size()))) {
            if (mount == null || !Long.valueOf(volumeId).equals(mount.getVolumeId())
                    || mount.getInstanceId() == null || mount.getRemoved() != null) continue;
            AuthorizedTarget child = usageTarget(domain, "container", mount.getInstanceId().toString());
            Resource target = child == null ? null : child.resource();
            if (target == null || !"container".equals(target.getType()) || !(target.getFields().get("name") instanceof String)
                    || !(target.getFields().get("state") instanceof String)) continue;
            String path = mount.getPath();
            if (path == null || !path.startsWith("/") || path.length() > 1024
                    || path.chars().anyMatch(value -> value < 32 || value == 127)
                    || !("ro".equals(mount.getPermissions()) || "rw".equals(mount.getPermissions()))) continue;
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("containerId", target.getId());
            scalar(reference, target.getFields(), "name"); scalar(reference, target.getFields(), "state");
            reference.put("mountPath", path); reference.put("readOnly", "ro".equals(mount.getPermissions()));
            reference.put("stackResolution", "unknown");
            usageStack(domain, child, reference, null, null);
            references.add(reference);
        }
    }

    private void hostWorkloads(ApiRequest domain, String id, Map<String, Object> safe) {
        safe.put("workloadReferencesStatus", "unknown");
        List<Map<String, Object>> references = new ArrayList<>();
        safe.put("workloadReferences", references);
        if (hosts == null) return;
        long hostId = Long.parseLong(rawId(id).toString()), account = ApiUtils.getPolicy().getAccountId();
        if (hostId <= 0 || !sameId(domain.getId(), id)) throw error(503, "ResourceViewUnavailable");
        List<? extends InstanceHostMap> candidates = hosts.getWorkloadCandidates(account, hostId);
        if (candidates == null || candidates.size() > 51) throw error(503, "ResourceViewUnavailable");
        Set<Long> seen = new LinkedHashSet<>();
        for (InstanceHostMap candidate : candidates.subList(0, Math.min(50, candidates.size()))) {
            if (candidate == null || candidate.getId() == null || candidate.getId() <= 0
                    || !Long.valueOf(hostId).equals(candidate.getHostId()) || candidate.getInstanceId() == null
                    || candidate.getInstanceId() <= 0 || candidate.getRemoved() != null || !seen.add(candidate.getInstanceId())) continue;
            AuthorizedTarget child = usageTarget(domain, "container", candidate.getInstanceId().toString());
            Resource target = child == null ? null : child.resource();
            if (target == null || !(target.getFields().get("name") instanceof String)
                    || !(target.getFields().get("state") instanceof String)) continue;
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("containerId", target.getId());
            scalar(reference, target.getFields(), "name"); scalar(reference, target.getFields(), "state");
            reference.put("stackResolution", "unknown");
            boolean resolved = usageStack(domain, child, reference, hostId, candidate.getId());
            if (resolved || hosts.isWorkloadCurrent(account, hostId, candidate.getId(), candidate.getInstanceId(), null)) references.add(reference);
        }
    }

    private boolean usageStack(ApiRequest domain, AuthorizedTarget child, Map<String, Object> reference, Long hostId, Long hostMappingId) {
        Resource target = child.resource(); Object stackId = target.getFields().get("stackId");
        Long serviceId = null; long account = ApiUtils.getPolicy().getAccountId();
        long instanceId = Long.parseLong(rawId(target.getId()).toString());
        if (stackId == null) {
            // Native service containers commonly have no persisted Stack. Use
            // only the same visible serviceIds relationship as the normal UI.
            if (!child.missingPersistedStack() || !child.serviceIdsReadable() || services == null
                    || child.instanceUuid() == null || !child.instanceUuid().matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
                return false;
            List<Long> ids = services.getUsageServiceIds(account, instanceId, child.instanceUuid());
            if (ids == null || ids.size() > 2) throw error(503, "ResourceViewUnavailable");
            if (ids.size() != 1 || ids.getFirst() == null || ids.getFirst() <= 0) return false;
            serviceId = ids.getFirst(); AuthorizedTarget service = usageTarget(domain, "service", serviceId.toString());
            if (service == null) return false;
            stackId = service.resource().getFields().get("stackId");
        }
        if (!(stackId instanceof String || stackId instanceof Number)) return false;
        long capturedStack = Long.parseLong(rawId(stackId.toString()).toString());
        AuthorizedTarget stack = usageTarget(domain, "stack", stackId.toString());
        if (stack == null || !(stack.resource().getFields().get("name") instanceof String name) || name.isBlank() || name.length() > 512)
            return false;
        if (serviceId != null) {
            if (!services.isUsageServiceCurrent(account, instanceId, child.instanceUuid(), serviceId, capturedStack, hostId, hostMappingId)) return false;
        } else if (hostId != null && !hosts.isWorkloadCurrent(account, hostId, hostMappingId, instanceId, capturedStack)) return false;
        reference.put("stackName", name); reference.put("stackResolution", "resolved"); return true;
    }

    private AuthorizedTarget usageTarget(ApiRequest domain, String type, String id) {
        ApiRequest child = new ApiRequest(domain);
        child.setRequestServletContext(domain.getServletContext());
        child.setType(type); child.setId(rawId(id).toString()); child.setMethod("GET");
        child.setAction(null); child.setLink(null); child.setRequestObject(null);
        child.setRequestParams(new LinkedHashMap<>()); child.setConditions(new LinkedHashMap<>()); child.setInclude(null);
        child.setAttribute(DOMAIN_READ_ATTRIBUTE, Boolean.TRUE);
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(domain);
        if (key != null) ApiKeyCredentialContext.attach(child, key);
        Policy owner = keyAuthorization.actor(child);
        ApiContext context = ApiContext.getContext();
        ApiRequest previous = context.getApiRequest(); Object previousPolicy = context.getPolicy();
        try {
            var authority = authenticator.currentAuthorization(owner.getAuthenticatedAsAccountId(), owner.getAccountId(), child);
            child.setSchemaFactory(authority.schemas());
            context.setApiRequest(child); context.setPolicy(authority.policy());
            Schema schema = authority.schemas().getSchema(type);
            if (schema == null || schema.getResourceMethods() == null || !schema.getResourceMethods().contains("GET")) return null;
            ResourceManager manager = managers.getResourceManagerByType(type);
            if (manager == null) return null;
            // A relationship candidate is not a caller-supplied API target.
            // The attested native getById query applies this exact Key guard
            // before materialization; do not synthesize per-hidden-target audit
            // events whose IDs or event count would disclose the relation.
            if (key != null && key.restricted() && !manager.supportsScopedCollectionQuery(keyAccess)) return null;
            Object row = manager.getById(type, child.getId(), new ListOptions(child));
            if (row == null) return null;
            if ("service".equals(type) && row instanceof Service service && !"service".equals(service.getKind())) return null;
            Resource resource = coreDetail(row, child);
            if (resource == null || resource.getId() == null || !sameId(resource.getId(), id)
                    || resource.getFields().get("removed") != null) return null;
            var relation = schema.getResourceFields() == null ? null : schema.getResourceFields().get("serviceIds");
            boolean readable = relation != null && relation.isIncludeInList() && !relation.isReadOnCreateOnly()
                    && (relation.getTransform() == null || relation.getTransform().isBlank());
            return new AuthorizedTarget(resource, "container".equals(type) && readable,
                    row instanceof Instance instance && instance.getStackId() == null, row instanceof Instance instance ? instance.getUuid() : null);
        } catch (ClientVisibleException denied) {
            if (!Set.of("KeyScopeDenied", "KeyPolicyDenied", "OwnerPermissionDenied", "NotFound").contains(denied.getCode())) throw denied;
            return null;
        } finally {
            context.setApiRequest(previous); context.setPolicy(previousPolicy);
        }
    }

    /** Called only after native getById (including object authorization) and the fresh Key decision. */
    private Resource coreDetail(Object row, ApiRequest request) {
        Long id, account; java.util.Date removed;
        if ("volume".equals(request.getType()) && row instanceof Volume volume) {
            id = volume.getId(); account = volume.getAccountId(); removed = volume.getRemoved();
        } else if ("host".equals(request.getType()) && row instanceof Host host) {
            id = host.getId(); account = host.getAccountId(); removed = host.getRemoved();
        } else if ("container".equals(request.getType()) && row instanceof Instance instance && "container".equals(instance.getKind())) {
            id = instance.getId(); account = instance.getAccountId(); removed = instance.getRemoved();
        } else if ("stack".equals(request.getType()) && row instanceof Stack stack) {
            id = stack.getId(); account = stack.getAccountId(); removed = stack.getRemoved();
        } else if ("service".equals(request.getType()) && row instanceof Service service && "service".equals(service.getKind())) {
            id = service.getId(); account = service.getAccountId(); removed = service.getRemoved();
        } else throw error(503, "ResourceViewUnavailable");
        if (id == null || !sameId(id, request.getId()) || account == null
                || account != ApiUtils.getPolicy().getAccountId() || removed != null) throw error(404, "NotFound");
        Schema schema = request.getSchemaFactory().getSchema(request.getType());
        if (schema == null || schema.getResourceFields() == null || !schema.getResourceFields().containsKey("id"))
            throw error(403, "OwnerPermissionDenied");
        List<String> names = "volume".equals(request.getType())
                ? List.of("name", "description", "state", "kind", "created", "removed", "driver", "accessMode", "size", "sizeMb", "isHostPath", "isNative")
                : "host".equals(request.getType()) ? List.of("name", "description", "state", "kind", "created", "hostname",
                        "agentIpAddress", "dockerVersion", "memTotal", "cpuCount", "agentState", "memory", "milliCpu", "localStorageMb")
                : "container".equals(request.getType()) ? List.of("name", "state", "stackId")
                : "service".equals(request.getType()) ? List.of("stackId") : List.of("name");
        // Reuse the exact role-schema Field accessors and native wrapping rules
        // (including aliases and reference formatting),
        // with a bounded field subset. No full DTO/output augmentation runs.
        SchemaImpl projection = new SchemaImpl(); projection.setId(schema.getId()); projection.setParent(schema.getParent());
        Map<String, io.github.ibuildthecloud.gdapi.model.Field> fields = new LinkedHashMap<>();
        fields.put("id", schema.getResourceFields().get("id"));
        for (String name : names) {
            var field = schema.getResourceFields().get(name);
            // Sensitive/create-only accessors must not run, even transiently.
            if (field != null && field.isIncludeInList() && !field.isReadOnCreateOnly()
                    && (field.getTransform() == null || field.getTransform().isBlank())) fields.put(name, field);
        }
        projection.setResourceFields(fields);
        // Native wrapping gives persisted data fields precedence over bean getters.
        // Copy only the already-approved scalar subset, never attachments or transformed values.
        Map<String, Object> nativeFields = DataUtils.getFields(row), additional = new LinkedHashMap<>();
        for (String name : fields.keySet()) {
            Object value = nativeFields.get(name);
            if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof java.util.Date)
                additional.put(name, value);
        }
        Resource wrapped = new WrappedResource(ApiContext.getContext().getIdFormatter(), request.getSchemaFactory(), projection,
                row, additional, Set.of(), "GET", false);
        // Preserve the ordinary HostsFilter state semantics without its unbounded
        // instanceIds/physical-host augmentation or bypassing a hidden state field.
        if (!"v1".equals(request.getVersion()) && row instanceof Host host && wrapped.getFields().containsKey("state")
                && "active".equals(host.getState()) && StringUtils.isNotBlank(host.getAgentState()))
            wrapped.getFields().put("state", host.getAgentState());
        if (row instanceof Instance instance && (!sameId(wrapped.getFields().get("stackId"), instance.getStackId())))
            wrapped.getFields().remove("stackId");
        if (row instanceof Service service && !sameId(wrapped.getFields().get("stackId"), service.getStackId()))
            wrapped.getFields().remove("stackId");
        return wrapped;
    }

    private static boolean sameId(Object left, Object right) {
        return left != null && right != null && rawId(left.toString()).toString().equals(rawId(right.toString()).toString());
    }

    /** No recursive copy: nested credentials, values, payloads and launchConfig stay inside the server. */
    public static Map<String, Object> project(String type, String id, Map<String, Object> fields) {
        if (!READ_TYPES.contains(type) || id == null || fields == null) throw error(503, "ResourceViewUnavailable");
        Map<String, Object> safe = new LinkedHashMap<>(); safe.put("id", id); safe.put("type", type);
        for (String field : COMMON_FIELDS) scalar(safe, fields, field);
        for (String field : FIELDS.getOrDefault(type, List.of())) {
            if ("archived".equals(field)) safe.put(field, Boolean.TRUE.equals(fields.get(field)));
            else scalar(safe, fields, field);
        }
        if (Set.of("service", "loadBalancerService", "dnsService", "externalService").contains(type)
                && fields.get("launchConfig") instanceof Map<?, ?> launch && launch.get("imageUuid") instanceof String image)
            safe.put("imageUuid", image.substring(0, Math.min(image.length(), 512)));
        if ("externalService".equals(type)) safe.putAll(
                io.cattle.platform.iaas.api.auth.change.AiExternalServiceChange.summary(fields));
        if (Set.of("apiKey", "apiKeyRestricted").contains(type) && fields.get("apiKeyPolicy") instanceof Map<?, ?> policy) {
            for (String field : List.of("mode", "purpose", "expiresAt")) {
                Object value = policy.get(field);
                if (value instanceof String text && text.length() <= 128) safe.put(field, text);
            }
        }
        return safe;
    }

    private static void scalar(Map<String, Object> output, Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (value instanceof String text) output.put(field, text.substring(0, Math.min(text.length(), "description".equals(field) ? 1024 : 512)));
        else if (value instanceof Boolean || value instanceof Number) output.put(field, value);
    }

    private static Map<String, Object> input(ApiRequest request) {
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        if (!Set.of("type", "resourceType", "resourceId", "query", "marker", "parentId", "sourcePage").containsAll(input.keySet())
                || !(input.get("resourceType") instanceof String type) || !READ_TYPES.contains(type)) throw error(422, "ResourceViewInputInvalid");
        for (String key : List.of("resourceId", "marker", "parentId")) {
            Object value = input.get(key);
            if (value != null && (!(value instanceof String text) || !text.matches("[A-Za-z0-9_.-]{1,128}"))) throw error(422, "ResourceViewInputInvalid");
        }
        if (input.containsKey("query") && (!(input.get("query") instanceof String query) || query.length() > 256)) throw error(422, "ResourceViewInputInvalid");
        if (input.get("resourceId") != null && (input.get("marker") != null || input.get("query") != null || input.get("parentId") != null))
            throw error(422, "ResourceViewInputInvalid");
        return input;
    }

    private static Object rawId(String id) {
        Object value = ApiContext.getContext().getIdFormatter().parseId(id);
        return value == null ? id : value;
    }

    private static int compareIds(String left, String right) {
        Object a = rawId(left), b = rawId(right);
        try { return Long.compare(Long.parseLong(a.toString()), Long.parseLong(b.toString())); }
        catch (NumberFormatException nonnumeric) { return left.compareTo(right); }
    }

    // ProcessPoolManager exposes an owner-supplied bean identity, not a persisted
    // object with an ID. Its detail is resolved only inside the same authorized
    // collection, never by treating this digest as a database ID or permission.
    private static String poolId(Map<String, Object> fields) {
        if (!(fields.get("poolKey") instanceof String key) || key.isBlank() || key.length() > 512
                || key.chars().anyMatch(value -> value < 32 || value == 127)) throw error(503, "ResourceViewUnavailable");
        try { return "pool-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("process-pool-bean:" + key).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw error(503, "ResourceViewUnavailable"); }
    }

    private static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
