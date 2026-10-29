package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyCodec;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Live owner and original-Key checks for this one typed launch workflow. */
public class AiCatalogLaunchAccess extends io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao {
    private static final String PERMIT = "ai.catalog.launch.domain.permit";
    private record Permit(String type, String method, String id, String action) { }
    public record Authorization(ApiAuthenticator.CurrentAuthorization current, ApiKeyCredentialContext key) { }
    @Inject AiKeyAuthorization keys;
    @Inject ApiAuthenticator authenticator;
    @Inject ApiKeyAuthorizationService keyAccess;
    @Inject ObjectManager objects;
    @Inject ResourceManagerLocator managers;
    @Inject ValidationHandler validation;
    @Inject AiCatalogLaunchGraph graphs;
    @Inject @jakarta.inject.Named("DefaultIdFormatter") io.github.ibuildthecloud.gdapi.id.IdFormatter idFormatter;
    Clock clock = Clock.systemUTC();
    final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
    final ApiKeyPolicyEvaluator evaluator = new ApiKeyPolicyEvaluator();

    public static boolean trustedDomain(ApiRequest request) {
        if (request == null || request.getLink() != null || !(request.getAttribute(PERMIT) instanceof Permit permit)) return false;
        boolean sameId = Objects.equals(permit.id(), request.getId());
        if (!sameId && permit.id() != null && request.getId() != null && request.getId().matches("[1-9][0-9]*")
                && ApiContext.getContext() != null && ApiContext.getContext().getIdFormatter() != null)
            sameId = request.getId().equals(ApiContext.getContext().getIdFormatter().parseId(permit.id()));
        return permit.type().equals(request.getType()) && permit.method().equals(request.getMethod())
                && sameId && Objects.equals(permit.action(), request.getAction());
    }

    void markDomain(ApiRequest request) {
        request.setAttribute(PERMIT, new Permit(request.getType(), request.getMethod(), request.getId(), request.getAction()));
    }

    public Policy actor(ApiRequest request) {
        Policy actor = keys.actor(request);
        current(actor.getAuthenticatedAsAccountId(), actor.getAccountId(), request);
        return actor;
    }

    public ApiAuthenticator.CurrentAuthorization current(long actor, long project, ApiRequest request) {
        var current = authenticator.currentAuthorization(actor, project, request);
        Account account = objects.loadResource(Account.class, project);
        if (account == null || account.getRemoved() != null || !"active".equals(account.getState()) || !"project".equals(account.getKind()))
            throw error(403, "OwnerPermissionDenied");
        return current;
    }

    /** Only persisted, already ownership-checked plans may supply the expected binding. */
    public Authorization bound(Map<String, Object> plan, ApiRequest request) {
        long actor = positive(plan.get("actorId")), project = positive(plan.get("accountId"));
        var current = current(actor, project, request);
        if (plan.get("credentialId") == null) return new Authorization(current, null);
        long id = positive(plan.get("credentialId"));
        if (!(plan.get("credentialRevision") instanceof Number expectedRevision) || expectedRevision.longValue() < 0
                || expectedRevision.doubleValue() != expectedRevision.longValue()) throw error(403, "OwnerPermissionDenied");
        long revision = expectedRevision.longValue();
        // Current locking read, not an older ordinary SELECT from an API's RR
        // snapshot. Keep this credential stable until the surrounding write commits.
        Credential credential = create().selectFrom(io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL)
                .where(io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL.ID.eq(id)).forUpdate().fetchOne();
        if (credential == null || credential.getRemoved() != null || !"active".equals(credential.getState())
                || !Set.of(CredentialConstants.KIND_API_KEY, CredentialConstants.KIND_API_KEY_RESTRICTED).contains(credential.getKind()))
            throw error(403, "ApiKeyRevoked");
        if (!Objects.equals(credential.getAccountId(), actor)) throw error(403, "OwnerPermissionDenied");
        ApiKeyPolicy policy;
        try {
            policy = codec.read(credential);
            if (codec.revision(credential) != revision || !Objects.equals(credential.getKind(), plan.get("credentialKind")))
                throw error(403, "ApiKeyPolicyChanged");
        } catch (IllegalArgumentException invalid) { throw error(403, "ApiKeyPolicyInvalid"); }
        if (policy != null && policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) throw error(403, "ApiKeyExpired");
        if (policy != null && policy.getMode() == ApiKeyPolicy.Mode.CLOSED) throw error(403, "KeyPolicyDenied");
        ApiKeyAiBridgeGuard.requireEnabled(policy);
        return new Authorization(current, new ApiKeyCredentialContext(id, actor, credential.getKind(), revision, policy));
    }

