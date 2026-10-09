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
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.tables.records.InstanceHostMapRecord;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.engine.process.LaunchConfiguration;
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
