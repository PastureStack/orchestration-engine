package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Image;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
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
}
