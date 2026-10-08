package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.resource.NamedResourceIdentity;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
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
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.FieldType;
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
            Long stackId = instance.getStackId();
            if (instance.getServiceId() != null) {
                Service service = objectManager.loadResource(Service.class, instance.getServiceId());
                if (service == null || !Objects.equals(instance.getAccountId(), service.getAccountId())
                        || (stackId != null && !stackId.equals(service.getStackId()))) denied("KeyScopeDenied");
                stackId = service.getStackId();
            }
            return child("container", id, instance.getAccountId(), stackId, owner);
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
        Long declaredAccount = parentId("project", body.get("accountId"));
        Long declaredStack = parentId("stack", body.get("stackId"));
        if (declaredStack != null) {
            Stack stack = objectManager.loadResource(Stack.class, declaredStack);
            if (stack == null || (declaredAccount != null && !declaredAccount.equals(stack.getAccountId()))) denied("KeyScopeDenied");
            targets.add(stack("stack", format("stack", stack.getId()), stack, owner));
        }
        Long serviceId = parentId("service", body.get("serviceId"));
        if (serviceId != null) {
            Service service = objectManager.loadResource(Service.class, serviceId);
            if (service == null || (declaredStack != null && !declaredStack.equals(service.getStackId()))
                    || (declaredAccount != null && !declaredAccount.equals(service.getAccountId()))) denied("KeyScopeDenied");
            targets.add(resolveObject("service", service, owner));
        }
        Long instanceId = parentId("container", body.get("instanceId"));
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
        Schema schema = request.getSchemaFactory().getSchema(request.getType());
        if (schema != null && request.getAction() != null && schema.getResourceActions() != null
                && schema.getResourceActions().get(request.getAction()) != null) {
            schema = request.getSchemaFactory().getSchema(schema.getResourceActions().get(request.getAction()).getInput());
        }
        collectReferences(request, schema, request.getRequestObject(), owner, targets, 0);
        return targets;
    }

    private void collectReferences(ApiRequest request, Schema schema, Object raw, Policy owner,
            List<ApiKeyPolicyEvaluator.Target> targets, int depth) {
        if (!(raw instanceof Map<?, ?> body) || schema == null) return;
        if (depth > 16 || targets.size() > 1024) denied("KeyScopeDenied");
        if (schema.getResourceFields() == null) return;
        for (Map.Entry<String, Field> entry : schema.getResourceFields().entrySet()) {
            Field field = entry.getValue();
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
                    collectReferences(request, request.getSchemaFactory().getSchema(nested), item, owner, targets, depth + 1);
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
