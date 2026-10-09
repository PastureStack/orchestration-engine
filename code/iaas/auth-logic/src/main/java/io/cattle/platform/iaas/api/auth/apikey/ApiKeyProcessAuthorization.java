package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.Image;
import io.cattle.platform.core.model.ImageStoragePoolMap;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorizationHook;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.inject.Inject;

/** No token or role snapshot enters process data. Every execution uses live RBAC. */
public class ApiKeyProcessAuthorization implements ProcessAuthorizationHook {
    @Inject ObjectManager objectManager;
    @Inject ApiAuthenticator authenticator;
    @Inject ApiKeyTargetResolver targets;
    Clock clock = Clock.systemUTC();
    private final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
    private final ApiKeyPolicyEvaluator evaluator = new ApiKeyPolicyEvaluator();

    @Override public boolean authorizesExecution() { return true; }

    @Override public Map<String, Object> capture(LaunchConfiguration config) {
        ApiContext context = ApiContext.getContext();
        ApiRequest request = context == null ? null : context.getApiRequest();
        ApiKeyCredentialContext key = request == null ? null : ApiKeyCredentialContext.get(request);
        if (key == null) return Map.of();
        Policy owner = (Policy) context.getPolicy();
        if (owner == null || !"ALLOW".equals(request.getAttribute("apiKey.audit.decision"))) {
            throw new ProcessAuthorizationDeniedException("KeyPolicyDenied");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("keyId", request.getAttribute("apiKey.audit.keyId"));
        metadata.put("policyRevision", key.revision());
        metadata.put("principalAccountId", key.principalAccountId());
        metadata.put("accountId", owner.getAccountId());
        metadata.put("requestId", request.getAttribute("apiKey.audit.requestId"));
        metadata.put("operation", request.getAttribute("apiKey.audit.operation"));
        metadata.put("requestMethod", request.getMethod());
        metadata.put("requestCollection", request.getId() == null);
        if (request.getAction() != null) metadata.put("requestAction", request.getAction());
        if (request.getLink() != null) metadata.put("requestLink", request.getLink());
        metadata.put("targetType", request.getType());
        metadata.put("targetId", request.getId() == null ? config.getResourceId() : request.getId());
        metadata.put("preview", false);
        return metadata;
    }

    @Override public void beforeExecution(LaunchConfiguration config, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return;
        try {
            long keyId = targets.parseScopeId("credential", text(metadata, "keyId"));
            Credential credential = objectManager.loadResource(Credential.class, keyId);
            if (credential == null || credential.getRemoved() != null || !"active".equals(credential.getState())
                    || !(CredentialConstants.KIND_API_KEY.equals(credential.getKind())
                    || CredentialConstants.KIND_API_KEY_RESTRICTED.equals(credential.getKind()))) denied("ApiKeyRevoked");
            if (credential.getAccountId() != number(metadata, "principalAccountId")) denied("OwnerPermissionDenied");
            ApiKeyPolicy policy = codec.read(credential);
            if (codec.revision(credential) != number(metadata, "policyRevision")) denied("ApiKeyPolicyChanged");
            String operation = text(metadata, "operation");
            String type = text(metadata, "targetType");
            ApiRequest request = new ApiRequest(null, objectManager.getSchemaFactory());
            request.setSchemaVersion("v2-beta");
            request.setType(type);
            var current = authenticator.currentAuthorization(credential.getAccountId(), number(metadata, "accountId"), request);
            Object root = objectManager.loadResource(type, parseNumeric(type, text(metadata, "targetId")));
            Object child = objectManager.loadResource(config.getResourceType(), config.getResourceId());
            if (root == null || child == null || current.policy().authorizeObject(root) == null
                    || !schemaAllows(current.schemas().getSchema(type), metadata)) {
                denied("OwnerPermissionDenied");
            }
            // Image CREATE runs before the caller returns. Its actual instance
            // relation is persisted by storage first; that narrow dependency is
            // authorized through live owner access, never a blanket internal bypass.
            ApiKeyTargetResolver.ImageDependency dependency = null;
            ApiKeyTargetResolver.LifecycleDependency ensure = null;
            boolean imageDependency = child instanceof Image && !(root instanceof Image)
                    && "image.create".equals(config.getProcessName()) && "image".equals(config.getResourceType());
            boolean imageEnsure = !(root instanceof Image) && (child instanceof Image
                    && "image".equals(config.getResourceType()) && "image.activate".equals(config.getProcessName())
                    || child instanceof ImageStoragePoolMap && "imageStoragePoolMap".equalsIgnoreCase(config.getResourceType())
                    && ("imagestoragepoolmap.create".equals(config.getProcessName()) || "imagestoragepoolmap.activate".equals(config.getProcessName())));
            if (current.policy().authorizeObject(child) == null) {
                if (imageDependency) dependency = targets.resolveImageDependency(root, (Image) child, current.policy());
                else if (imageEnsure) ensure = targets.resolveImageEnsureDependency(root, child, current.policy(),
                        EngineContext.getEngineContext().currentVerifiedExecution(), metadata);
                else denied("OwnerPermissionDenied");
            }
            if (policy == null || policy.getMode() == ApiKeyPolicy.Mode.FULL) {
                if (policy != null && policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) denied("ApiKeyExpired");
                return;
            }
            var target = targets.resolveObject(type, root, current.policy());
            var decision = evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, operation,
                    ApiKeyOperations.OPERATIONS.contains(operation), List.of(target)), clock.instant());
            if (!decision.allowed()) denied(decision.reason().getCode());
            if (imageDependency && dependency == null) {
                dependency = targets.resolveImageDependency(root, (Image) child, current.policy());
            }
            if (imageEnsure && ensure == null) ensure = targets.resolveImageEnsureDependency(root, child, current.policy(),
                    EngineContext.getEngineContext().currentVerifiedExecution(), metadata);
            var lifecycle = ensure != null ? ensure : dependency == null ? targets.resolveLifecycleDependency(root, child, current.policy()) : null;
            var descendant = lifecycle != null ? lifecycle.scope() : dependency == null
                    ? targets.resolveObject(ApiKeyQueryScopes.canonical(config.getResourceType()), child, current.policy()) : dependency.scope();
            if (target.projectId() != null && !target.projectId().equals(descendant.projectId())) denied("KeyScopeDenied");
            if (target.stackId() != null && !target.stackId().equals(descendant.stackId())) denied("KeyScopeDenied");
            var dependencyDecision = evaluator.evaluateAuthorizedDependency(policy, new ApiKeyPolicyEvaluator.Request(true, operation,
                    ApiKeyOperations.OPERATIONS.contains(operation), lifecycle != null ? lifecycle.resources()
                            : dependency == null ? List.of(descendant) : dependency.instances()), clock.instant());
            if (!dependencyDecision.allowed()) denied(dependencyDecision.reason().getCode());
        } catch (ProcessAuthorizationDeniedException denied) { throw denied; }
        catch (ClientVisibleException denied) {
            if (denied.getStatus() == 401 || denied.getStatus() == 403 || denied.getStatus() == 404) {
                denied(denied.getCode());
            }
            throw denied;
        }
        catch (IllegalArgumentException invalid) { denied("KeyScopeDenied"); }
        // Transient DB/provider failures remain retryable engine failures. They
        // must not be mislabeled as a permanent permission denial.
    }

    private Long parseNumeric(String type, String raw) {
        return raw.matches("[0-9]+") ? Long.valueOf(raw) : targets.parseScopeId(type, raw);
    }

    private boolean schemaAllows(Schema schema, Map<String, Object> metadata) {
        if (schema == null) return false;
        String op = text(metadata, "operation");
        boolean collection = Boolean.TRUE.equals(metadata.get("requestCollection"));
        if (metadata.get("requestAction") instanceof String action) {
            Map<String, ?> actions = collection ? schema.getCollectionActions() : schema.getResourceActions();
            return actions != null && actions.containsKey(action);
        }
        if (op.equals("create")) return schema.getCollectionMethods() != null && schema.getCollectionMethods().contains("POST");
        String method = text(metadata, "requestMethod");
        List<String> methods = collection ? schema.getCollectionMethods() : schema.getResourceMethods();
        return methods != null && methods.contains(method);
    }
    private static String text(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Invalid job authorization metadata");
        return s;
    }
    private static long number(Map<String, Object> data, String field) { return ApiKeyPolicyCodec.number(data.get(field)); }
    private static void denied(String code) { throw new ProcessAuthorizationDeniedException(code); }
}
