package io.cattle.platform.iaas.api.auth.ai;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyCodec;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyOperations;
import io.cattle.platform.iaas.api.auth.change.AiResourceChangeDomain;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Live Key checks shared by the bounded AI resources, never a new login mechanism. */
public class AiKeyAuthorization {
    @Inject ObjectManager objects;
    @Inject ApiAuthenticator authenticator;
    @Inject ApiKeyTargetResolver targets;
    Clock clock = Clock.systemUTC();
    private final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
    private final ApiKeyPolicyEvaluator evaluator = new ApiKeyPolicyEvaluator();

    public Policy actor(ApiRequest request) {
        Policy owner = ApiUtils.getPolicy();
        if (owner == null) denied("PermissionDenied");
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        return key == null ? owner : live(key, owner.getAccountId(), request).authorization().policy();
    }

    private record Verified(ApiAuthenticator.CurrentAuthorization authorization, ApiKeyPolicy policy) { }

    private Verified live(ApiKeyCredentialContext expected, long accountId, ApiRequest request) {
        Credential credential = objects.loadResource(Credential.class, expected.credentialId());
        if (credential == null || credential.getRemoved() != null || !"active".equals(credential.getState())
                || !Set.of(CredentialConstants.KIND_API_KEY, CredentialConstants.KIND_API_KEY_RESTRICTED).contains(credential.getKind()))
            denied("ApiKeyRevoked");
        if (!Objects.equals(credential.getAccountId(), expected.principalAccountId())) denied("OwnerPermissionDenied");
        ApiKeyPolicy policy;
        try {
            policy = codec.read(credential);
            if (codec.revision(credential) != expected.revision() || !Objects.equals(credential.getKind(), expected.kind()))
                denied("ApiKeyPolicyChanged");
        } catch (IllegalArgumentException invalid) { denied("ApiKeyPolicyInvalid"); return null; }
        if (policy != null && policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) denied("ApiKeyExpired");
        if (policy != null && policy.getMode() == ApiKeyPolicy.Mode.CLOSED) denied("KeyPolicyDenied");
        ApiKeyAiBridgeGuard.requireEnabled(policy);
        return new Verified(authenticator.currentAuthorization(expected.principalAccountId(), accountId, request), policy);
    }

