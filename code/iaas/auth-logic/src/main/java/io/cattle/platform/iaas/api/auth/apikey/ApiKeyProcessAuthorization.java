package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.constants.NetworkConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Network;
import io.cattle.platform.core.model.ProjectMember;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.Image;
import io.cattle.platform.core.model.ImageStoragePoolMap;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorizationHook;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.projects.ProjectCreationScope;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        Long createdProjectId = ProjectCreationScope.currentProjectId();
        if (createdProjectId != null && "project".equals(request.getType()) && request.getId() == null
                && "POST".equals(request.getMethod()) && request.getAction() == null && request.getLink() == null) {
            metadata.put("projectCreation", true);
            metadata.put("targetId", createdProjectId.toString());
        }
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
            if (root == null || child == null || !schemaAllows(current.schemas().getSchema(type), metadata)) {
                denied("OwnerPermissionDenied");
            }
            if (authorizedProjectCreation(config, metadata, policy, current.policy(), root, child)) return;
            if (current.policy().authorizeObject(root) == null) denied("OwnerPermissionDenied");
            // Image CREATE runs before the caller returns. Its actual instance
            // relation is persisted by storage first; that narrow dependency is
            // authorized through live owner access, never a blanket internal bypass.
            ApiKeyTargetResolver.ImageDependency dependency = null;
            ApiKeyTargetResolver.LifecycleDependency ensure = null;
            ApiKeyTargetResolver.LifecycleDependency cleanup = null;
            boolean imageDependency = child instanceof Image && !(root instanceof Image)
                    && "image.create".equals(config.getProcessName()) && "image".equals(config.getResourceType());
            boolean imageEnsure = !(root instanceof Image) && (child instanceof Image
                    && "image".equals(config.getResourceType()) && "image.activate".equals(config.getProcessName())
                    || child instanceof ImageStoragePoolMap && "imageStoragePoolMap".equalsIgnoreCase(config.getResourceType())
                    && ("imagestoragepoolmap.create".equals(config.getProcessName()) || "imagestoragepoolmap.activate".equals(config.getProcessName())));
            boolean imageCleanup = !(root instanceof Image) && (child instanceof Image && "image".equals(config.getResourceType())
                    && ("image.deactivate".equals(config.getProcessName()) || "image.remove".equals(config.getProcessName()))
                    || child instanceof ImageStoragePoolMap && "imageStoragePoolMap".equalsIgnoreCase(config.getResourceType())
                    && ("imagestoragepoolmap.deactivate".equals(config.getProcessName()) || "imagestoragepoolmap.remove".equals(config.getProcessName())));
            if (current.policy().authorizeObject(child) == null) {
                if (imageDependency) dependency = targets.resolveImageDependency(root, (Image) child, current.policy());
                else if (imageEnsure) ensure = targets.resolveImageEnsureDependency(root, child, current.policy(),
                        EngineContext.getEngineContext().currentVerifiedExecution(), metadata);
                else if (imageCleanup) cleanup = policy == null || policy.getMode() == ApiKeyPolicy.Mode.FULL
                        ? targets.resolveOwnedImageCleanupDependency(root, child, current.policy(), config,
                                EngineContext.getEngineContext().currentVerifiedExecution(), EngineContext.getEngineContext().parentVerifiedExecution(), metadata)
                        : targets.resolveUpgradeCleanupDependency(root, child, current.policy(), config,
                                EngineContext.getEngineContext().currentVerifiedExecution(), EngineContext.getEngineContext().parentVerifiedExecution(), metadata);
                else denied("OwnerPermissionDenied");
                if (imageCleanup && cleanup == null) denied("OwnerPermissionDenied");
            }
            if (policy == null || policy.getMode() == ApiKeyPolicy.Mode.FULL) {
                if (policy != null && policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) denied("ApiKeyExpired");
                return;
            }
            // Queued work may have been captured by an older server whose
            // named selector masked the actual method. Never reuse that label
            // as authority after upgrade; legitimate full/legacy work above
            // keeps its original owner and lifecycle checks.
            if (policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) denied("ApiKeyExpired");
            var dispatched = ApiKeyOperations.of(text(metadata, "requestMethod"),
                    optionalText(metadata, "requestAction"), optionalText(metadata, "requestLink"), type);
            if (!dispatched.registered() || !dispatched.id().equals(operation)) denied("KeyScopeDenied");
            var target = targets.resolveObject(type, root, current.policy());
            var decision = evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, operation,
                    ApiKeyOperations.OPERATIONS.contains(operation), List.of(target)), clock.instant());
            if (!decision.allowed()) denied(decision.reason().getCode());
            if (imageDependency && dependency == null) {
                dependency = targets.resolveImageDependency(root, (Image) child, current.policy());
            }
            if (imageEnsure && ensure == null) ensure = targets.resolveImageEnsureDependency(root, child, current.policy(),
                    EngineContext.getEngineContext().currentVerifiedExecution(), metadata);
            if (cleanup == null) cleanup = targets.resolveUpgradeCleanupDependency(root, child, current.policy(), config,
                    EngineContext.getEngineContext().currentVerifiedExecution(), EngineContext.getEngineContext().parentVerifiedExecution(), metadata);
            var lifecycle = cleanup != null ? cleanup : ensure != null ? ensure : dependency == null
                    ? targets.resolveUpgradeRemovalDependency(root, child, current.policy(), config.getProcessName(), config.getResourceType(),
                            EngineContext.getEngineContext().currentVerifiedExecution(), metadata) : null;
            if (lifecycle == null && dependency == null) lifecycle = targets.resolveLifecycleDependency(root, child, current.policy());
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

    private boolean authorizedProjectCreation(LaunchConfiguration config, Map<String, Object> metadata,
            ApiKeyPolicy policy, Policy owner, Object root, Object child) {
        // Creating a namespace cannot require a membership that its creation
        // will establish. Keep the live principal/schema checks above, and only
        // authorize work bound by the project manager to this exact new row.
        if (!Boolean.TRUE.equals(metadata.get("projectCreation"))
                || !"project".equals(metadata.get("targetType")) || !"create".equals(metadata.get("operation"))
                || !"POST".equals(metadata.get("requestMethod")) || !Boolean.TRUE.equals(metadata.get("requestCollection"))
                || metadata.containsKey("requestAction") || metadata.containsKey("requestLink")
                || !(root instanceof Account project) || !"project".equals(project.getKind())
                || project.getRemoved() != null || (policy != null && policy.getMode() != ApiKeyPolicy.Mode.FULL)) return false;
        if (!io.cattle.platform.engine.process.ProcessAuthorization.executionBound(config)) denied("KeyScopeDenied");
        if (policy != null && policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) denied("ApiKeyExpired");
        // A captured creation marker is not a permanent membership bypass.
        // Only this live manager scope bootstraps membership; detached work
        // must recheck current ownership, including withdrawals.
        if (!Objects.equals(project.getId(), ProjectCreationScope.currentProjectId())
                && !authenticator.getAuthDao().isProjectOwner(project.getId(), owner.getAccountId(),
                        owner.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS), owner.getIdentities())) {
            denied("OwnerPermissionDenied");
        }
        if (child instanceof Account account && "account".equals(config.getResourceType())
                && "account.create".equals(config.getProcessName()) && Objects.equals(project.getId(), account.getId())
                && project.getId().toString().equals(config.getResourceId())
                && "project".equals(account.getKind()) && account.getRemoved() == null) return true;
        // AccountFieldPostInitHandler stamps a normal caller's personal account
        // on the member. Only an all-account caller preserves AuthDao's explicit
        // project accountId. Rebuild that exact expectation from live policy.
        Long memberAccountId = owner.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS)
                ? project.getId() : owner.getAccountId();
        if (child instanceof ProjectMember member && "projectMember".equals(config.getResourceType())
                && "projectmember.create".equals(config.getProcessName())
                && member.getId() != null && member.getId().toString().equals(config.getResourceId()) && member.getRemoved() == null
                && Objects.equals(project.getId(), member.getProjectId())
                && Objects.equals(memberAccountId, member.getAccountId())) return true;
        // The account-create listener schedules only these four default networks.
        // A marker alone is not authority: require its framework-captured parent
        // edge and reload the exact network under the actual new project.
        if (child instanceof Network network && "network".equals(config.getResourceType())
                && "network.create".equals(config.getProcessName())) {
            var edge = io.cattle.platform.engine.process.ProcessAuthorization.verifiedChild(config);
            if (edge != null && "account".equals(edge.parentType()) && project.getId().toString().equals(edge.parentId())
                    && metadata.equals(edge.rootAuthorization()) && network.getId() != null
                    && network.getId().toString().equals(config.getResourceId()) && network.getRemoved() == null
                    && Objects.equals(project.getId(), network.getAccountId())
                    && network.getNetworkDriverId() == null && network.getKind() != null
                    && io.cattle.platform.object.util.DataAccessor.field(network, NetworkConstants.FIELD_SUBNETS, Object.class) == null
                    && java.util.Set.of(NetworkConstants.KIND_DOCKER_HOST, NetworkConstants.KIND_DOCKER_NONE,
                            NetworkConstants.KIND_DOCKER_CONTAINER, NetworkConstants.KIND_DOCKER_BRIDGE).contains(network.getKind())) {
                return true;
            }
        }
        denied("OwnerPermissionDenied");
        return false;
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
    private static String optionalText(Map<String, Object> data, String field) {
        return data.containsKey(field) ? text(data, field) : null;
    }
    private static long number(Map<String, Object> data, String field) { return ApiKeyPolicyCodec.number(data.get(field)); }
    private static void denied(String code) { throw new ProcessAuthorizationDeniedException(code); }
}