    public void catalog(Authorization authorization) {
        ApiKeyPolicy policy = authorization.key() == null ? null : authorization.key().policy();
        if (policy == null) return;
        var decision = evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, "read", true,
                List.of(ApiKeyPolicyEvaluator.Target.platformResource("catalogTemplate", "catalog-launch"))), clock.instant());
        if ((decision.allowed() || decision.reason() == ApiKeyPolicyEvaluator.Reason.SCOPE_DENIED)
                && policy.getMode() != ApiKeyPolicy.Mode.FULL && policy.getRules().stream().anyMatch(rule -> rule.operations().contains("read")
                && rule.scope().kind() == ApiKeyPolicy.ScopeKind.RESOURCE
                && Set.of("catalogTemplate", "catalogTemplateVersion", "catalog").contains(rule.scope().resourceType())))
            throw error(422, "CatalogMetadataFineGrainedUnsupported");
        if (!decision.allowed()) throw error(403, decision.reason().getCode());
    }

    /** A prospective child action is checked on its actual Service role, with
     * the real destination Project as Key scope. This never requires Project PUT.
     */
    void childActions(Authorization authorization, String type, boolean startsServices) {
        Schema service = authorization.current().schemas().getSchema(type);
        if (service == null || service.getResourceActions() == null || !service.getResourceActions().containsKey("setservicelinks")
                || startsServices && !service.getResourceActions().containsKey("activate")) throw error(403, "OwnerPermissionDenied");
        destination(authorization, "update");
    }

    void rootCreate(Authorization authorization) {
        Schema stack = authorization.current().schemas().getSchema("stack");
        if (stack == null || stack.getCollectionMethods() == null || !stack.getCollectionMethods().contains("POST")) throw error(403, "OwnerPermissionDenied");
        destination(authorization, "create");
    }

    /** The normal Compose owner reads back the new graph; create/update never imply this read authority. */
    void launchReads(Authorization authorization, boolean hasVolumes) {
        destination(authorization, "read");
        for (String type : hasVolumes ? List.of("project", "stack", "service", "volumeTemplate") : List.of("project", "stack", "service")) {
            Schema schema = authorization.current().schemas().getSchema(type);
            if (schema == null || schema.getResourceMethods() == null || !schema.getResourceMethods().contains("GET"))
                throw error(403, "OwnerPermissionDenied");
        }
        for (String type : hasVolumes ? List.of("schema", "service", "serviceConsumeMap", "volumeTemplate") : List.of("schema", "service",
                "serviceConsumeMap")) {
            Schema schema = authorization.current().schemas().getSchema(type);
            if (schema == null || schema.getCollectionMethods() == null || !schema.getCollectionMethods().contains("GET"))
                throw error(403, "OwnerPermissionDenied");
        }
    }

    private void destination(Authorization authorization, String operation) {
        ApiKeyPolicy policy = authorization.key() == null ? null : authorization.key().policy();
        if (policy == null) return;
        String project = project(authorization.current().policy());
        var decision = evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, operation, true,
                List.of(ApiKeyPolicyEvaluator.Target.projectResource("project", project, project))), clock.instant());
        if (!decision.allowed()) throw error(403, decision.reason().getCode());
    }

    public <T> T domain(ApiRequest original, Authorization authorization, String type, String method, String id, String action,
            Map<String, Object> body, java.util.function.Function<ApiRequest, T> operation) {
        return domain(original, authorization, type, method, id, action, body, validation, operation);
    }

    /** Only this non-dispatching preflight may defer the exact new Stack reference. Public schemas are never changed. */
    void preflightChild(ApiRequest original, Authorization authorization, Map<String, Object> plan, String type, Map<String, Object> body) {
        if (!Set.of("service", "volumeTemplate").contains(type) || !"$createdStack".equals(body.get("stackId"))
                || plan.get("internalStackId") != null || !(plan.get("id") instanceof String planId)
                || !planId.matches("aicataloglaunch-[0-9a-f-]{36}")
                || positive(plan.get("actorId")) != authorization.current().policy().getAuthenticatedAsAccountId()
                || positive(plan.get("accountId")) != authorization.current().policy().getAccountId()) throw error(403, "OwnerPermissionDenied");
        rootCreate(authorization);
        Schema schema = authorization.current().schemas().getSchema(type);
        var parent = schema == null ? null : schema.getResourceFields().get("stackId");
        if (parent == null || !parent.isCreate() || !Set.of("reference[stack]", "reference[environment]").contains(parent.getType()))
            throw error(403, "OwnerPermissionDenied");
        // The permit exists only in this call's validator closure, bound to the
        // typed parent and exact child bytes. No request/body/header flag grants it.
        String bodyDigest = graphs.digest(body);
        ValidationHandler prospective = new ValidationHandler() {
            @Override protected void validateOperationField(Schema candidate, ApiRequest request, boolean create, ValidationContext context) {
                Map<String, Object> restored = map(request.getRequestObject()); restored.put("stackId", "$createdStack");
                if (!create || request.getId() != null || request.getAction() != null || !type.equals(request.getType())
                        || candidate != schema || !bodyDigest.equals(graphs.digest(restored))
                        || !(candidate instanceof io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl concrete))
                    throw AiCatalogLaunchInput.error(403, "OwnerPermissionDenied");
                var local = new io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl(concrete);
                local.getResourceFields().remove("stackId");
                super.validateOperationField(local, request, true, context);
            }
        };
        prospective.setReferenceValidator(validation.getReferenceValidator()); prospective.setSupportedMethods(validation.getSupportedMethods());
        Map<String, Object> future = new LinkedHashMap<>(body); future.remove("stackId");
        // The callback is fixed and cannot reach a ResourceManager. All other
        // validation, existing references and destination Key checks still run.
        domain(original, authorization, type, "POST", null, null, future, prospective, request -> null);
    }

    private <T> T domain(ApiRequest original, Authorization authorization, String type, String method, String id, String action,
            Map<String, Object> body, ValidationHandler validator, java.util.function.Function<ApiRequest, T> operation) {
        ApiRequest request = new ApiRequest(original);
        request.setRequestServletContext(original.getServletContext());
        request.setType(type); request.setId(id); request.setMethod(method); request.setAction(action); request.setLink(null);
        request.setRequestObject(new LinkedHashMap<>(body)); request.setRequestParams(new LinkedHashMap<>()); request.setConditions(new LinkedHashMap<>());
        request.setPagination(null); request.setSort(null); request.setInclude(null);
        request.setSchemaFactory(authorization.current().schemas());
        request.setAttribute(PERMIT, new Permit(type, method, id, action));
        if (authorization.key() != null) ApiKeyCredentialContext.attach(request, authorization.key());
        Schema schema = request.getSchemaFactory().getSchema(type);
        if (schema == null) throw error(403, "OwnerPermissionDenied");
        if (("POST".equals(method) && action == null || "PUT".equals(method)) && !"schema".equals(type)) {
            for (String field : body.keySet()) {
                if ("type".equals(field)) continue;
                var capability = schema.getResourceFields().get(field);
                if (capability == null || "POST".equals(method) && !capability.isCreate() || "PUT".equals(method) && !capability.isUpdate())
                    throw error(403, "OwnerPermissionDenied");
            }
        }
        return inContext(request, authorization.current().policy(), () -> {
            try { validator.generate(request); }
            catch (IOException invalid) { throw error(422, "CatalogLaunchInputInvalid"); }
            catch (io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid) {
                int status = invalid.getApiError() == null ? invalid.getStatus() : invalid.getApiError().getStatus();
                throw error(status == 422 ? 422 : 403, status == 422 ? "CatalogLaunchInputInvalid" : "OwnerPermissionDenied");
            }
            // Validation may decode the path ID; bind only the exact resulting request.
            request.setAttribute(PERMIT, new Permit(type, method, request.getId(), action));
            keyAccess.authorize(request, authorization.current().policy());
            return operation.apply(request);
        });
    }

    static <T> T inContext(ApiRequest request, Policy policy, Supplier<T> action) {
        ApiContext context = ApiContext.getContext();
        if (context == null) throw error(503, "CatalogLaunchUnavailable");
        ApiRequest previousRequest = context.getApiRequest(); Object previousPolicy = context.getPolicy();
        context.setApiRequest(request); context.setPolicy(policy);
        try { return action.get(); }
        finally { context.setApiRequest(previousRequest); context.setPolicy(previousPolicy); }
    }

    ResourceManager manager(String type) {
        ResourceManager manager = managers.getResourceManagerByType(type);
        if (manager == null) throw error(422, "CatalogLaunchUnsupported");
        return manager;
    }

    static long positive(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 1 || number.doubleValue() != number.longValue())
            throw error(403, "OwnerPermissionDenied");
        return number.longValue();
    }

    String project(Policy actor) { return String.valueOf(idFormatter.formatId("account", actor.getAccountId())); }
}
