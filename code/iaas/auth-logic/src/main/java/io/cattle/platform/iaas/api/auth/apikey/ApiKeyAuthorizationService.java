package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ApiResourceAccess;
import io.cattle.platform.api.auth.Policy;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.ArrayList;
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
            // Legacy and full preserve the original RBAC pipeline. No operation
            // registry, ancestry inference or new schema whitelist limits them.
            if (policy != null && policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) {
                throw forbidden("ApiKeyExpired");
            }
            if (key.restricted() && delegatesUnboundAuthority(request)) throw forbidden("ApiKeyRestrictedDelegationUnsupported");
            if (policy != null && policy.getMode() != ApiKeyPolicy.Mode.FULL) {
                if (!ownerAllows(request)) throw forbidden("OwnerPermissionDenied");
                if (policy.getMode() == ApiKeyPolicy.Mode.CLOSED) throw forbidden("KeyPolicyDenied");
                if (!operation.registered()) throw forbidden("UnknownOperation");
                if (delegatesUnboundAuthority(request)) throw forbidden("ApiKeyRestrictedDelegationUnsupported");
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
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null || key.policy() == null || key.policy().getMode() == ApiKeyPolicy.Mode.FULL) return null;
        if (key.policy().getMode() == ApiKeyPolicy.Mode.CLOSED) throw forbidden("KeyPolicyDenied");
        if (key.policy().getExpiresAt() != null && !clock.instant().isBefore(key.policy().getExpiresAt())) throw forbidden("ApiKeyExpired");
        // The table's core type, not a client's filter, determines row ancestry.
        String wireType = table.getName().equals("account") && "project".equals(request.getType()) ? "project" : type;
        if (type.equals("credential")) wireType = request.getType();
        return ApiKeyQueryScopes.condition(key.policy(), wireType, table, "read", targets::parseScopeId);
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
