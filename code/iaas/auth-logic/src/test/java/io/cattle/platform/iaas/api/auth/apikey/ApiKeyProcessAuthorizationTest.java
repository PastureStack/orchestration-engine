package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Image;
import io.cattle.platform.core.model.ImageStoragePoolMap;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.StoragePool;
import io.cattle.platform.core.model.VolumeStoragePoolMap;
import io.cattle.platform.core.model.tables.records.ImageRecord;
import io.cattle.platform.core.model.tables.records.ImageStoragePoolMapRecord;
import io.cattle.platform.core.model.tables.records.StoragePoolRecord;
import io.cattle.platform.core.model.tables.records.VolumeStoragePoolMapRecord;
import io.cattle.platform.core.model.tables.records.InstanceHostMapRecord;
import io.cattle.platform.core.model.tables.records.VolumeRecord;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import static io.cattle.platform.core.model.tables.MountTable.MOUNT;
import static io.cattle.platform.core.model.tables.VolumeStoragePoolMapTable.VOLUME_STORAGE_POOL_MAP;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyProcessAuthorizationTest {
    ApiKeyProcessAuthorization hook;
    Credential key;
    Schema schema;
    Policy owner;
    io.cattle.platform.iaas.api.auth.dao.AuthDao projectAuthorityDao;
    Map<String, Object> metadata;
    LaunchConfiguration job = new LaunchConfiguration("instance.update", "instance", "3", 5L, 0, Map.of());
    Instant now = Instant.parse("2026-10-08T00:00:00Z");
    Object root = new Object(), child = new Object();
    @Before public void setup() {
        hook = new ApiKeyProcessAuthorization(); hook.objectManager = mock(ObjectManager.class);
        hook.authenticator = mock(ApiAuthenticator.class); hook.targets = mock(ApiKeyTargetResolver.class);
        hook.clock = Clock.fixed(now, ZoneOffset.UTC);
        key = mock(Credential.class); when(key.getId()).thenReturn(1L); when(key.getAccountId()).thenReturn(10L);
        when(key.getKind()).thenReturn("apiKey"); when(key.getState()).thenReturn("active");
        when(hook.targets.parseScopeId("credential", "1c1")).thenReturn(1L);
        when(hook.objectManager.loadResource(Credential.class, 1L)).thenReturn(key);
        when(hook.objectManager.loadResource("container", 3L)).thenReturn(root);
        when(hook.objectManager.loadResource("instance", "3")).thenReturn(child);
        owner = mock(Policy.class); when(owner.authorizeObject(any())).thenAnswer(i -> i.getArgument(0));
        SchemaFactory factory = mock(SchemaFactory.class); schema = mock(Schema.class);
        when(factory.getSchema("container")).thenReturn(schema); when(schema.getResourceMethods()).thenReturn(List.of("PUT"));
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, factory));
        metadata = new java.util.LinkedHashMap<>(Map.of("keyId", "1c1", "policyRevision", 0L,
                "principalAccountId", 10L, "accountId", 5L, "operation", "update", "targetType", "container",
                "targetId", "3", "requestMethod", "PUT", "requestCollection", false));
    }
    private void policy(ApiKeyPolicy policy) {
        Map<String, Object> stored = new ApiKeyPolicyCodec().store(key, policy, 1);
        when(key.getData()).thenReturn(stored);
        metadata.put("policyRevision", 1L);
    }
    private void deny(String code) {
        assertEquals(code, assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(job, metadata)).getCode());
    }
    private void sameScopePolicy(String operation) {
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("scope", ApiKeyPolicy.Effect.ALLOW,
                        ApiKeyPolicy.Scope.stack("1st8"), Set.of(operation)))));
        when(hook.targets.resolveObject("container", root, owner)).thenReturn(
                ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"));
        when(hook.targets.resolveObject("container", child, owner)).thenReturn(
                ApiKeyPolicyEvaluator.Target.stackResource("container", "1i4", "1a5", "1st8"));
    }
    @Test public void queuedOldExportClassificationCannotAuthorizeMutation() {
        sameScopePolicy("export");
        metadata.put("operation", "export"); metadata.put("requestLink", "composeConfig");
        deny("KeyScopeDenied");
        metadata.put("requestMethod", "DELETE");
        when(schema.getResourceMethods()).thenReturn(List.of("DELETE"));
        deny("KeyScopeDenied");
    }
    @Test public void queuedOldActionClassificationCannotAuthorizePut() {
        sameScopePolicy("upgrade");
        metadata.put("operation", "upgrade"); metadata.put("requestAction", "upgrade");
        when(schema.getResourceActions()).thenReturn(Map.of("upgrade", mock(Action.class)));
        deny("KeyScopeDenied");
    }
    @Test public void matchingQueuedDispatchKeepsActualUpdateAndUpgradeGrants() {
        sameScopePolicy("update"); metadata.put("requestLink", "composeConfig");
        hook.beforeExecution(job, metadata);
        sameScopePolicy("upgrade");
        metadata.put("operation", "upgrade"); metadata.put("requestMethod", "POST");
        metadata.put("requestAction", "upgrade");
        when(schema.getResourceActions()).thenReturn(Map.of("upgrade", mock(Action.class)));
        hook.beforeExecution(job, metadata);
    }
    @Test public void queuedStackServiceActionsStillRecheckChildDenialsAndOwnerAccess() {
        var stack = new io.cattle.platform.core.model.tables.records.StackRecord(); stack.setId(8L); stack.setAccountId(5L);
        var service = new io.cattle.platform.core.model.tables.records.ServiceRecord();
        service.setId(11L); service.setAccountId(5L); service.setStackId(8L);
        when(hook.targets.parseScopeId("stack", "8")).thenReturn(8L);
        when(hook.objectManager.loadResource("stack", 8L)).thenReturn(stack);
        when(hook.objectManager.loadResource("service", "11")).thenReturn(service);
        SchemaFactory factory = mock(SchemaFactory.class); when(factory.getSchema("stack")).thenReturn(schema);
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, factory));
        when(schema.getResourceActions()).thenReturn(Map.of("activateservices", new Action(), "deactivateservices", new Action()));
        when(hook.targets.resolveObject("stack", stack, owner)).thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("stack", "1st8", "1a5", "1st8"));
        when(hook.targets.resolveObject("service", service, owner)).thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("service", "1s11", "1a5", "1st8"));
        var allow = new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("stack", "1st8"), Set.of("update"));
        var denyChild = new ApiKeyPolicy.Rule("child", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("service", "1s11"), Set.of("update"));
        metadata.put("targetType", "stack"); metadata.put("targetId", "8"); metadata.put("requestMethod", "POST");
        for (String action : List.of("activateservices", "deactivateservices")) {
            metadata.put("requestAction", action);
            job = new LaunchConfiguration("service." + ("activateservices".equals(action) ? "activate" : "deactivate"), "service", "11", 5L, 0, Map.of());
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(allow, denyChild)));
            deny("KeyPolicyDenied");
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(allow)));
            hook.beforeExecution(job, metadata);
        }
        when(owner.authorizeObject(service)).thenReturn(null); deny("OwnerPermissionDenied");
    }
    @Test public void fullLegacyQueuedMetadataRetainsOriginalOwnerBoundary() {
        metadata.put("operation", "export"); metadata.put("requestLink", "composeConfig");
        hook.beforeExecution(job, metadata);
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        hook.beforeExecution(job, metadata);
        verify(hook.targets, never()).resolveObject(anyString(), any(), any());
    }
    @Test public void typedQueuedActionsRecomputeTheSamePersistedRootOperation() {
        sameScopePolicy("update");
        metadata.put("requestMethod", "POST");
        for (String action : List.of("allocate", "deallocate", "updatehealthy")) {
            metadata.put("requestAction", action);
            when(schema.getResourceActions()).thenReturn(Map.of(action, mock(Action.class)));
            hook.beforeExecution(job, metadata);
        }
        sameScopePolicy("upgrade"); metadata.put("operation", "upgrade");
        deny("KeyScopeDenied");
    }
    @Test public void unknownTypedQueuedActionDoesNotRestrictFullOrLegacy() {
        metadata.put("requestMethod", "POST"); metadata.put("requestAction", "futurePluginAction");
        metadata.put("operation", "action:futurepluginaction");
        when(schema.getResourceActions()).thenReturn(Map.of("futurePluginAction", mock(Action.class)));
        hook.beforeExecution(job, metadata);
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        hook.beforeExecution(job, metadata);
        verify(hook.targets, never()).resolveObject(anyString(), any(), any());
    }
    @Test public void malformedQueuedSelectorAndExpiredPolicyRemainFailClosed() {
        sameScopePolicy("update"); metadata.put("requestLink", 7);
        deny("KeyScopeDenied");
        metadata.remove("requestLink"); metadata.remove("requestMethod");
        deny("KeyScopeDenied");
        metadata.put("requestMethod", "PUT"); metadata.put("operation", "export");
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, now, List.of()));
        deny("ApiKeyExpired");
    }
    @Test public void fullAndLegacyKeepExistingActionBoundary() {
        hook.beforeExecution(job, metadata);
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        hook.beforeExecution(job, metadata);
        verify(hook.targets, times(2)).parseScopeId("credential", "1c1");
        verifyNoMoreInteractions(hook.targets);
    }
    @Test public void revocationStopsQueuedWorkBeforeOwnerOrTargetLookup() {
        when(key.getRemoved()).thenReturn(new java.util.Date()); deny("ApiKeyRevoked");
        verifyNoInteractions(hook.authenticator);
    }
    @Test public void policyRevisionChangeDoesNotRunQueuedOldGrant() {
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        metadata.put("policyRevision", 0L); deny("ApiKeyPolicyChanged"); verifyNoInteractions(hook.authenticator);
    }
    @Test public void fullExplicitExpiryIsCheckedAtExecution() {
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, now, List.of()));
        deny("ApiKeyExpired");
    }
    @Test public void currentOwnerSchemaStillConstrainsFullKey() {
        when(schema.getResourceMethods()).thenReturn(List.of("GET")); deny("OwnerPermissionDenied");
    }
    @Test public void currentOwnerCannotAuthorizeDifferentPrincipal() {
        metadata.put("principalAccountId", 11L); deny("OwnerPermissionDenied"); verifyNoInteractions(hook.authenticator);
    }
    @Test public void descendantCannotEscapeAuthorizedStack() {
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("scope", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")))));
        when(hook.targets.resolveObject("container", root, owner)).thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"));
        when(hook.targets.resolveObject("container", child, owner)).thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i4", "1a5", "1st9"));
        deny("KeyScopeDenied");
    }
    @Test public void collectionActionUsesCollectionNotResourceActions() {
        metadata.put("requestAction", "restart"); metadata.put("requestCollection", true);
        when(schema.getCollectionActions()).thenReturn(Map.of("restart", mock(Action.class)));
        when(schema.getResourceActions()).thenReturn(Map.of());
        hook.beforeExecution(job, metadata);
        metadata.put("requestCollection", false); deny("OwnerPermissionDenied");
    }
    @Test public void transientAuthorizationLookupFailureIsNotPermanentDenial() {
        IllegalStateException unavailable = new IllegalStateException("provider unavailable");
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> hook.beforeExecution(job, metadata)));
        verify(hook.objectManager, never()).loadResource("instance", "3");
    }

    @Test public void actualAccountPolicyAuthorizesQueuedWorkWithoutHttpContext() {
        actualAccountPolicy(5L);
        ApiContext.remove();
        hook.beforeExecution(job, metadata);
        assertNull(ApiContext.getContext());
    }

    @Test public void actualAccountPolicyStillDeniesForeignQueuedResource() {
        actualAccountPolicy(6L);
        ApiContext.remove();
        deny("OwnerPermissionDenied");
        assertNull(ApiContext.getContext());
    }

    private void actualAccountPolicy(long resourceAccountId) {
        Account project = mock(Account.class), principal = mock(Account.class);
        when(project.getId()).thenReturn(5L);
        when(principal.getId()).thenReturn(10L);
        AccountPolicy real = new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions());
        SchemaFactory factory = mock(SchemaFactory.class);
        when(factory.getSchema("container")).thenReturn(schema);
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any()))
                .thenReturn(new ApiAuthenticator.CurrentAuthorization(real, factory));
        Instance instance = mock(Instance.class);
        when(instance.getAccountId()).thenReturn(resourceAccountId);
        when(hook.objectManager.loadResource("container", 3L)).thenReturn(instance);
        when(hook.objectManager.loadResource("instance", "3")).thenReturn(instance);
    }

    @Test public void imageCreateDependencyWorksForFullAndScopedWithoutPublicImageGrant() {
        Image image = mock(Image.class);
        when(hook.objectManager.loadResource("image", "7")).thenReturn(image);
        when(owner.authorizeObject(image)).thenReturn(null);
        when(hook.targets.resolveImageDependency(root, image, owner))
                .thenReturn(new ApiKeyTargetResolver.ImageDependency(
                        ApiKeyPolicyEvaluator.Target.stackResource("image", "1img7", "1a5", "1st8"),
                        List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"))));
        LaunchConfiguration imageJob = new LaunchConfiguration("image.create", "image", "7", null, 0, Map.of());
        hook.beforeExecution(imageJob, metadata);
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("scope", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")))));
        when(hook.targets.resolveObject("container", root, owner))
                .thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"));
        hook.beforeExecution(imageJob, metadata);
        verify(hook.targets, times(2)).resolveImageDependency(root, image, owner);
        verify(owner, times(2)).authorizeObject(image);
    }
    private ApiKeyTargetResolver.LifecycleDependency scopedEnsure(Object child, String type) {
        when(hook.objectManager.loadResource(type, "7")).thenReturn(child);
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("container", "1i3"), Set.of("update")))));
        when(hook.targets.resolveObject("container", root, owner))
                .thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"));
        var dependency = new ApiKeyTargetResolver.LifecycleDependency(
                ApiKeyPolicyEvaluator.Target.stackResource("volume", "1v6", "1a5", "1st8"), List.of(
                        ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"),
                        ApiKeyPolicyEvaluator.Target.stackResource("volume", "1v6", "1a5", "1st8"),
                        ApiKeyPolicyEvaluator.Target.platformResource("image", "1img7"),
                        ApiKeyPolicyEvaluator.Target.platformResource("imageStoragePoolMap", "1im7"),
                        ApiKeyPolicyEvaluator.Target.projectResource("storagePool", "1sp9", "1a5")));
        when(hook.targets.resolveImageEnsureDependency(eq(root), eq(child), eq(owner), any(), eq(metadata))).thenReturn(dependency);
        return dependency;
    }

    @Test public void onlyClosedEnsureProcessesUseActualCurrentFrameAndRootGrant() {
        Image image = mock(Image.class); ImageStoragePoolMap cache = mock(ImageStoragePoolMap.class);
        for (String process : List.of("image.activate", "imagestoragepoolmap.create", "imagestoragepoolmap.activate")) {
            Object child = process.startsWith("image.") ? image : cache;
            String type = process.startsWith("image.") ? "image" : "imagestoragepoolmap";
            scopedEnsure(child, type);
            if (child == image) when(owner.authorizeObject(image)).thenReturn(null);
            else when(owner.authorizeObject(cache)).thenReturn(null);
            EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("volume", "6", metadata);
            try {
                hook.beforeExecution(new LaunchConfiguration(process, type, "7", null, 0, Map.of("volumeId", 99L)), metadata);
                verify(hook.targets).resolveImageEnsureDependency(root, child, owner, frame, metadata);
            } finally { engine.popVerifiedExecution(frame); }
        }
        verify(hook.targets, never()).resolveImageDependency(any(), any(), any());
    }

    @Test public void scopedEnsureCannotHideExplicitActualDependencyDenials() {
        Image image = mock(Image.class);
        var dependency = scopedEnsure(image, "image");
        when(owner.authorizeObject(image)).thenReturn(null);
        for (var target : dependency.resources()) {
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("container", "1i3"), Set.of("update")),
                    new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource(target.resourceType(), target.resourceId()), Set.of("update")))));
            EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("volume", "6", metadata);
            try { assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(new LaunchConfiguration("image.activate", "image", "7", null, 0, Map.of()), metadata)).getCode()); }
            finally { engine.popVerifiedExecution(frame); }
        }
    }

    @Test public void requestDataCannotMintEnsureFrameAndRootGrantIsStillRequired() {
        Image image = mock(Image.class); scopedEnsure(image, "image");
        when(owner.authorizeObject(image)).thenReturn(null);
        when(hook.targets.resolveImageEnsureDependency(eq(root), eq(image), eq(owner), isNull(), eq(metadata)))
                .thenThrow(new ClientVisibleException(403, "KeyScopeDenied"));
        LaunchConfiguration config = new LaunchConfiguration("image.activate", "image", "7", null, 0,
                Map.of("verifiedExecution", Map.of("resourceType", "volume", "resourceId", "6"), "_apiKeyAudit", metadata));
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config, metadata)).getCode());
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of()));
        EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("volume", "6", metadata);
        try { assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(config, metadata)).getCode()); }
        finally { engine.popVerifiedExecution(frame); }
    }

    @Test public void revokedEnsureKeyNeverUsesParentAuthority() {
        Image image = mock(Image.class); scopedEnsure(image, "image");
        when(key.getRemoved()).thenReturn(new java.util.Date());
        assertEquals("ApiKeyRevoked", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(new LaunchConfiguration("image.activate", "image", "7", null, 0, Map.of()), metadata)).getCode());
        verify(hook.targets, never()).resolveImageEnsureDependency(any(), any(), any(), any(), any());
    }

    @Test public void destructiveImageAndCacheProcessesCannotUseEnsureAuthority() {
        Image image = mock(Image.class); ImageStoragePoolMap cache = mock(ImageStoragePoolMap.class);
        when(owner.authorizeObject(image)).thenReturn(null); when(owner.authorizeObject(cache)).thenReturn(null);
        when(hook.objectManager.loadResource("image", "7")).thenReturn(image);
        when(hook.objectManager.loadResource("imagestoragepoolmap", "7")).thenReturn(cache);
        for (String type : List.of("image", "imagestoragepoolmap")) {
            for (String action : List.of("remove", "update", "purge")) {
                assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                        () -> hook.beforeExecution(new LaunchConfiguration(type + "." + action, type, "7", null, 0, Map.of()), metadata)).getCode());
            }
        }
        verify(hook.targets, never()).resolveImageEnsureDependency(any(), any(), any(), any(), any());
    }

    @Test public void fullAndLegacyAlreadyOwnedEnsurePathsHaveNoNewFrameRequirement() {
        Image image = mock(Image.class); ImageStoragePoolMap cache = mock(ImageStoragePoolMap.class);
        when(hook.objectManager.loadResource("image", "7")).thenReturn(image);
        when(hook.objectManager.loadResource("imagestoragepoolmap", "7")).thenReturn(cache);
        for (boolean full : List.of(false, true)) {
            if (full) policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
            for (String process : List.of("image.activate", "imagestoragepoolmap.create", "imagestoragepoolmap.activate")) {
                String type = process.startsWith("image.") ? "image" : "imagestoragepoolmap";
                hook.beforeExecution(new LaunchConfiguration(process, type, "7", null, 0, Map.of()), metadata);
            }
        }
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
        verify(hook.targets, never()).resolveImageEnsureDependency(any(), any(), any(), any(), any());
    }

    @Test public void customDirectPublicImageStillCannotBorrowVolumeEnsureGrant() {
        Image image = mock(Image.class);
        when(hook.objectManager.loadResource("image", 7L)).thenReturn(image);
        when(hook.objectManager.loadResource("image", "7")).thenReturn(image);
        metadata.put("targetType", "image"); metadata.put("targetId", "7");
        SchemaFactory factory = mock(SchemaFactory.class); when(factory.getSchema("image")).thenReturn(schema);
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any()))
                .thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, factory));
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        when(hook.targets.resolveObject("image", image, owner)).thenReturn(ApiKeyPolicyEvaluator.Target.unresolved("image", "1img7"));
        EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("volume", "6", metadata);
        try { assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(new LaunchConfiguration("image.activate", "image", "7", null, 0, Map.of()), metadata)).getCode()); }
        finally { engine.popVerifiedExecution(frame); }
        verify(hook.targets, never()).resolveImageEnsureDependency(any(), any(), any(), any(), any());
    }
    @Test public void fullAndLegacyDirectImageCreateKeepExistingOwnerGrantWithoutDependencyRequirement() {
        Image image = mock(Image.class);
        when(hook.objectManager.loadResource("image", 7L)).thenReturn(image);
        when(hook.objectManager.loadResource("image", "7")).thenReturn(image);
        metadata.put("targetType", "image"); metadata.put("targetId", "7");
        metadata.put("operation", "create"); metadata.put("requestCollection", true); metadata.put("requestMethod", "POST");
        SchemaFactory factory = mock(SchemaFactory.class);
        when(factory.getSchema("image")).thenReturn(schema);
        when(schema.getCollectionMethods()).thenReturn(List.of("POST"));
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any()))
                .thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, factory));
        LaunchConfiguration imageJob = new LaunchConfiguration("image.create", "image", "7", null, 0, Map.of());
        hook.beforeExecution(imageJob, metadata);
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        hook.beforeExecution(imageJob, metadata);
        verify(hook.targets, never()).resolveImageDependency(any(), any(), any());
        verify(hook.targets, never()).resolveObject(anyString(), any(), any());
    }
    @Test public void rootGrantCannotOverrideActualContainerDependencyDenial() {
        Image image = mock(Image.class);
        when(hook.objectManager.loadResource("image", "7")).thenReturn(image);
        when(owner.authorizeObject(image)).thenReturn(null);
        when(hook.targets.resolveImageDependency(root, image, owner))
                .thenReturn(new ApiKeyTargetResolver.ImageDependency(
                        ApiKeyPolicyEvaluator.Target.stackResource("image", "1img7", "1a5", "1st8"),
                        List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i4", "1a5", "1st8"))));
        when(hook.targets.resolveObject("container", root, owner))
                .thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"));
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                new ApiKeyPolicy.Rule("parent", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("container", "1i3"), Set.of("update")),
                new ApiKeyPolicy.Rule("child-deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("container", "1i4"), Set.of("update")))));
        LaunchConfiguration imageJob = new LaunchConfiguration("image.create", "image", "7", null, 0, Map.of());
        assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(imageJob, metadata)).getCode());
    }
    @Test public void authorizedRootDependencyDoesNotRequireAnUnrelatedAdditionalChildGrant() {
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("parent", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("container", "1i3"), Set.of("update")))));
        when(hook.targets.resolveObject("container", root, owner))
                .thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"));
        when(hook.targets.resolveObject("container", child, owner))
                .thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i4", "1a5", "1st8"));
        hook.beforeExecution(job, metadata);
    }
    @Test public void missingImageDependencyIsDeniedAndOtherImageProcessesKeepOwnerBoundary() {
        Image image = mock(Image.class);
        when(hook.objectManager.loadResource("image", "7")).thenReturn(image);
        when(owner.authorizeObject(image)).thenReturn(null);
        when(hook.targets.resolveImageDependency(root, image, owner))
                .thenThrow(new ClientVisibleException(403, "KeyScopeDenied"));
        LaunchConfiguration imageJob = new LaunchConfiguration("image.create", "image", "7", null, 0, Map.of());
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(imageJob, metadata)).getCode());
        imageJob.setProcessName("image.remove");
        assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(imageJob, metadata)).getCode());
        verify(hook.targets, times(1)).resolveImageDependency(root, image, owner);
    }
    @Test public void actualResolverAndLiveAccountPolicyAuthorizeUpgradeStopAndRemoveAfterMapUnmanaged() {
        upgradeLifecycle();
        ApiContext.remove();
        for (String action : List.of("upgrade", "finishupgrade")) {
            metadata.put("requestAction", action);
            for (String process : List.of("instance.stop", "instance.remove")) {
                hook.beforeExecution(new LaunchConfiguration(process, "instance", "3", 5L, 0, Map.of()), metadata);
            }
        }
        assertNull(ApiContext.getContext());
    }
    @Test public void backgroundProjectAuthorityIsFreshAndCreatesNoRequest() {
        removalLifecycle(); ApiContext.remove();
        when(projectAuthorityDao.hasAccessToProject(eq(5L), eq(5L), eq(false), any())).thenReturn(false);
        assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(new LaunchConfiguration("instance.remove", "instance", "3", 5L, 0, Map.of()), metadata)).getCode());
        assertNull(ApiContext.getContext());
    }
    @Test public void upgradeHostMapUsesActualPersistedInstanceAndPreservesChildDenial() {
        upgradeLifecycle();
        DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(mock(SchemaFactory.class));
        formatter.setTypeMappings(Map.of("credential", "c", "project", "a", "stack", "st", "service", "s", "container", "i"));
        hook.targets.idFormatter = formatter;
        Instance instance = hook.objectManager.loadResource("instance", "3");
        when(hook.objectManager.loadResource(Instance.class, 3L)).thenReturn(instance);
        InstanceHostMapRecord map = new InstanceHostMapRecord();
        map.setId(4L); map.setInstanceId(3L); map.setHostId(9L);
        when(hook.objectManager.loadResource("instancehostmap", "4")).thenReturn(map);
        when(hook.objectManager.loadResource(InstanceHostMap.class, 4L)).thenReturn(map);
        LaunchConfiguration deactivate = new LaunchConfiguration("instancehostmap.deactivate", "instancehostmap", "4", null, 0,
                Map.of("instanceId", 99L, "stackId", 99L));
        ApiContext.remove();
        hook.beforeExecution(deactivate, metadata);
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")),
                new ApiKeyPolicy.Rule("child-deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("container", "1i3"), Set.of("upgrade")))));
        assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(deactivate, metadata)).getCode());
        assertNull(ApiContext.getContext());
    }
    @Test public void upgradeLifecycleStillRejectsForeignOrUnmarkedMapAndExplicitChildDeny() {
        ServiceExposeMap map = upgradeLifecycle();
        LaunchConfiguration stop = new LaunchConfiguration("instance.stop", "instance", "3", 5L, 0, Map.of());
        when(map.getAccountId()).thenReturn(6L);
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(stop, metadata)).getCode());
        when(map.getAccountId()).thenReturn(5L); when(map.getUpgrade()).thenReturn(false);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true)).thenReturn(List.of());
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(stop, metadata)).getCode());
        when(map.getUpgrade()).thenReturn(true);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true)).thenReturn(List.of(map));
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")),
                new ApiKeyPolicy.Rule("child-deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("container", "1i3"), Set.of("upgrade")))));
        assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(stop, metadata)).getCode());
    }
    private Volume removalLifecycle() {
        ServiceExposeMap map = upgradeLifecycle();
        when(map.getId()).thenReturn(1L); when(map.getState()).thenReturn("removed"); when(map.getRemoved()).thenReturn(new java.util.Date(1));
        Instance instance = hook.objectManager.loadResource("instance", "3");
        when(instance.getState()).thenReturn("removing"); when(instance.getRemoved()).thenReturn(null);
        when(hook.objectManager.loadResource(Instance.class, 3L)).thenReturn(instance);
        when(hook.objectManager.loadResource(ServiceExposeMap.class, 1L)).thenReturn(map);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true)).thenReturn(List.of());
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.SERVICE_ID, 2L, SERVICE_EXPOSE_MAP.MANAGED, false,
                SERVICE_EXPOSE_MAP.UPGRADE, true, SERVICE_EXPOSE_MAP.STATE, "removed")).thenReturn(List.of(map));
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of());
        VolumeRecord volume = new VolumeRecord(); volume.setId(6L); volume.setAccountId(5L); volume.setInstanceId(3L);
        volume.setDeviceNumber(0); volume.setImageId(7L); volume.setState("detached");
        when(hook.objectManager.loadResource(Volume.class, 6L)).thenReturn(volume);
        when(hook.objectManager.loadResource("volume", "6")).thenReturn(volume);
        when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of());
        when(hook.targets.idFormatter.formatId(anyString(), any())).thenAnswer(i -> "1" +
                Map.of("credential", "c", "project", "a", "stack", "st", "service", "s", "container", "i",
                        "volume", "v", "serviceExposeMap", "sem").getOrDefault(i.<String>getArgument(0), "internal") + i.getArgument(1));
        metadata.put("requestAction", "finishupgrade"); metadata.put("preview", false);
        return volume;
    }
    @Test public void stillLiveUpgradeMapKeepsOriginalRemovalResolverWithoutNewFrameRequirement() {
        upgradeLifecycle(); metadata.put("requestAction", "finishupgrade");
        Instance instance = hook.objectManager.loadResource("instance", "3"); when(instance.getState()).thenReturn("removing");
        when(hook.objectManager.loadResource(Instance.class, 3L)).thenReturn(instance);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.SERVICE_ID, 2L, SERVICE_EXPOSE_MAP.MANAGED, false,
                SERVICE_EXPOSE_MAP.UPGRADE, true, SERVICE_EXPOSE_MAP.STATE, "removed")).thenReturn(List.of());
        VolumeRecord volume = new VolumeRecord(); volume.setId(6L); volume.setAccountId(5L); volume.setInstanceId(3L); volume.setDeviceNumber(0);
        when(hook.objectManager.loadResource(Volume.class, 6L)).thenReturn(volume);
        when(hook.objectManager.loadResource("volume", "6")).thenReturn(volume);
        when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of());
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
        hook.beforeExecution(new LaunchConfiguration("instance.remove", "instance", "3", 5L, 0, Map.of()), metadata);
        hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0, Map.of()), metadata);
    }
    @Test public void queuedFinishUpgradeRemovalAndVerifiedOsVolumeKeepExactRootGrantAfterPreListener() {
        removalLifecycle(); ApiContext.remove();
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
        hook.beforeExecution(new LaunchConfiguration("instance.remove", "instance", "3", 5L, 0, Map.of()), metadata);
        EngineContext engine = EngineContext.getEngineContext(); var instanceFrame = engine.pushVerifiedExecution("instance", "3", metadata);
        try {
            hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0, Map.of()), metadata);
            hook.beforeExecution(new LaunchConfiguration("instance.deallocate", "instance", "3", 5L, 0, Map.of()), metadata);
        } finally { engine.popVerifiedExecution(instanceFrame); }
        var volumeFrame = engine.pushVerifiedExecution("volume", "6", metadata);
        try { hook.beforeExecution(new LaunchConfiguration("volume.deallocate", "volume", "6", 5L, 0, Map.of()), metadata); }
        finally { engine.popVerifiedExecution(volumeFrame); }
        assertNull(ApiContext.getContext());
    }
    @Test public void persistedChainedUpgradeRetainsExactRemovalGrantAndRejectsForgedOrForeignBinding() {
        removalLifecycle(); metadata.put("requestAction", "upgrade"); ApiContext.remove();
        ProcessRecord record = new ProcessRecord(new LaunchConfiguration("instance.remove", "instance", "3", 5L, 0,
                Map.of(ProcessAuthorization.DATA_KEY, metadata)), 901L, "test");
        hook.beforeExecution(record, metadata);
        EngineContext engine = EngineContext.getEngineContext();
        var frame = engine.pushVerifiedExecution("instance", "3", metadata);
        try {
            hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0, Map.of()), metadata);
        } finally { engine.popVerifiedExecution(frame); }
        ProcessRecord forged = new ProcessRecord(new LaunchConfiguration("instance.remove", "instance", "3", 5L, 0,
                Map.of(ProcessAuthorization.DATA_KEY, Map.of("targetId", "99"))), 902L, "test");
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(forged, metadata)).getCode());
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")),
                new ApiKeyPolicy.Rule("deny-old", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("container", "1i3"), Set.of("upgrade")))));
        assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(record, metadata)).getCode());
    }
    @Test public void trueUpgradeNicCleanupUsesOriginOperationAndRejectsForgedCrossChildOrDeny() {
        removalLifecycle(); metadata.put("requestAction", "upgrade");
        var nic = new io.cattle.platform.core.model.tables.records.NicRecord();
        nic.setId(15L); nic.setAccountId(5L); nic.setInstanceId(3L); nic.setState("removing");
        when(hook.objectManager.loadResource("nic", "15")).thenReturn(nic);
        when(hook.objectManager.loadResource(io.cattle.platform.core.model.Nic.class, 15L)).thenReturn(nic);
        var engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("instance", "3", metadata);
        LaunchConfiguration child = new LaunchConfiguration("nic.remove", "nic", "15", 5L, 0, Map.of());
        try {
            hook.beforeExecution(child, metadata);
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")),
                    new ApiKeyPolicy.Rule("deny-nic", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("nic", "1internal15"), Set.of("upgrade")))));
            assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child, metadata)).getCode());
            nic.setInstanceId(99L);
            assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child, metadata)).getCode());
        } finally { engine.popVerifiedExecution(frame); }
    }

    @Test public void finishUpgradeRemovalCannotHideContainerVolumeOrTombstoneExplicitDeny() {
        removalLifecycle();
        for (var scope : List.of(ApiKeyPolicy.Scope.resource("container", "1i3"), ApiKeyPolicy.Scope.resource("volume", "1v6"),
                ApiKeyPolicy.Scope.resource("serviceExposeMap", "1sem1"))) {
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")),
                    new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, scope, Set.of("upgrade")))));
            EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("instance", "3", metadata);
            try { assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0, Map.of()), metadata)).getCode()); }
            finally { engine.popVerifiedExecution(frame); }
        }
    }
    @Test public void finishUpgradeRemovalRechecksKeyRevisionOwnerSchemaAndExpiryBeforeAncestry() {
        for (String invalid : List.of("revoked", "revision", "owner", "schema", "expired", "no-root-grant")) {
            setup(); removalLifecycle();
            String expected = "OwnerPermissionDenied";
            switch (invalid) {
                case "revoked" -> { when(key.getState()).thenReturn("inactive"); expected = "ApiKeyRevoked"; }
                case "revision" -> { metadata.put("policyRevision", 0L); expected = "ApiKeyPolicyChanged"; }
                case "owner" -> {
                    Policy noOwner = mock(Policy.class); SchemaFactory factory = mock(SchemaFactory.class); when(factory.getSchema("service")).thenReturn(schema);
                    when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(noOwner, factory));
                }
                case "schema" -> when(schema.getResourceActions()).thenReturn(Map.of("upgrade", mock(Action.class)));
                case "expired" -> { policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, now, List.of())); expected = "ApiKeyExpired"; }
                case "no-root-grant" -> { policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of())); expected = "KeyScopeDenied"; }
            }
            assertEquals(invalid, expected, assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(new LaunchConfiguration("instance.remove", "instance", "3", 5L, 0, Map.of()), metadata)).getCode());
        }
    }
    @Test public void upgradeOrForgedProcessDataCannotUseFinishUpgradeTombstoneAndFrame() {
        removalLifecycle();
        metadata.put("requestAction", "upgrade");
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(new LaunchConfiguration("instance.remove", "instance", "3", 5L, 0, Map.of()), metadata)).getCode());
        metadata.put("requestAction", "finishupgrade");
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0,
                        Map.of("verifiedExecution", Map.of("resourceType", "instance", "resourceId", "3"), "_apiKeyAudit", metadata)), metadata)).getCode());
    }
    @Test public void fullAndLegacyRemovalGetNoNewTombstoneOrFrameRestriction() {
        removalLifecycle(); hook.targets = spy(hook.targets);
        for (boolean full : List.of(false, true)) {
            if (full) policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
            else { when(key.getData()).thenReturn(Map.of()); metadata.put("policyRevision", 0L); }
            hook.beforeExecution(new LaunchConfiguration("instance.remove", "instance", "3", 5L, 0, Map.of()), metadata);
            hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0, Map.of()), metadata);
        }
        verify(hook.targets, never()).resolveUpgradeRemovalDependency(any(), any(), any(), anyString(), anyString(), any(), any());
        verify(hook.targets, never()).resolveUpgradeRemovalDependency(any(), any(), any(), anyString(), anyString(), any(), any(), any());
    }

    private Volume cleanupLifecycle() {
        Volume volume = removalLifecycle(); volume.setState("removing"); volume.setUuid("private-volume");
        Instance instance = hook.objectManager.loadResource(Instance.class, 3L);
        when(instance.getImageId()).thenReturn(7L); when(instance.getRegistryCredentialId()).thenReturn(null);
        ImageRecord image = new ImageRecord(); image.setId(7L); image.setUuid("private-image"); image.setFormat("docker");
        image.setInstanceKind("container"); image.setState("active");
        ImageStoragePoolMapRecord cache = new ImageStoragePoolMapRecord(); cache.setId(14L); cache.setUuid("private-cache");
        cache.setImageId(7L); cache.setStoragePoolId(9L); cache.setState("active");
        StoragePoolRecord pool = new StoragePoolRecord(); pool.setId(9L); pool.setAccountId(5L); pool.setUuid("pool-nine");
        VolumeStoragePoolMapRecord allocation = new VolumeStoragePoolMapRecord(); allocation.setId(12L); allocation.setUuid("private-allocation");
        allocation.setVolumeId(6L); allocation.setStoragePoolId(9L); allocation.setState("inactive");
        when(hook.objectManager.loadResource(Image.class, 7L)).thenReturn(image);
        when(hook.objectManager.loadResource("image", "7")).thenReturn(image);
        when(hook.objectManager.loadResource(ImageStoragePoolMap.class, 14L)).thenReturn(cache);
        when(hook.objectManager.loadResource("imageStoragePoolMap", "14")).thenReturn(cache);
        when(hook.objectManager.loadResource(VolumeStoragePoolMap.class, 12L)).thenReturn(allocation);
        when(hook.objectManager.loadResource("volumeStoragePoolMap", "12")).thenReturn(allocation);
        when(hook.objectManager.loadResource(StoragePool.class, 9L)).thenReturn(pool);
        when(hook.objectManager.find(VolumeStoragePoolMap.class, VOLUME_STORAGE_POOL_MAP.VOLUME_ID, 6L)).thenReturn(List.of(allocation));
        when(hook.objectManager.find(ImageStoragePoolMap.class,
                io.cattle.platform.core.model.tables.ImageStoragePoolMapTable.IMAGE_STORAGE_POOL_MAP.IMAGE_ID, 7L)).thenReturn(List.of(cache));
        when(hook.objectManager.find(Volume.class, io.cattle.platform.core.model.tables.VolumeTable.VOLUME.IMAGE_ID, 7L,
                io.cattle.platform.core.model.tables.VolumeTable.VOLUME.REMOVED, null)).thenReturn(List.of(volume));
        when(hook.objectManager.find(Instance.class, io.cattle.platform.core.model.tables.InstanceTable.INSTANCE.IMAGE_ID, 7L,
                io.cattle.platform.core.model.tables.InstanceTable.INSTANCE.REMOVED, null)).thenReturn(List.of(instance));
        return volume;
    }
    private void executePrivateImageCleanup() {
        EngineContext engine = EngineContext.getEngineContext(); var volume = engine.pushVerifiedExecution("volume", "6", metadata);
        try {
            for (String action : List.of("deactivate", "remove"))
                hook.beforeExecution(new LaunchConfiguration("image." + action, "image", "7", null, 0, Map.of()), metadata);
            var image = engine.pushVerifiedExecution("image", "7", metadata);
            try {
                for (String action : List.of("deactivate", "remove"))
                    hook.beforeExecution(new LaunchConfiguration("imagestoragepoolmap." + action, "imageStoragePoolMap", "14", null, 0, Map.of()), metadata);
            } finally { engine.popVerifiedExecution(image); }
        } finally { engine.popVerifiedExecution(volume); }
    }
    @Test public void actualNullAclPrivateImageAndCacheCleanupKeepsCustomRootGrantAndFullLegacyCompatibility() {
        cleanupLifecycle();
        var current = hook.authenticator.currentAuthorization(10L, 5L, new io.github.ibuildthecloud.gdapi.request.ApiRequest(null, null));
        assertNull(current.policy().authorizeObject(hook.objectManager.loadResource(Image.class, 7L)));
        // AccountPolicy allows mappings without an accountId property; image
        // rows themselves have NULL accountId and require the narrow fallback.
        assertNotNull(current.policy().authorizeObject(hook.objectManager.loadResource(ImageStoragePoolMap.class, 14L)));
        executePrivateImageCleanup();
        Policy cacheDenied = spy(current.policy());
        doAnswer(i -> i.getArgument(0) instanceof ImageStoragePoolMap ? null : current.policy().authorizeObject(i.getArgument(0)))
                .when(cacheDenied).authorizeObject(any());
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any()))
                .thenReturn(new ApiAuthenticator.CurrentAuthorization(cacheDenied, current.schemas()));
        executePrivateImageCleanup();
        Instance instance = hook.objectManager.loadResource(Instance.class, 3L);
        when(hook.objectManager.loadResource("container", 3L)).thenReturn(instance);
        metadata.put("targetType", "container"); metadata.put("targetId", "3"); metadata.put("operation", "remove");
        metadata.put("requestMethod", "DELETE"); metadata.remove("requestAction");
        SchemaFactory factory = mock(SchemaFactory.class); when(factory.getSchema("container")).thenReturn(schema);
        when(schema.getResourceMethods()).thenReturn(List.of("DELETE"));
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any()))
                .thenReturn(new ApiAuthenticator.CurrentAuthorization(cacheDenied, factory));
        for (boolean full : List.of(false, true)) {
            if (full) policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
            else { when(key.getData()).thenReturn(Map.of()); metadata.put("policyRevision", 0L); }
            executePrivateImageCleanup();
        }
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
    }
    @Test public void customPrivateCleanupKeepsEveryExplicitChildDenialAndFreshCredentialBarrier() {
        for (String invalid : List.of("image-deny", "cache-deny", "pool-deny", "revoked", "revision", "expired", "schema", "owner", "no-root-grant")) {
            setup(); cleanupLifecycle(); String expected = "KeyPolicyDenied";
            switch (invalid) {
                case "image-deny", "cache-deny", "pool-deny" -> {
                    String type = invalid.equals("image-deny") ? "image" : invalid.equals("cache-deny") ? "imageStoragePoolMap" : "storagePool";
                    String id = invalid.equals("image-deny") ? "7" : invalid.equals("cache-deny") ? "14" : "9";
                    policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                            new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")),
                            new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource(type, "1internal" + id), Set.of("upgrade")))));
                }
                case "revoked" -> { when(key.getState()).thenReturn("inactive"); expected = "ApiKeyRevoked"; }
                case "revision" -> { metadata.put("policyRevision", 0L); expected = "ApiKeyPolicyChanged"; }
                case "expired" -> { policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, now, List.of())); expected = "ApiKeyExpired"; }
                case "schema" -> { when(schema.getResourceActions()).thenReturn(Map.of()); expected = "OwnerPermissionDenied"; }
                case "owner" -> { Policy deniedOwner = mock(Policy.class); SchemaFactory factory = mock(SchemaFactory.class); when(factory.getSchema("service")).thenReturn(schema);
                    when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(deniedOwner, factory)); expected = "OwnerPermissionDenied"; }
                case "no-root-grant" -> { policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of())); expected = "KeyScopeDenied"; }
            }
            assertEquals(invalid, expected, assertThrows(ProcessAuthorizationDeniedException.class, this::executePrivateImageCleanup).getCode());
        }
    }
    @Test public void queuedPrivateMapRetryUsesFreshKeyPolicyAndRootNotParentRoleSnapshot() {
        cleanupLifecycle(); EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(metadata);
        var frame = engine.pushVerifiedExecution("volume", "6", metadata); ProcessRecord queued;
        try {
            LaunchConfiguration launch = new LaunchConfiguration("volumestoragepoolmap.remove", "volumeStoragePoolMap", "12", null, 0, Map.of());
            ProcessAuthorization.prepare(launch, List.of()); queued = new ProcessRecord(launch, 99L, "server");
            hook.beforeExecution(queued, metadata);
        } finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
        assertNull(engine.currentVerifiedExecution()); hook.beforeExecution(queued, metadata);
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")),
                new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("volume", "1v6"), Set.of("upgrade")))));
        assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(queued, metadata)).getCode());
        metadata.put("policyRevision", 0L);
        assertEquals("ApiKeyPolicyChanged", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(queued, metadata)).getCode());
        when(key.getState()).thenReturn("inactive");
        assertEquals("ApiKeyRevoked", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(queued, metadata)).getCode());
    }
    private VolumeRecord liveOsVolume() {
        ServiceExposeMap exposure = upgradeLifecycle();
        when(exposure.getState()).thenReturn("active");
        metadata.put("preview", false);
        DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(mock(SchemaFactory.class));
        formatter.setTypeMappings(Map.of("credential", "c", "project", "a", "stack", "st", "service", "s", "container", "i", "volume", "v"));
        hook.targets.idFormatter = formatter;
        Instance instance = hook.objectManager.loadResource("instance", "3");
        when(instance.getState()).thenReturn("stopping");
        when(hook.objectManager.loadResource(Instance.class, 3L)).thenReturn(instance);
        VolumeRecord volume = new VolumeRecord(); volume.setId(6L); volume.setAccountId(5L); volume.setInstanceId(3L);
        volume.setDeviceNumber(0); volume.setState("active");
        when(hook.objectManager.loadResource("volume", "6")).thenReturn(volume);
        when(hook.objectManager.loadResource(Volume.class, 6L)).thenReturn(volume);
        when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of());
        VolumeStoragePoolMapRecord map = new VolumeStoragePoolMapRecord(); map.setId(12L); map.setVolumeId(6L); map.setStoragePoolId(9L); map.setState("active");
        when(hook.objectManager.loadResource("volumeStoragePoolMap", "12")).thenReturn(map);
        when(hook.objectManager.loadResource(VolumeStoragePoolMap.class, 12L)).thenReturn(map);
        return volume;
    }
    @Test public void liveUpgradeStopAndRestartKeepExistingOsVolumeLifecycle() {
        VolumeRecord volume = liveOsVolume(); var engine = EngineContext.getEngineContext();
        assertNull(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null).stream().findFirst().orElse(null));
        // This is the actual InstanceStop chain: instance -> OS volume -> map.
        // It has no Mount parent and retains the live managed=false/upgrade map.
        for (String action : List.of("stop", "start")) {
            hook.beforeExecution(new ProcessRecord(new LaunchConfiguration("instance." + action, "instance", "3", 5L, 0,
                    Map.of(ProcessAuthorization.DATA_KEY, metadata)), 900L, "test"), metadata);
            var instanceFrame = engine.pushVerifiedExecution("instance", "3", metadata);
            try {
                String storageAction = action.equals("stop") ? "deactivate" : "activate";
                hook.beforeExecution(new LaunchConfiguration("volume." + storageAction, "volume", "6", 5L, 0, Map.of()), metadata);
                volume.setState(action.equals("stop") ? "deactivating" : "activating");
                var volumeFrame = engine.pushVerifiedExecution("volume", "6", metadata);
                try { hook.beforeExecution(new LaunchConfiguration("volumestoragepoolmap." + storageAction,
                        "volumeStoragePoolMap", "12", 5L, 0, Map.of()), metadata); }
                finally { engine.popVerifiedExecution(volumeFrame); }
            } finally { engine.popVerifiedExecution(instanceFrame); }
            volume.setState(action.equals("stop") ? "inactive" : "active");
        }
        // Finishupgrade later retires that same map before the remove chain.
        ServiceExposeMap map = hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true).getFirst();
        when(map.getId()).thenReturn(1L); when(map.getState()).thenReturn("removed"); when(map.getRemoved()).thenReturn(new java.util.Date(1));
        when(hook.objectManager.loadResource(ServiceExposeMap.class, 1L)).thenReturn(map);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true)).thenReturn(List.of());
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.SERVICE_ID, 2L, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true,
                SERVICE_EXPOSE_MAP.STATE, "removed")).thenReturn(List.of(map));
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L, SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of());
        when(hook.objectManager.loadResource(Instance.class, 3L).getState()).thenReturn("removing");
        volume.setState("detached"); volume.setUuid("os-volume");
        hook.beforeExecution(new ProcessRecord(new LaunchConfiguration("instance.remove", "instance", "3", 5L, 0,
                Map.of(ProcessAuthorization.DATA_KEY, metadata)), 901L, "test"), metadata);
        var instanceFrame = engine.pushVerifiedExecution("instance", "3", metadata);
        try { hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0, Map.of()), metadata); }
        finally { engine.popVerifiedExecution(instanceFrame); }
        volume.setState("removing");
        var pool = new StoragePoolRecord(); pool.setId(9L); pool.setAccountId(5L); pool.setUuid("pool-nine");
        when(hook.objectManager.loadResource(StoragePool.class, 9L)).thenReturn(pool);
        var allocation = (VolumeStoragePoolMapRecord) hook.objectManager.loadResource(VolumeStoragePoolMap.class, 12L);
        allocation.setUuid("os-map");
        var volumeFrame = engine.pushVerifiedExecution("volume", "6", metadata);
        try {
            hook.beforeExecution(new LaunchConfiguration("volume.deallocate", "volume", "6", 5L, 0, Map.of()), metadata);
            for (String action : List.of("deactivate", "remove")) hook.beforeExecution(new LaunchConfiguration(
                    "volumestoragepoolmap." + action, "volumeStoragePoolMap", "12", 5L, 0, Map.of()), metadata);
        } finally { engine.popVerifiedExecution(volumeFrame); }
    }
    @Test public void liveDeactivationStillRejectsCrossStackMountsWrongIdsAndExplicitDenies() {
        VolumeRecord volume = liveOsVolume(); var engine = EngineContext.getEngineContext();
        var instanceFrame = engine.pushVerifiedExecution("instance", "3", metadata);
        try {
            // Named volume detachment is authorized from its live Mount graph.
            volume.setName("persistent-data"); volume.setDeviceNumber(null); volume.setInstanceId(null);
            var mount = new io.cattle.platform.core.model.tables.records.MountRecord();
            mount.setId(7L); mount.setAccountId(5L); mount.setVolumeId(6L); mount.setInstanceId(3L); mount.setState("active");
            when(hook.objectManager.loadResource(Mount.class, 7L)).thenReturn(mount);
            when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of(mount));
            var deactivate = new LaunchConfiguration("volume.deactivate", "volume", "6", 5L, 0, Map.of());
            hook.beforeExecution(deactivate, metadata);
            for (var scope : List.of(ApiKeyPolicy.Scope.resource("volume", "1v6"), ApiKeyPolicy.Scope.resource("container", "1i3"))) {
                policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                        new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")),
                        new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, scope, Set.of("upgrade")))));
                assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(deactivate, metadata)).getCode());
            }
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                    List.of(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")))));
            var foreign = new io.cattle.platform.core.model.tables.records.InstanceRecord();
            foreign.setId(99L); foreign.setAccountId(5L); foreign.setStackId(88L);
            var stack = new io.cattle.platform.core.model.tables.records.StackRecord(); stack.setId(88L); stack.setAccountId(5L);
            when(hook.objectManager.loadResource(Instance.class, 99L)).thenReturn(foreign);
            when(hook.objectManager.loadResource(Stack.class, 88L)).thenReturn(stack);
            mount.setInstanceId(99L);
            assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(deactivate, metadata)).getCode());
            mount.setInstanceId(3L);
            var map = (VolumeStoragePoolMapRecord) hook.objectManager.loadResource(VolumeStoragePoolMap.class, 12L); map.setVolumeId(99L);
            var volumeFrame = engine.pushVerifiedExecution("volume", "6", metadata);
            try {
                var mapJob = new LaunchConfiguration("volumestoragepoolmap.deactivate", "volumeStoragePoolMap", "12", 5L, 0, Map.of());
                assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(mapJob, metadata)).getCode());
                map.setVolumeId(6L); hook.beforeExecution(mapJob, metadata);
                policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                        new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")),
                        new ApiKeyPolicy.Rule("deny-map", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("volumeStoragePoolMap",
                                String.valueOf(hook.targets.idFormatter.formatId("volumeStoragePoolMap", 12L))), Set.of("upgrade")))));
                assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(mapJob, metadata)).getCode());
                volume.setAccountId(99L);
                assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(deactivate, metadata)).getCode());
            } finally { engine.popVerifiedExecution(volumeFrame); }
        } finally { engine.popVerifiedExecution(instanceFrame); }
    }

    @Test public void managedScaleDownRetainsOnlyItsVerifiedQueuedStopAndRemoveAfterMapRetires() {
        ServiceExposeMap map = managedLifecycle();
        ProcessRecord stop = managedJob("instance.stop", "service", "2");
        hook.beforeExecution(stop, metadata);
        retireManagedMap(map);
        hook.beforeExecution(stop, metadata);
        Instance instance = hook.objectManager.loadResource(Instance.class, 3L);
        when(instance.getState()).thenReturn("removing");
        hook.beforeExecution(managedJob("instance.remove", "instance", "3"), metadata);
        // Ordinary ancestry must still exclude the retired map.
        Account project = hook.objectManager.loadResource(Account.class, 5L), principal = mock(Account.class);
        when(principal.getId()).thenReturn(10L);
        assertNull(hook.targets.resolveObject("container", instance,
                new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions())).stackId());
    }

    @Test public void retiredManagedMapCannotAuthorizeMissingForgedOrWrongParentProof() {
        ServiceExposeMap map = managedLifecycle(); retireManagedMap(map);
        for (String parent : List.of("none", "other-service", "wrong-binding")) {
            LaunchConfiguration launch = new LaunchConfiguration("instance.stop", "instance", "3", 5L, 0,
                    Map.of(ProcessAuthorization.VERIFIED_CHILD_KEY, Map.of("parentId", "2")));
            EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(metadata);
            var frame = "none".equals(parent) ? null : engine.pushVerifiedExecution("service",
                    "other-service".equals(parent) ? "9" : "2",
                    "wrong-binding".equals(parent) ? Map.of("keyId", "different") : metadata);
            try { ProcessAuthorization.prepare(launch, List.of()); }
            finally { if (frame != null) engine.popVerifiedExecution(frame); engine.popAuthorization(); }
            assertEquals(parent, "KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(new ProcessRecord(launch, 99L, "server"), metadata)).getCode());
        }
    }

    @Test public void managedRetirementRejectsForeignReassignedAndNonManagedRelationships() {
        ServiceExposeMap map = managedLifecycle(); ProcessRecord stop = managedJob("instance.stop", "service", "2");
        retireManagedMap(map);
        for (String invalid : List.of("project", "instance", "service", "unmanaged", "upgrade")) {
            when(map.getAccountId()).thenReturn(invalid.equals("project") ? 6L : 5L);
            when(map.getInstanceId()).thenReturn(invalid.equals("instance") ? 4L : 3L);
            when(map.getServiceId()).thenReturn(invalid.equals("service") ? 9L : 2L);
            when(map.getManaged()).thenReturn(!invalid.equals("unmanaged"));
            when(map.getUpgrade()).thenReturn(invalid.equals("upgrade"));
            assertEquals(invalid, invalid.equals("project") ? "OwnerPermissionDenied" : "KeyScopeDenied",
                    assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(stop, metadata)).getCode());
        }
        when(map.getUpgrade()).thenReturn(false);
        ServiceExposeMap reassigned = mock(ServiceExposeMap.class);
        when(reassigned.getId()).thenReturn(7L); when(reassigned.getInstanceId()).thenReturn(3L);
        when(reassigned.getServiceId()).thenReturn(9L); when(reassigned.getAccountId()).thenReturn(5L);
        when(reassigned.getManaged()).thenReturn(true); when(reassigned.getUpgrade()).thenReturn(false);
        when(hook.objectManager.loadResource(ServiceExposeMap.class, 7L)).thenReturn(reassigned);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of(reassigned));
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(stop, metadata)).getCode());
    }

    @Test public void managedRetirementStillRevalidatesChildDenyKeyRevisionExpiryAndOwner() {
        ServiceExposeMap map = managedLifecycle(); ProcessRecord stop = managedJob("instance.stop", "service", "2");
        retireManagedMap(map);
        for (String type : List.of("container", "serviceExposeMap")) {
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")),
                    new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY,
                            ApiKeyPolicy.Scope.resource(type, String.valueOf(hook.targets.idFormatter.formatId(type,
                                    type.equals("container") ? 3L : 1L))), Set.of("update")))));
            assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(stop, metadata)).getCode());
        }
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")))));
        when(key.getState()).thenReturn("inactive");
        assertEquals("ApiKeyRevoked", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(stop, metadata)).getCode());
        when(key.getState()).thenReturn("active"); metadata.put("policyRevision", 0L);
        assertEquals("ApiKeyPolicyChanged", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(stop, metadata)).getCode());
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, now,
                List.of(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")))));
        assertEquals("ApiKeyExpired", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(stop, metadata)).getCode());
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")))));
        when(projectAuthorityDao.hasAccessToProject(eq(5L), eq(5L), eq(false), any())).thenReturn(false);
        assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(stop, metadata)).getCode());
    }

    @Test public void managedStopPreservesExactOsVolumeAndNetworkTeardownWithoutNamedOrSharedVolumeRemoval() {
        ServiceExposeMap map = managedLifecycle(); retireManagedMap(map);
        VolumeRecord volume = new VolumeRecord(); volume.setId(6L); volume.setAccountId(5L); volume.setInstanceId(3L);
        volume.setDeviceNumber(0); volume.setState("active");
        when(hook.objectManager.loadResource(Volume.class, 6L)).thenReturn(volume);
        when(hook.objectManager.loadResource("volume", "6")).thenReturn(volume);
        when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of());
        var port = new io.cattle.platform.core.model.tables.records.PortRecord();
        port.setId(15L); port.setInstanceId(3L); port.setAccountId(5L); port.setState("active");
        when(hook.objectManager.loadResource(io.cattle.platform.core.model.Port.class, 15L)).thenReturn(port);
        when(hook.objectManager.loadResource("port", "15")).thenReturn(port);
        EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("instance", "3", metadata);
        try {
            hook.beforeExecution(new LaunchConfiguration("volume.deactivate", "volume", "6", 5L, 0, Map.of()), metadata);
            hook.beforeExecution(new LaunchConfiguration("port.deactivate", "port", "15", 5L, 0, Map.of()), metadata);
            volume.setDeviceNumber(1);
            assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0, Map.of()), metadata)).getCode());
            volume.setDeviceNumber(0);
            var shared = new io.cattle.platform.core.model.tables.records.MountRecord();
            shared.setId(16L); shared.setInstanceId(4L); shared.setVolumeId(6L); shared.setAccountId(5L); shared.setState("active");
            when(hook.objectManager.loadResource(Mount.class, 16L)).thenReturn(shared);
            when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of(shared));
            assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0, Map.of()), metadata)).getCode());
        } finally { engine.popVerifiedExecution(frame); }
    }

    @Test public void serviceAndStackDeleteRetainTheirOriginalDeleteGrantThroughRetiredChildCleanup() {
        for (String rootType : List.of("service", "stack")) {
            setup(); ServiceExposeMap map = managedLifecycle();
            metadata.put("operation", "delete"); metadata.put("requestMethod", "DELETE");
            when(schema.getResourceMethods()).thenReturn(List.of("DELETE"));
            if (rootType.equals("stack")) managedStackRoot(map);
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                    List.of(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("delete")))));
            ProcessRecord stop = managedJob("instance.stop", "service", "2");
            hook.beforeExecution(stop, metadata);
            retireManagedMap(map);
            Service service = hook.objectManager.loadResource(Service.class, 2L);
            when(service.getState()).thenReturn("removed"); when(service.getRemoved()).thenReturn(new java.util.Date(2));
            hook.beforeExecution(stop, metadata);
            when(hook.objectManager.loadResource(Instance.class, 3L).getState()).thenReturn("removing");
            hook.beforeExecution(managedJob("instance.remove", "instance", "3"), metadata);
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("delete")),
                    new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("service", "1s2"), Set.of("delete")))));
            assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(stop, metadata)).getCode());
        }
    }

    @Test public void managedOsVolumePoolStopWithoutMountSurvivesQueuedRetryAndRejectsRebindingOrExplicitDeny() {
        ServiceExposeMap map = managedLifecycle(); retireManagedMap(map);
        VolumeRecord volume = new VolumeRecord(); volume.setId(6L); volume.setAccountId(5L); volume.setInstanceId(3L);
        volume.setDeviceNumber(0); volume.setState("deactivating"); volume.setUuid("private-os-volume");
        when(hook.objectManager.loadResource(Volume.class, 6L)).thenReturn(volume);
        when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of());
        VolumeStoragePoolMapRecord allocation = new VolumeStoragePoolMapRecord();
        allocation.setId(12L); allocation.setVolumeId(6L); allocation.setStoragePoolId(9L); allocation.setState("active"); allocation.setUuid("private-map");
        when(hook.objectManager.loadResource(VolumeStoragePoolMap.class, 12L)).thenReturn(allocation);
        when(hook.objectManager.loadResource("volumeStoragePoolMap", "12")).thenReturn(allocation);
        StoragePoolRecord pool = new StoragePoolRecord(); pool.setId(9L); pool.setAccountId(5L); pool.setUuid("pool-nine");
        when(hook.objectManager.loadResource(StoragePool.class, 9L)).thenReturn(pool);
        EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(metadata);
        var frame = engine.pushVerifiedExecution("volume", "6", metadata);
        ProcessRecord queued;
        try {
            LaunchConfiguration launch = new LaunchConfiguration("volumestoragepoolmap.deactivate", "volumeStoragePoolMap", "12", 5L, 0, Map.of());
            ProcessAuthorization.prepare(launch, List.of()); queued = new ProcessRecord(launch, 99L, "server");
            hook.beforeExecution(queued, metadata);
        } finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
        hook.beforeExecution(queued, metadata);
        pool.setUuid("different-pool");
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(queued, metadata)).getCode());
        pool.setUuid("pool-nine");
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")),
                new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY,
                        ApiKeyPolicy.Scope.resource("volumeStoragePoolMap", "1internal12"), Set.of("update")))));
        assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(queued, metadata)).getCode());
    }

    @Test public void managedNamedAndFileVolumesKeepLiveMountDeactivationWithoutPrivateInstance() {
        for (String name : List.of("named-data", "/file-data")) {
            setup(); managedLifecycle(); VolumeRecord volume = managedMountedVolume(); volume.setName(name);
            assertNull(volume.getInstanceId()); assertNull(volume.getDeviceNumber());
            EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("mount", "7", metadata);
            try {
                hook.beforeExecution(new LaunchConfiguration("volume.deactivate", "volume", "6", 5L, 0, Map.of()), metadata);
                assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                        () -> hook.beforeExecution(new LaunchConfiguration("volume.remove", "volume", "6", 5L, 0, Map.of()), metadata)).getCode());
            } finally { engine.popVerifiedExecution(frame); }
        }
    }

    @Test public void managedNamedVolumeRetiredMountRetryPreservesSharedCrossAndExplicitDenyBoundaries() {
        ServiceExposeMap map = managedLifecycle(); managedMountedVolume(); retireManagedMap(map);
        EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(metadata);
        var frame = engine.pushVerifiedExecution("mount", "7", metadata); ProcessRecord queued;
        try {
            LaunchConfiguration launch = new LaunchConfiguration("volume.deactivate", "volume", "6", 5L, 0, Map.of());
            ProcessAuthorization.prepare(launch, List.of()); queued = new ProcessRecord(launch, 99L, "server");
            hook.beforeExecution(queued, metadata);
        } finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
        hook.beforeExecution(queued, metadata);
        Mount mount = hook.objectManager.loadResource(Mount.class, 7L);
        var other = new io.cattle.platform.core.model.tables.records.MountRecord();
        other.setId(8L); other.setVolumeId(6L); other.setAccountId(5L); other.setInstanceId(3L); other.setState("active");
        when(hook.objectManager.loadResource(Mount.class, 8L)).thenReturn(other);
        when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of(mount, other));
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(queued, metadata)).getCode());
        other.setState("inactive"); other.setInstanceId(4L);
        var foreign = new io.cattle.platform.core.model.tables.records.InstanceRecord();
        foreign.setId(4L); foreign.setAccountId(5L); foreign.setStackId(8L); foreign.setState("stopping");
        when(hook.objectManager.loadResource(Instance.class, 4L)).thenReturn(foreign);
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(queued, metadata)).getCode());
        when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of(mount));
        for (String type : List.of("volume", "mount")) {
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")),
                    new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY,
                            ApiKeyPolicy.Scope.resource(type, String.valueOf(hook.targets.idFormatter.formatId(type,
                                    type.equals("volume") ? 6L : 7L))), Set.of("update")))));
            assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(queued, metadata)).getCode());
        }
    }

    private VolumeRecord managedMountedVolume() {
        VolumeRecord volume = new VolumeRecord(); volume.setId(6L); volume.setAccountId(5L); volume.setStackId(8L); volume.setState("active");
        when(hook.objectManager.loadResource(Volume.class, 6L)).thenReturn(volume);
        when(hook.objectManager.loadResource("volume", "6")).thenReturn(volume);
        var mount = new io.cattle.platform.core.model.tables.records.MountRecord();
        mount.setId(7L); mount.setVolumeId(6L); mount.setAccountId(5L); mount.setInstanceId(3L); mount.setState("inactive");
        when(hook.objectManager.loadResource(Mount.class, 7L)).thenReturn(mount);
        when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of(mount));
        return volume;
    }

    @Test public void healthyServiceStopRestartAndStackStopKeepTheirExistingLiveManagedAncestry() {
        for (String action : List.of("deactivate", "restart", "deactivateservices")) {
            setup(); ServiceExposeMap map = managedLifecycle();
            metadata.put("requestMethod", "POST"); metadata.put("requestAction", action);
            when(schema.getResourceActions()).thenReturn(Map.of(action, mock(Action.class)));
            if (action.equals("deactivateservices")) managedStackRoot(map);
            hook.beforeExecution(new LaunchConfiguration("instance.stop", "instance", "3", 5L, 0, Map.of()), metadata);
            hook.beforeExecution(new LaunchConfiguration("instance.start", "instance", "3", 5L, 0, Map.of()), metadata);
            if (action.equals("restart")) {
                ProcessRecord stop = managedJob("instance.stop", "service", "2");
                retireManagedMap(map); hook.beforeExecution(stop, metadata);
            }
        }
    }

    @Test public void inactiveServiceDeactivationWithLiveManagedMapAndNullInstanceParentsUsesPersistedProofAndRealFormatter() throws Exception {
        managedLifecycle(); metadata.put("requestMethod", "POST"); metadata.put("requestAction", "deactivate");
        when(schema.getResourceActions()).thenReturn(Map.of("deactivate", new Action()));
        var service = new io.cattle.platform.core.model.tables.records.ServiceRecord();
        service.setId(2L); service.setAccountId(5L); service.setStackId(8L); service.setState("inactive");
        var instance = new io.cattle.platform.core.model.tables.records.InstanceRecord();
        instance.setId(3L); instance.setAccountId(5L); instance.setState("stopping");
        var map = new io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord();
        map.setId(1L); map.setAccountId(5L); map.setInstanceId(3L); map.setServiceId(2L);
        map.setManaged(true); map.setUpgrade(false); map.setState("active");
        when(hook.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        when(hook.objectManager.loadResource("service", 2L)).thenReturn(service);
        when(hook.objectManager.loadResource(Instance.class, 3L)).thenReturn(instance);
        when(hook.objectManager.loadResource("instance", "3")).thenReturn(instance);
        when(hook.objectManager.loadResource(ServiceExposeMap.class, 1L)).thenReturn(map);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.SERVICE_ID, 2L, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(map));
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(map));
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of(map));
        var factory = mock(SchemaFactory.class);
        when(factory.getBaseType("container")).thenReturn("instance"); when(factory.getBaseType("project")).thenReturn("account");
        DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(factory);
        formatter.setTypeMappings(Map.of("credential", "c", "account", "a", "service", "s", "stack", "st", "instance", "i"));
        hook.targets.idFormatter = formatter;
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("project", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5"),
                        Set.of("read", "create", "update", "delete"))), ApiKeyPolicy.Purpose.AI_BRIDGE));
        ProcessRecord stop = managedJob("instance.stop", "service", "2");
        var json = new io.cattle.platform.json.JacksonJsonMapper(); stop.setData(json.readValue(json.writeValueAsString(stop.getData())));
        Map<String, Object> stored = ProcessAuthorization.metadata(stop);
        assertNotNull(ProcessAuthorization.verifiedChild(stop)); assertEquals("active", map.getState());
        assertNull(instance.getServiceId()); assertNull(instance.getStackId()); ApiContext.remove();
        try (RealWorkerAuthority authority = new RealWorkerAuthority()) {
            hook.authenticator = authority.authenticator;
            hook.beforeExecution(stop, stored);
            assertNull(ApiContext.getContext());
        }
    }

    @Test public void serviceDeactivationCompletionBetweenAuthorizationReadsKeepsManagedStopAuthorized() throws Exception {
        inactiveServiceDeactivationWithLiveManagedMapAndNullInstanceParentsUsesPersistedProofAndRealFormatter();
        Service current = hook.objectManager.loadResource(Service.class, 2L);
        var beforeCompletion = new io.cattle.platform.core.model.tables.records.ServiceRecord();
        beforeCompletion.setId(current.getId()); beforeCompletion.setAccountId(current.getAccountId());
        beforeCompletion.setStackId(current.getStackId()); beforeCompletion.setState("deactivating");
        // The worker's root read can precede ServiceDeactivate's final state
        // write. Its subsequent ownership reload observes inactive, with the
        // same persisted identity, account, stack and still-live managed map.
        when(hook.objectManager.loadResource("service", 2L)).thenReturn(beforeCompletion);
        ProcessRecord stop = managedJob("instance.stop", "service", "2");
        var json = new io.cattle.platform.json.JacksonJsonMapper();
        stop.setData(json.readValue(json.writeValueAsString(stop.getData())));
        ApiContext.remove();
        try (RealWorkerAuthority authority = new RealWorkerAuthority()) {
            hook.authenticator = authority.authenticator;
            for (int replay = 0; replay < 100; replay++) hook.beforeExecution(stop, ProcessAuthorization.metadata(stop));
        }
    }

    @Test public void completedDeactivationRetainsIdentityOwnerMappingKeyAndExplicitDenyChecks() throws Exception {
        for (String mutation : List.of("uuid", "owner", "stack", "mapping", "instance", "revision", "expired", "revoked", "principal", "deny")) {
            setup();
            inactiveServiceDeactivationWithLiveManagedMapAndNullInstanceParentsUsesPersistedProofAndRealFormatter();
            var current = (io.cattle.platform.core.model.tables.records.ServiceRecord) hook.objectManager.loadResource(Service.class, 2L);
            current.setUuid("original-service");
            var before = new io.cattle.platform.core.model.tables.records.ServiceRecord();
            before.setId(2L); before.setAccountId(5L); before.setStackId(8L);
            before.setUuid("original-service"); before.setState("deactivating");
            when(hook.objectManager.loadResource("service", 2L)).thenReturn(before);
            if (mutation.equals("uuid")) current.setUuid("replacement-service");
            if (mutation.equals("owner")) current.setAccountId(6L);
            if (mutation.equals("stack")) current.setStackId(9L);
            var map = (io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord) hook.objectManager.loadResource(ServiceExposeMap.class, 1L);
            if (mutation.equals("mapping")) map.setServiceId(9L);
            if (mutation.equals("instance")) map.setInstanceId(4L);
            if (mutation.equals("revision")) {
                var codec = new ApiKeyPolicyCodec();
                Map<String, Object> changed = codec.store(key, codec.read(key), 2);
                when(key.getData()).thenReturn(changed);
            }
            if (mutation.equals("revoked")) when(key.getState()).thenReturn("inactive");
            if (mutation.equals("expired") || mutation.equals("deny")) {
                policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY,
                        mutation.equals("expired") ? now.minusSeconds(1) : null,
                        List.of(new ApiKeyPolicy.Rule("project", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5"), Set.of("update")),
                                new ApiKeyPolicy.Rule("child", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("container", "1i3"),
                                        mutation.equals("deny") ? Set.of("update") : Set.of("delete"))), ApiKeyPolicy.Purpose.AI_BRIDGE));
            }
            ProcessRecord stop = managedJob("instance.stop", "service", "2");
            try (RealWorkerAuthority authority = new RealWorkerAuthority()) {
                if (mutation.equals("principal")) authority.principal.setState("inactive");
                hook.authenticator = authority.authenticator; ApiContext.remove();
                String expected = switch (mutation) {
                    case "owner", "principal" -> "OwnerPermissionDenied";
                    case "revision" -> "ApiKeyPolicyChanged";
                    case "expired" -> "ApiKeyExpired";
                    case "revoked" -> "ApiKeyRevoked";
                    case "deny" -> "KeyPolicyDenied";
                    default -> "KeyScopeDenied";
                };
                assertEquals(mutation, expected, assertThrows(ProcessAuthorizationDeniedException.class,
                        () -> hook.beforeExecution(stop, ProcessAuthorization.metadata(stop))).getCode());
            }
        }
    }

    @Test public void completedDeactivationDoesNotAuthorizeUnrelatedStateDrift() throws Exception {
        for (String state : List.of("active", "activating", "restarting", "removing", "removed")) {
            setup();
            inactiveServiceDeactivationWithLiveManagedMapAndNullInstanceParentsUsesPersistedProofAndRealFormatter();
            var before = new io.cattle.platform.core.model.tables.records.ServiceRecord();
            before.setId(2L); before.setAccountId(5L); before.setStackId(8L); before.setState(state);
            when(hook.objectManager.loadResource("service", 2L)).thenReturn(before);
            ProcessRecord stop = managedJob("instance.stop", "service", "2");
            try (RealWorkerAuthority authority = new RealWorkerAuthority()) {
                hook.authenticator = authority.authenticator;
                assertEquals(state, "KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                        () -> hook.beforeExecution(stop, ProcessAuthorization.metadata(stop))).getCode());
            }
        }
    }

    @Test public void serviceDeleteCompletionBetweenReadsKeepsItsAlreadyScheduledStopAuthorized() throws Exception {
        inactiveServiceDeactivationWithLiveManagedMapAndNullInstanceParentsUsesPersistedProofAndRealFormatter();
        metadata.put("operation", "delete"); metadata.put("requestMethod", "DELETE"); metadata.remove("requestAction");
        var current = (io.cattle.platform.core.model.tables.records.ServiceRecord) hook.objectManager.loadResource(Service.class, 2L);
        current.setUuid("service-delete"); current.setState("removing");
        var before = new io.cattle.platform.core.model.tables.records.ServiceRecord();
        before.setId(2L); before.setAccountId(5L); before.setStackId(8L);
        before.setUuid("service-delete"); before.setState("removing");
        var rootRead = new java.util.concurrent.atomic.AtomicBoolean();
        when(hook.objectManager.loadResource("service", 2L)).thenAnswer(call -> { rootRead.set(true); return before; });
        // ServiceRemove schedules the stop before it completes. The subsequent
        // ownership reload is a deterministic barrier for its final row write.
        ProcessRecord stop = managedJob("instance.stop", "service", "2");
        when(hook.objectManager.loadResource(Service.class, 2L)).thenAnswer(call -> {
            assertTrue(rootRead.get()); assertNotNull(ProcessAuthorization.verifiedChild(stop));
            current.setState("removed"); current.setRemoved(new java.util.Date(1)); return current;
        });
        try (RealWorkerAuthority authority = new RealWorkerAuthority()) {
            hook.authenticator = authority.authenticator; ApiContext.remove();
            for (int replay = 0; replay < 100; replay++) hook.beforeExecution(stop, ProcessAuthorization.metadata(stop));
        }
        assertEquals("removed", current.getState());
    }

    @Test public void stackDeleteMapRetirementBetweenReadsKeepsItsAlreadyScheduledStopAuthorized() throws Exception {
        inactiveServiceDeactivationWithLiveManagedMapAndNullInstanceParentsUsesPersistedProofAndRealFormatter();
        metadata.put("operation", "delete"); metadata.put("requestMethod", "DELETE"); metadata.remove("requestAction");
        var before = (io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord) hook.objectManager.loadResource(ServiceExposeMap.class, 1L);
        before.setUuid("managed-map");
        metadata.put("targetType", "stack"); metadata.put("targetId", "8");
        Stack stack = hook.objectManager.loadResource(Stack.class, 8L);
        when(hook.objectManager.loadResource("stack", 8L)).thenReturn(stack);
        var queryRead = new java.util.concurrent.atomic.AtomicBoolean();
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.MANAGED, true)).thenAnswer(call -> { queryRead.set(true); return List.of(before); });
        var current = new io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord();
        current.setId(1L); current.setAccountId(5L); current.setInstanceId(3L); current.setServiceId(2L);
        current.setUuid("managed-map"); current.setManaged(true); current.setUpgrade(false);
        var service = (io.cattle.platform.core.model.tables.records.ServiceRecord) hook.objectManager.loadResource(Service.class, 2L);
        service.setState("removed"); service.setRemoved(new java.util.Date(1));
        // StackRemove schedules ServiceRemove; that owner retires its SEM after
        // scheduling the instance. Complete that write between query/reload.
        ProcessRecord stop = managedJob("instance.stop", "service", "2");
        when(hook.objectManager.loadResource(ServiceExposeMap.class, 1L)).thenAnswer(call -> {
            assertTrue(queryRead.get()); assertNotNull(ProcessAuthorization.verifiedChild(stop));
            current.setState("removed"); current.setRemoved(new java.util.Date(1)); return current;
        });
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of());
        try (RealWorkerAuthority authority = new RealWorkerAuthority()) {
            hook.authenticator = authority.authenticator; ApiContext.remove();
            for (int replay = 0; replay < 100; replay++) hook.beforeExecution(stop, ProcessAuthorization.metadata(stop));
        }
        assertEquals("removed", current.getState());
    }

    @Test public void nativeScaleDownCompletionBetweenReadsKeepsItsRetiredManagedStopAuthorized() throws Exception {
        inactiveServiceDeactivationWithLiveManagedMapAndNullInstanceParentsUsesPersistedProofAndRealFormatter();
        metadata.put("operation", "update"); metadata.put("requestMethod", "PUT"); metadata.remove("requestAction");
        var current = (io.cattle.platform.core.model.tables.records.ServiceRecord) hook.objectManager.loadResource(Service.class, 2L);
        current.setUuid("scale-down"); current.setState("updating-active");
        var before = new io.cattle.platform.core.model.tables.records.ServiceRecord();
        before.setId(2L); before.setAccountId(5L); before.setStackId(8L);
        before.setUuid("scale-down"); before.setState("updating-active");
        var rootRead = new java.util.concurrent.atomic.AtomicBoolean();
        when(hook.objectManager.loadResource("service", 2L)).thenAnswer(call -> { rootRead.set(true); return before; });
        ProcessRecord stop = managedJob("instance.stop", "service", "2");
        var map = (io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord) hook.objectManager.loadResource(ServiceExposeMap.class, 1L);
        map.setState("removed"); map.setRemoved(new java.util.Date(1));
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of());
        when(hook.objectManager.loadResource(Service.class, 2L)).thenAnswer(call -> {
            assertTrue(rootRead.get()); assertNotNull(ProcessAuthorization.verifiedChild(stop));
            current.setState("active"); return current;
        });
        try (RealWorkerAuthority authority = new RealWorkerAuthority()) {
            hook.authenticator = authority.authenticator; ApiContext.remove();
            for (int replay = 0; replay < 100; replay++) hook.beforeExecution(stop, ProcessAuthorization.metadata(stop));
        }
    }

    @Test public void mapRetirementProgressStillRejectsReplacementReassignmentAndUntrustedJobs() throws Exception {
        stackDeleteMapRetirementBetweenReadsKeepsItsAlreadyScheduledStopAuthorized();
        var current = (io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord) hook.objectManager.loadResource(ServiceExposeMap.class, 1L);
        when(hook.objectManager.loadResource(ServiceExposeMap.class, 1L)).thenReturn(current);
        var instance = (io.cattle.platform.core.model.tables.records.InstanceRecord) hook.objectManager.loadResource(Instance.class, 3L);
        try (RealWorkerAuthority authority = new RealWorkerAuthority()) {
            hook.authenticator = authority.authenticator;
            for (String mutation : List.of("uuid", "owner", "service", "instance", "unmanaged", "upgrade", "state", "running", "proof")) {
                current.setUuid("managed-map"); current.setAccountId(5L); current.setServiceId(2L); current.setInstanceId(3L);
                current.setManaged(true); current.setUpgrade(false); current.setState("removed"); current.setRemoved(new java.util.Date(1));
                instance.setState("stopping");
                if (mutation.equals("uuid")) current.setUuid("different-map");
                if (mutation.equals("owner")) current.setAccountId(6L);
                if (mutation.equals("service")) current.setServiceId(9L);
                if (mutation.equals("instance")) current.setInstanceId(4L);
                if (mutation.equals("unmanaged")) current.setManaged(false);
                if (mutation.equals("upgrade")) current.setUpgrade(true);
                if (mutation.equals("state")) { current.setState("inactive"); current.setRemoved(null); }
                if (mutation.equals("running")) instance.setState("running");
                ProcessRecord stop = managedJob("instance.stop", "service", "2");
                if (mutation.equals("proof")) stop.getData().remove(ProcessAuthorization.VERIFIED_CHILD_KEY);
                assertEquals(mutation, mutation.equals("owner") ? "OwnerPermissionDenied" : "KeyScopeDenied",
                        assertThrows(ProcessAuthorizationDeniedException.class,
                                () -> hook.beforeExecution(stop, ProcessAuthorization.metadata(stop))).getCode());
            }
        }
    }

    @Test public void managedMapRemoveTransitionBetweenReadsKeepsScheduledStopAuthorized() throws Exception {
        stackDeleteMapRetirementBetweenReadsKeepsItsAlreadyScheduledStopAuthorized();
        var current = (io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord) hook.objectManager.loadResource(ServiceExposeMap.class, 1L);
        when(hook.objectManager.loadResource(ServiceExposeMap.class, 1L)).thenReturn(current);
        current.setState("removing"); current.setRemoved(null);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of(current));
        ProcessRecord stop = managedJob("instance.stop", "service", "2");
        try (RealWorkerAuthority authority = new RealWorkerAuthority()) {
            hook.authenticator = authority.authenticator;
            for (int replay = 0; replay < 100; replay++) hook.beforeExecution(stop, ProcessAuthorization.metadata(stop));
        }
    }

    /** Real worker authorization: only persisted account reads are stubbed. */
    private static final class RealWorkerAuthority implements AutoCloseable {
        private static final String ADMIN_OPTION = "account.type.admin.all.accounts";
        private final Object previous = com.netflix.config.ConfigurationManager.getConfigInstance().getProperty(ADMIN_OPTION);
        final ApiAuthenticator authenticator = new ApiAuthenticator();
        final io.cattle.platform.core.model.tables.records.AccountRecord principal =
                new io.cattle.platform.core.model.tables.records.AccountRecord();

        RealWorkerAuthority() throws Exception {
            com.netflix.config.ConfigurationManager.getConfigInstance().setProperty(ADMIN_OPTION, true);
            principal.setId(10L); principal.setKind("admin"); principal.setState("active");
            var project = new io.cattle.platform.core.model.tables.records.AccountRecord();
            project.setId(5L); project.setKind("project"); project.setState("active");
            var auth = mock(io.cattle.platform.iaas.api.auth.dao.impl.AuthDaoImpl.class, CALLS_REAL_METHODS);
            doReturn(principal).when(auth).getAccountById(10L);
            doReturn(project).when(auth).getAccountById(5L);
            var accounts = mock(io.cattle.platform.core.dao.impl.AccountDaoImpl.class, CALLS_REAL_METHODS);
            doReturn(principal).when(accounts).getAccountById(10L);
            doReturn(project).when(accounts).getAccountById(5L);
            var core = mock(SchemaFactory.class);
            java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
            while (root != null && !java.nio.file.Files.isDirectory(root.resolve("resources/content/schema/v1"))) root = root.getParent();
            assertNotNull(root);
            var role = new io.cattle.platform.api.schema.FileSchemaFactory();
            role.setFile("schema/v1/owner.ser"); role.setId("owner"); role.setSchemaFactory(core);
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            try (var loader = new java.net.URLClassLoader(new java.net.URL[] { root.resolve("resources/content").toUri().toURL() }, original)) {
                Thread.currentThread().setContextClassLoader(loader);
                role.start();
            } finally { Thread.currentThread().setContextClassLoader(original); }
            assertTrue(role.getSchema("service").getResourceActions().containsKey("deactivate"));
            var authorization = new io.cattle.platform.iaas.api.auth.impl.DefaultAuthorizationProvider();
            inject(authorization, "authDao", auth); inject(authorization, "accountDao", accounts);
            authorization.setOptionsFactory(new io.cattle.platform.iaas.api.auth.AchaiusPolicyOptionsFactory());
            authorization.setSchemaFactoryList(List.of(role)); authorization.start();
            authenticator.setAuthDao(auth); inject(authenticator, "accountDao", accounts);
            authenticator.setAuthorizationProviders(List.of(authorization));
            authenticator.setIdentityProviders(List.of(new io.cattle.platform.iaas.api.auth.integration.internal.rancher.RancherIdentityProvider()));
            var external = mock(io.cattle.platform.iaas.api.auth.integration.external.ExternalServiceAuthProvider.class);
            when(external.getPrincipalIdentities(any())).thenReturn(Set.of());
            inject(authenticator, "externalAuthProvider", external);
        }

        private static void inject(Object target, String name, Object value) throws Exception {
            var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
        }

        @Override public void close() {
            if (previous == null) com.netflix.config.ConfigurationManager.getConfigInstance().clearProperty(ADMIN_OPTION);
            else com.netflix.config.ConfigurationManager.getConfigInstance().setProperty(ADMIN_OPTION, previous);
        }
    }

    @Test public void stackRetirementCannotBorrowAnotherStacksServiceOrDifferentOperation() {
        ServiceExposeMap map = managedLifecycle(); managedStackRoot(map);
        metadata.put("operation", "delete"); metadata.put("requestMethod", "DELETE");
        when(schema.getResourceMethods()).thenReturn(List.of("DELETE"));
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("delete")))));
        ProcessRecord stop = managedJob("instance.stop", "service", "2"); retireManagedMap(map);
        when(hook.objectManager.loadResource(Service.class, 2L).getStackId()).thenReturn(9L);
        assertEquals("KeyScopeDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(stop, metadata)).getCode());
    }

    private void managedStackRoot(ServiceExposeMap map) {
        metadata.put("targetType", "stack"); metadata.put("targetId", "8");
        Stack stack = hook.objectManager.loadResource(Stack.class, 8L);
        when(hook.objectManager.loadResource("stack", 8L)).thenReturn(stack);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(map));
        Account principal = mock(Account.class); when(principal.getId()).thenReturn(10L);
        Policy current = new AccountPolicy(hook.objectManager.loadResource(Account.class, 5L), principal, Set.of(), new NoPolicyOptions());
        SchemaFactory factory = mock(SchemaFactory.class); when(factory.getSchema("stack")).thenReturn(schema);
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any()))
                .thenReturn(new ApiAuthenticator.CurrentAuthorization(current, factory));
    }

    private ProcessRecord managedJob(String process, String parentType, String parentId) {
        EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(metadata);
        var parent = engine.pushVerifiedExecution(parentType, parentId, metadata);
        LaunchConfiguration launch = new LaunchConfiguration(process, "instance", "3", 5L, 0, Map.of());
        try { ProcessAuthorization.prepare(launch, List.of()); }
        finally { engine.popVerifiedExecution(parent); engine.popAuthorization(); }
        return new ProcessRecord(launch, 99L, "server");
    }

    private ServiceExposeMap managedLifecycle() {
        ServiceExposeMap map = upgradeLifecycle();
        when(map.getId()).thenReturn(1L); when(map.getState()).thenReturn("active"); when(map.getRemoved()).thenReturn(null);
        when(map.getManaged()).thenReturn(true); when(map.getUpgrade()).thenReturn(false);
        Instance instance = hook.objectManager.loadResource("instance", "3"); when(instance.getState()).thenReturn("stopping");
        when(instance.getRemoved()).thenReturn(null); when(hook.objectManager.loadResource(Instance.class, 3L)).thenReturn(instance);
        when(hook.objectManager.loadResource(ServiceExposeMap.class, 1L)).thenReturn(map);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.SERVICE_ID, 2L, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(map));
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(map));
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true)).thenReturn(List.of());
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of(map));
        metadata.put("operation", "update"); metadata.put("requestMethod", "PUT"); metadata.put("preview", false);
        metadata.remove("requestAction");
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")))));
        return map;
    }

    private void retireManagedMap(ServiceExposeMap map) {
        when(map.getState()).thenReturn("removed"); when(map.getRemoved()).thenReturn(new java.util.Date(1));
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of());
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of());
    }

    private ServiceExposeMap upgradeLifecycle() {
        Account project = mock(Account.class), principal = mock(Account.class);
        when(project.getId()).thenReturn(5L); when(project.getKind()).thenReturn("project"); when(principal.getId()).thenReturn(10L);
        AccountPolicy real = new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions());
        Service service = mock(Service.class); when(service.getId()).thenReturn(2L);
        when(service.getAccountId()).thenReturn(5L); when(service.getStackId()).thenReturn(8L);
        Instance instance = mock(Instance.class); when(instance.getId()).thenReturn(3L); when(instance.getAccountId()).thenReturn(5L);
        // The real old upgrade child has NULL denormalized parents. Mockito's
        // boxed Long defaults are zero, which would create a spurious Service 0.
        when(instance.getServiceId()).thenReturn(null); when(instance.getStackId()).thenReturn(null);
        Stack stack = mock(Stack.class); when(stack.getId()).thenReturn(8L); when(stack.getAccountId()).thenReturn(5L);
        ServiceExposeMap map = mock(ServiceExposeMap.class);
        when(map.getInstanceId()).thenReturn(3L); when(map.getServiceId()).thenReturn(2L); when(map.getAccountId()).thenReturn(5L);
        when(map.getManaged()).thenReturn(false); when(map.getUpgrade()).thenReturn(true);
        when(hook.objectManager.loadResource(Account.class, 5L)).thenReturn(project);
        when(hook.objectManager.loadResource(Stack.class, 8L)).thenReturn(stack);
        when(hook.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        when(hook.objectManager.loadResource("service", 2L)).thenReturn(service);
        when(hook.objectManager.loadResource("instance", "3")).thenReturn(instance);
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of());
        when(hook.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true)).thenReturn(List.of(map));
        ApiKeyTargetResolver actual = new ApiKeyTargetResolver(); actual.objectManager = hook.objectManager;
        projectAuthorityDao = mock(io.cattle.platform.iaas.api.auth.dao.AuthDao.class);
        var projectAccounts = mock(io.cattle.platform.core.dao.AccountDao.class);
        when(projectAuthorityDao.hasAccessToProject(eq(5L), eq(5L), eq(false), any())).thenReturn(true);
        when(projectAccounts.isActiveAccount(project)).thenReturn(true);
        actual.projectAccess = new io.cattle.platform.iaas.api.auth.projects.ProjectAccessService(projectAuthorityDao, projectAccounts);
        actual.idFormatter = mock(IdFormatter.class);
        when(actual.idFormatter.parseId(anyString())).thenAnswer(i -> i.<String>getArgument(0).replaceFirst("^1[a-z]+", ""));
        when(actual.idFormatter.formatId(anyString(), any())).thenAnswer(i -> "1" +
                Map.of("credential", "c", "project", "a", "stack", "st", "service", "s", "container", "i").getOrDefault(i.<String>getArgument(0), "internal") + i.getArgument(1));
        hook.targets = actual;
        SchemaFactory factory = mock(SchemaFactory.class); when(factory.getSchema("service")).thenReturn(schema);
        when(schema.getResourceActions()).thenReturn(Map.of("upgrade", mock(Action.class), "finishupgrade", mock(Action.class)));
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(real, factory));
        metadata.put("targetType", "service"); metadata.put("targetId", "2"); metadata.put("operation", "upgrade");
        metadata.put("requestMethod", "POST"); metadata.put("requestCollection", false); metadata.put("requestAction", "upgrade");
        policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("upgrade")))));
        // Exercise the actual resolver outside the process hook's exception
        // translation so a fixture/round-trip error retains its original source.
        assertEquals(Long.valueOf(1L), actual.parseScopeId("credential", "1c1"));
        assertEquals(ApiKeyPolicyEvaluator.Target.stackResource("service", "1s2", "1a5", "1st8"),
                actual.resolveObject("service", service, real));
        assertEquals(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"),
                actual.resolveObject("container", instance, real));
        return map;
    }
}