    public Policy authorizeService(ApiRequest request, Service service, String operation) {
        Policy owner = ApiUtils.getPolicy();
        if (owner == null) denied("PermissionDenied");
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null) return owner;
        return authorize(key, owner.getAccountId(), service, operation, request);
    }

    private Policy authorize(ApiKeyCredentialContext key, long accountId, Service service, String operation, ApiRequest request) {
        var verified = live(key, accountId, request);
        var current = verified.authorization();
        Schema schema = current.schemas().getSchema("service");
        boolean allowed = service != null && service.getRemoved() == null
                && Objects.equals(service.getAccountId(), accountId) && current.policy().authorizeObject(service) != null
                && schema != null && schema.getResourceMethods() != null && schema.getResourceMethods().contains("GET");
        if ("upgrade".equals(operation)) allowed &= schema != null && schema.getResourceActions() != null
                && schema.getResourceActions().keySet().containsAll(Set.of("upgrade", "finishupgrade"));
        else if ("update".equals(operation)) allowed &= schema != null && schema.getResourceMethods() != null
                && schema.getResourceMethods().contains("PUT") && schema.getResourceFields() != null
                && schema.getResourceFields().get("description") != null && schema.getResourceFields().get("description").isUpdate();
        else if (!"read".equals(operation)) allowed = false;
        if (!allowed) denied("OwnerPermissionDenied");
        ApiKeyPolicy policy = verified.policy();
        if (policy != null) {
            var decision = evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, operation, true,
                    List.of(targets.resolveObject("service", service, current.policy()))), clock.instant());
            if (!decision.allowed()) denied(decision.reason().getCode());
        }
        return current.policy();
    }

    public void authorizeBound(Map<String, Object> plan, Service service, String operation, ApiRequest request) {
        if (!plan.containsKey("credentialId")) return;
        if (!(plan.get("credentialId") instanceof Number)) denied("OwnerPermissionDenied");
        long credentialId = ((Number) plan.get("credentialId")).longValue();
        Policy owner = ApiUtils.getPolicy();
        if (owner == null || !(plan.get("credentialRevision") instanceof Number revision)
                || !(plan.get("credentialKind") instanceof String kind)
                || !(plan.get("actorId") instanceof Number actor) || actor.longValue() != owner.getAuthenticatedAsAccountId())
            denied("OwnerPermissionDenied");
        authorize(new ApiKeyCredentialContext(credentialId, ((Number) plan.get("actorId")).longValue(),
                (String) plan.get("credentialKind"), ((Number) plan.get("credentialRevision")).longValue(), null),
                owner.getAccountId(), service, operation, request);
    }

    /** A human may approve a Key-bound plan, but cannot lend the Key broader rights. */
    public void authorizeBoundDomain(Map<String, Object> plan, ApiRequest domain) {
        if (!plan.containsKey("credentialId")) return;
        Policy owner = ApiUtils.getPolicy();
        if (owner == null || !AiResourceChangeDomain.trustedDomain(domain)
                || !(plan.get("credentialId") instanceof Number id)
                || !(plan.get("credentialRevision") instanceof Number revision)
                || !(plan.get("credentialKind") instanceof String kind)
                || !(plan.get("actorId") instanceof Number actor) || actor.longValue() != owner.getAuthenticatedAsAccountId())
            denied("OwnerPermissionDenied");
        var verified = live(new ApiKeyCredentialContext(((Number) plan.get("credentialId")).longValue(),
                ((Number) plan.get("actorId")).longValue(), (String) plan.get("credentialKind"),
                ((Number) plan.get("credentialRevision")).longValue(), null), owner.getAccountId(), domain);
        var operation = ApiKeyOperations.of(domain);
        if (!operation.registered()) denied("UnknownOperation");
        if (verified.policy() != null) {
            var decision = evaluator.evaluate(verified.policy(), new ApiKeyPolicyEvaluator.Request(true, operation.id(), true,
                    targets.resolve(domain, verified.authorization().policy())), clock.instant());
            if (!decision.allowed()) denied(decision.reason().getCode());
            if (verified.policy().getMode() != ApiKeyPolicy.Mode.FULL) {
                var children = targets.stackLifecycleTargets(domain, verified.authorization().policy());
                if (!children.isEmpty()) {
                    var childDecision = evaluator.evaluateAuthorizedDependency(verified.policy(),
                            new ApiKeyPolicyEvaluator.Request(true, operation.id(), true, children), clock.instant());
                    if (!childDecision.allowed()) denied(childDecision.reason().getCode());
                }
            }
            var references = targets.references(domain, verified.authorization().policy());
            if (!references.isEmpty()) {
                var read = evaluator.evaluate(verified.policy(), new ApiKeyPolicyEvaluator.Request(true, "read", true, references), clock.instant());
                if (!read.allowed()) denied(read.reason().getCode());
            }
        }
    }

    /** Process capture must see the real domain request, while retaining its verified Key. */
    public static <T> T dispatch(ApiRequest original, ApiRequest domain, Supplier<T> action) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(original);
        if (key == null) return action.get();
        ApiContext context = ApiContext.getContext();
        if (context == null) throw new ClientVisibleException(503, "KeyAuthorizationUnavailable");
        ApiRequest previous = context.getApiRequest();
        ApiKeyCredentialContext.attach(domain, key);
        for (String name : List.of("keyId", "policyRevision", "requestId", "decision", "reason", "admitted"))
            domain.setAttribute("apiKey.audit." + name, original.getAttribute("apiKey.audit." + name));
        domain.setAttribute("apiKey.audit.operation", "POST".equals(domain.getMethod()) ? "upgrade" : "update");
        context.setApiRequest(domain);
        try { return action.get(); }
        finally { context.setApiRequest(previous); }
    }

    private static void denied(String code) { throw new ClientVisibleException(403, code, code, null); }
}
