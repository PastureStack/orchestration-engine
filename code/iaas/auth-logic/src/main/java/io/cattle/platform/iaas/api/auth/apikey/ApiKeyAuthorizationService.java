package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ApiResourceAccess;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.LinkedHashMap;
import io.cattle.platform.object.ObjectManager;
import jakarta.inject.Inject;
import org.jooq.Condition;
import org.jooq.Table;

/** Attenuation after live authentication, never a replacement for owner RBAC. */
public class ApiKeyAuthorizationService implements ApiResourceAccess {
    @Inject ApiKeyTargetResolver targets;
    @Inject List<ApiKeyAuditSink> auditSinks;
    @Inject ResourceManagerLocator resourceManagers;
    @Inject ObjectManager objectManager;
    Clock clock = Clock.systemUTC();
    private final ApiKeyPolicyEvaluator evaluator = new ApiKeyPolicyEvaluator();

    public void authorize(ApiRequest request, Policy owner) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null) return;
        ApiKeyOperations.Operation operation = ApiKeyOperations.of(request);
        request.setAttribute("apiKey.audit.operation", operation.id());
        request.setAttribute("apiKey.audit.targetType", request.getType());
        request.setAttribute("apiKey.audit.targetId", request.getId());
        request.setAttribute("apiKey.audit.requestId", UUID.randomUUID().toString());
        request.setAttribute("apiKey.audit.preview", "apiKeyPolicyPreview".equals(request.getType()));
        String reason = "KeyFullAccess";
        boolean allowed = true;
        try {
            ApiKeyPolicy policy = key.policy();
            ApiKeyAiBridgeGuard.check(request, policy);
            if (io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchDelegation.bootstrap(request)) {
                // Only an authenticated launcher with a verified, live, claimed
                // exact graph reaches this role-metadata / exact-inspect path.
                request.setAttribute("apiKey.audit.operation", "read"); record(request, owner, true, "CatalogLaunchOwnerMetadataAllowed");
                return;
            }
            if (("aiServiceUpgradePlan".equals(request.getType()) || "aiResourceChangePlan".equals(request.getType())
                    || "aiCatalogStackLaunchPlan".equals(request.getType()))
                    && !("GET".equals(request.getMethod()) && request.getId() != null && request.getAction() == null && request.getLink() == null
                    || "POST".equals(request.getMethod()) && request.getLink() == null
                    && (request.getId() == null && request.getAction() == null || request.getId() != null && "apply".equals(request.getAction()))))
                throw forbidden("AiKeyConfirmationForbidden");
            // Legacy and full preserve the original RBAC pipeline. No operation
            // registry, ancestry inference or new schema whitelist limits them.
            if (policy != null && policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) {
                throw forbidden("ApiKeyExpired");
            }
            if (key.restricted() && delegatesUnboundAuthority(request)) throw forbidden("ApiKeyRestrictedDelegationUnsupported");
            if (("aiToolCallEvent".equals(request.getType()) || "aiResourceView".equals(request.getType()) || "aiCatalogMetadataView".equals(request.getType())
                    || "aiCatalogStackLaunchOptions".equals(request.getType())) && "POST".equals(request.getMethod())
                    && request.getId() == null && request.getAction() == null && request.getLink() == null) {
                if (!ownerAllows(request)) throw forbidden("OwnerPermissionDenied");
                if (policy != null && policy.getMode() == ApiKeyPolicy.Mode.CLOSED) throw forbidden("KeyPolicyDenied");
                // A view's actual domain request must still pass this service
                // and its ordinary SQL-scoped manager before safe projection.
                record(request, owner, true, "aiCatalogMetadataView".equals(request.getType()) ? "AiCatalogMetadataAdmission"
                        : "aiResourceView".equals(request.getType()) ? "AiResourceViewAdmission" : "AiToolMetadataAllowed");
                return;
            }
            if ("aiResourceChangePlan".equals(request.getType()) || "aiCatalogStackLaunchPlan".equals(request.getType())) {
                if (!ownerAllows(request)) throw forbidden("OwnerPermissionDenied");
                if (policy != null && policy.getMode() == ApiKeyPolicy.Mode.CLOSED) throw forbidden("KeyPolicyDenied");
                // The fixed adapter performs the real create/update/delete
                // authorization before a durable claim or domain dispatch.
                record(request, owner, true, "aiCatalogStackLaunchPlan".equals(request.getType()) ? "AiCatalogLaunchAdmission" : "AiResourceChangeAdmission");
                return;
            }
            if (policy != null && policy.getMode() != ApiKeyPolicy.Mode.FULL) {
                if (!ownerAllows(request)) throw forbidden("OwnerPermissionDenied");
                if (policy.getMode() == ApiKeyPolicy.Mode.CLOSED) throw forbidden("KeyPolicyDenied");
                if (!operation.registered()) throw forbidden("UnknownOperation");
                if (delegatesUnboundAuthority(request)) throw forbidden("ApiKeyRestrictedDelegationUnsupported");
                if (changesSecurityAuthority(request)) throw forbidden("ApiKeyRestrictedDelegationUnsupported");
                boolean collection = request.getId() == null && "read".equals(operation.id());
                if (collection) {
                    // Synthetic managers have no row-scoping query. Never return
                    // unfiltered data for a partially granted collection.
                    var manager = resourceManagers.getResourceManagerByType(request.getType());
                    boolean sql = manager != null && manager.supportsScopedCollectionQuery(this);
                    // ProjectResourceManager obtains an already RBAC-filtered,
                    // unpaginated list from AuthDao; the common list guard below
                    // scopes it before any response is constructed.
                    sql |= "project".equals(request.getType());
                    if (!sql && !unscopedReadAllowed(policy)) throw forbidden("KeyScopeDenied");
                } else {
                    check(policy, operation.id(), targets.resolve(request, owner));
                    List<ApiKeyPolicyEvaluator.Target> children = targets.stackLifecycleTargets(request, owner);
                    if (!children.isEmpty()) {
                        var decision = evaluator.evaluateAuthorizedDependency(policy,
                                new ApiKeyPolicyEvaluator.Request(true, operation.id(), true, children), clock.instant());
                        if (!decision.allowed()) throw forbidden(decision.reason().getCode());
                    }
                    List<ApiKeyPolicyEvaluator.Target> references = targets.references(request, owner);
                    if (!references.isEmpty()) check(policy, "read", references);
                }
                reason = collection ? "KeyScopedCollection" : "KeyRuleAllowed";
            }
        } catch (ClientVisibleException error) {
            allowed = false;
            reason = error.getCode() == null ? "OwnerPermissionDenied" : error.getCode();
            record(request, owner, false, reason);
            throw error;
        }
        record(request, owner, allowed, reason);
    }

    private void check(ApiKeyPolicy policy, String operation, List<ApiKeyPolicyEvaluator.Target> resolved) {
        var decision = evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, operation, true, resolved), clock.instant());
        if (!decision.allowed()) throw forbidden(decision.reason().getCode());
    }

    private boolean ownerAllows(ApiRequest request) {
        Schema schema = request.getSchemaFactory().getSchema(request.getType());
        if (schema == null) return false;
        if (request.getAction() != null) {
            Map<String, ?> actions = request.getId() == null ? schema.getCollectionActions() : schema.getResourceActions();
            return actions != null && actions.containsKey(request.getAction());
        }
        String method = request.getMethod();
        if ("HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) method = "GET";
        List<String> methods = request.getId() == null ? schema.getCollectionMethods() : schema.getResourceMethods();
        return methods != null && methods.contains(method);
    }

    private boolean delegatesUnboundAuthority(ApiRequest request) {
        String type = ApiKeyQueryScopes.canonical(request.getType());
        // Never exchange a restricted Key for an unrestricted JWT or child Key.
        if ("token".equals(type)) return true;
        return "apiKey".equals(type) && (!"GET".equalsIgnoreCase(request.getMethod())
                || Set.of("pem", "certificate").contains(String.valueOf(request.getLink()).toLowerCase(java.util.Locale.ROOT)));
    }

    /** A business-resource grant cannot bootstrap a new, unscoped identity. */
    private boolean changesSecurityAuthority(ApiRequest request) {
        if (Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod().toUpperCase(Locale.ROOT))) return false;
        String type = request.getType();
        if (Set.of("mfaOperation", "mfaSettings", "authIdentityOperation", "localAuthConfig", "password").contains(type)) return true;
        if ("setting".equals(type) || "activeSetting".equals(type)) {
            // Resolve numeric/encoded IDs to the same authoritative name that
            // SettingManager will update; a body name cannot shadow a URL ID.
            String name = targets.settingName(request);
            return name == null || name.startsWith("api.auth.") || name.startsWith("api.security.")
                    || "system.stack.auth.url".equals(name);
        }
        if (!(request.getRequestObject() instanceof Map<?, ?> body)) return false;
        if ("project".equals(type) && request.getId() == null) {
            Object members = body.get("members");
            if (members != null && (!(members instanceof List<?> list) || !list.isEmpty())) return true;
        }
        if (!"account".equals(type)) return false;
        Account existing = null;
        boolean loaded = false;
        Map<Object, Object> metadata = new LinkedHashMap<>(body);
        boolean hasSecurityFields = false;
        for (Map.Entry<?, ?> entry : body.entrySet()) {
            if (!(entry.getKey() instanceof String field)) continue;
            String property = switch (field.toLowerCase(Locale.ROOT)) {
                case "kind" -> "kind";
                case "externalid" -> "externalId";
                case "externalidtype" -> "externalIdType";
                default -> null;
            };
            if (property == null) continue;
            hasSecurityFields = true;
            if (!loaded) {
                loaded = true;
                if (request.getId() != null) {
                    String rawId = request.getId();
                    String id = rawId.matches("[0-9]+") ? rawId
                            : ApiContext.getContext().getIdFormatter().parseId(rawId);
                    if (id != null && id.matches("[0-9]+")) {
                        try {
                            Long.parseLong(id);
                            existing = objectManager.loadResource(Account.class, id);
                        } catch (NumberFormatException invalid) {
                            return true;
                        }
                    }
                }
            }
            // Full account DTOs include unchanged identity fields. Preserve
            // ordinary metadata edits, but never silently accept a role or
            // identity change (including explicit clearing) with a scoped Key.
            if (existing == null || !Objects.equals(ObjectUtils.getPropertyIgnoreErrors(existing, property), entry.getValue())) return true;
            metadata.remove(entry.getKey());
        }
        // Do not replay the validated snapshot after a concurrent legitimate
        // downgrade/rebind. The later manager may reload a newer Account.
        if (hasSecurityFields) request.setRequestObject(metadata);
        return false;
    }

    private boolean unscopedReadAllowed(ApiKeyPolicy policy) {
        boolean allow = policy.getDefaultEffect() == ApiKeyPolicy.Effect.ALLOW;
        for (ApiKeyPolicy.Rule rule : policy.getRules()) {
            if (!rule.operations().contains("read")) continue;
            if (rule.effect() == ApiKeyPolicy.Effect.DENY) return false;
            if (rule.scope().kind() == ApiKeyPolicy.ScopeKind.GLOBAL) allow = true;
        }
        return allow;
    }

    @Override public List<?> filterCollection(ApiRequest request, List<?> values) {
        ApiKeyCredentialContext key = request == null ? null : ApiKeyCredentialContext.get(request);
        if (key == null || key.policy() == null || key.policy().getMode() == ApiKeyPolicy.Mode.FULL) return values;
        Policy owner = (Policy) ApiContext.getContext().getPolicy();
        List<Object> allowed = new ArrayList<>();
        for (Object value : values) {
            try {
                String type = ApiKeyQueryScopes.canonical(objectManager.getType(value));
                var target = targets.resolveObject(type, value, owner);
                if (evaluator.evaluate(key.policy(), new ApiKeyPolicyEvaluator.Request(true, "read", true,
                        List.of(target)), clock.instant()).allowed()) allowed.add(value);
            } catch (ClientVisibleException ignored) { /* omit inaccessible rows, never disclose their IDs */ }
        }
        return allowed;
    }

    private void record(ApiRequest request, Policy owner, boolean allowed, String reason) {
        request.setAttribute("apiKey.audit.decision", allowed ? "ALLOW" : "DENY");
        request.setAttribute("apiKey.audit.reason", reason);
        if (auditSinks == null || auditSinks.isEmpty()) throw unavailable();
        try {
            for (ApiKeyAuditSink sink : auditSinks) sink.recordDecision(request, owner);
        } catch (RuntimeException failure) {
            // No side effect has run yet. Do not expose a database/path/secret in
            // this stable, user-facing failure; operators use requestId instead.
            throw unavailable();
        }
    }

    @Override public Condition constrain(ApiRequest request, SchemaFactory factory, String type, Table<?> table) {
        return constrainOperation(request, type, table, "read");
    }

    @Override public Condition constrainMutationTarget(ApiRequest request, SchemaFactory factory, String type,
            Table<?> table, ListOptions.MutationTarget target) {
        String wireType = queryType(request, type, table);
        var operation = ApiKeyOperations.of(request);
        if (target == null || !target.matches(request, request.getType(), request.getId())
                || !operation.registered()
                // A matched handler is a lookup purpose, not a new operation
                // registry. Unknown and ordinary relationship links stay READ.
                || target.isNamedLink() && !Set.of("export", "exec", "logs").contains(operation.id())
                || !sameQueryResource(factory, target.type(), type, wireType)) {
            return constrain(request, factory, type, table);
        }
        Condition restriction = constrainOperation(request, wireType, table, operation.id());
        // Full/legacy retain exactly the existing RBAC pipeline. A custom Key
        // can load only this operand, never siblings or referenced resources.
        if (restriction == null) return null;
        var id = table.field("id", Long.class);
        if (id == null) return org.jooq.impl.DSL.falseCondition();
        // ValidationHandler has already decoded the URL ID before manager
        // dispatch. Policy scope IDs still use the strict typed-ID parser.
        return restriction.and(id.eq(mutationId(target.id())));
    }

    private String queryType(ApiRequest request, String type, Table<?> table) {
        // The table's core type, not a client's filter, determines row ancestry.
        if (table.getName().equals("account") && "project".equals(request.getType())) return "project";
        return type.equals("credential") ? request.getType() : type;
    }

    private boolean sameQueryResource(SchemaFactory factory, String operandType, String coreType, String wireType) {
        if (ApiKeyQueryScopes.canonical(operandType).equals(ApiKeyQueryScopes.canonical(wireType))) return true;
        // Derived schemas (for example composeProject) must prove their actual
        // persisted record type. A caller-provided alias string is not a grant.
        Class<?> operandClass = factory.getSchemaClass(operandType);
        return operandClass != null && objectManager != null
                && operandClass.equals(objectManager.getSchemaFactory().getSchemaClass(coreType));
    }

    private Long mutationId(String raw) {
        try {
            if (!raw.matches("[1-9][0-9]*")) throw new NumberFormatException();
            return Long.valueOf(raw);
        } catch (NumberFormatException invalid) {
            throw forbidden("KeyScopeDenied");
        }
    }

    private Condition constrainOperation(ApiRequest request, String type, Table<?> table, String operation) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null || key.policy() == null || key.policy().getMode() == ApiKeyPolicy.Mode.FULL) return null;
        if (key.policy().getMode() == ApiKeyPolicy.Mode.CLOSED) throw forbidden("KeyPolicyDenied");
        if (key.policy().getExpiresAt() != null && !clock.instant().isBefore(key.policy().getExpiresAt())) throw forbidden("ApiKeyExpired");
        return ApiKeyQueryScopes.condition(key.policy(), queryType(request, type, table), table, operation, targets::parseScopeId);
    }

    private static ClientVisibleException forbidden(String code) {
        return new ClientVisibleException(ResponseCodes.FORBIDDEN, code,
                "This API key does not permit the requested operation or resource. Check its access policy and your account permissions.", null);
    }
    private static ClientVisibleException unavailable() {
        return new ClientVisibleException(ResponseCodes.SERVICE_UNAVAILABLE, "AuditUnavailable",
                "The audit log cannot safely accept this operation. No change was made; try again later.", null);
    }
}
