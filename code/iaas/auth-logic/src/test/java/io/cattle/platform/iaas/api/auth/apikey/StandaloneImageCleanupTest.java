package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.VolumeTable.VOLUME;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.MountTable.MOUNT;
import static io.cattle.platform.core.model.tables.VolumeStoragePoolMapTable.VOLUME_STORAGE_POOL_MAP;
import static io.cattle.platform.core.model.tables.ImageStoragePoolMapTable.IMAGE_STORAGE_POOL_MAP;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.*;
import io.cattle.platform.core.model.tables.records.*;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/** Native instance.remove -> volume.remove -> NULL-account image and its cache.
 * The fixture reproduces the failed standalone container DELETE shape, not an
 * upgrade/Service grant or a public Image operation. No API/process is run. */
public class StandaloneImageCleanupTest {
    ApiKeyProcessAuthorization hook;
    ApiKeyTargetResolver targets;
    CredentialRecord key;
    InstanceRecord instance;
    VolumeRecord volume;
    ImageRecord image;
    ImageStoragePoolMapRecord cache;
    StoragePoolRecord pool;
    VolumeStoragePoolMapRecord allocation;
    SchemaFactory schemas;
    Schema schema;
    Policy owner;
    Map<String, Object> binding;
    Instant now = Instant.parse("2026-11-01T06:28:21Z");

    @Before public void setup() {
        ApiContext.remove();
        hook = new ApiKeyProcessAuthorization(); hook.objectManager = mock(ObjectManager.class);
        hook.authenticator = mock(ApiAuthenticator.class); hook.clock = Clock.fixed(now, ZoneOffset.UTC);
        var project = new AccountRecord(); project.setId(5L); project.setKind("project"); project.setState("active");
        var principal = new AccountRecord(); principal.setId(1L);
        owner = new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions());
        schemas = mock(SchemaFactory.class); schema = mock(Schema.class);
        when(schema.getResourceMethods()).thenReturn(List.of("DELETE")); when(schemas.getSchema("container")).thenReturn(schema);
        when(schemas.getBaseType("container")).thenReturn("instance"); when(schemas.getBaseType("project")).thenReturn("account");
        when(hook.objectManager.getSchemaFactory()).thenReturn(schemas);
        when(hook.authenticator.currentAuthorization(eq(1L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, schemas));
        targets = new ApiKeyTargetResolver(); targets.objectManager = hook.objectManager; hook.targets = targets;
        var roles = mock(AuthDao.class); var accounts = mock(io.cattle.platform.core.dao.AccountDao.class);
        when(roles.hasAccessToProject(eq(5L), eq(5L), eq(false), any())).thenReturn(true);
        when(accounts.isActiveAccount(project)).thenReturn(true); targets.projectAccess = new ProjectAccessService(roles, accounts);
        var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
        formatter.setTypeMappings(Map.of("credential", "c", "account", "a", "instance", "i", "container", "i", "volume", "v",
                "image", "img", "storagePool", "sp", "imageStoragePoolMap", "ispm", "volumeStoragePoolMap", "vspm"));
        targets.idFormatter = formatter;
        when(hook.objectManager.loadResource(Account.class, 5L)).thenReturn(project);
        key = new CredentialRecord(); key.setId(1L); key.setAccountId(1L); key.setKind("apiKeyRestricted"); key.setState("active");
        when(hook.objectManager.loadResource(Credential.class, 1L)).thenReturn(key);
        instance = new InstanceRecord(); instance.setId(3L); instance.setUuid("own-instance-uuid"); instance.setAccountId(5L);
        instance.setKind("container"); instance.setState("removing"); instance.setImageId(7L);
        volume = new VolumeRecord(); volume.setId(6L); volume.setUuid("own-root-volume-uuid"); volume.setAccountId(5L);
        volume.setInstanceId(3L); volume.setDeviceNumber(0); volume.setImageId(7L); volume.setState("removing");
        image = new ImageRecord(); image.setId(7L); image.setUuid("own-private-image-uuid"); image.setState("active");
        image.setFormat("docker"); image.setInstanceKind("container"); // NULL accountId is the actual native image shape.
        cache = new ImageStoragePoolMapRecord(); cache.setId(14L); cache.setUuid("own-private-cache-uuid");
        cache.setImageId(7L); cache.setStoragePoolId(9L); cache.setState("inactive");
        pool = new StoragePoolRecord(); pool.setId(9L); pool.setUuid("own-allocation-pool-uuid"); pool.setAccountId(5L); pool.setState("active");
        allocation = new VolumeStoragePoolMapRecord(); allocation.setId(12L); allocation.setUuid("own-volume-allocation-uuid");
        allocation.setVolumeId(6L); allocation.setStoragePoolId(9L); allocation.setState("inactive");
        when(hook.objectManager.loadResource("container", 3L)).thenReturn(instance);
        when(hook.objectManager.loadResource(Instance.class, 3L)).thenReturn(instance);
        when(hook.objectManager.loadResource(Volume.class, 6L)).thenReturn(volume);
        when(hook.objectManager.loadResource("image", "7")).thenReturn(image);
        when(hook.objectManager.loadResource(Image.class, 7L)).thenReturn(image);
        when(hook.objectManager.loadResource("imageStoragePoolMap", "14")).thenReturn(cache);
        when(hook.objectManager.loadResource(ImageStoragePoolMap.class, 14L)).thenReturn(cache);
        when(hook.objectManager.loadResource(StoragePool.class, 9L)).thenReturn(pool);
        when(hook.objectManager.loadResource(VolumeStoragePoolMap.class, 12L)).thenReturn(allocation);
        when(hook.objectManager.find(Volume.class, VOLUME.INSTANCE_ID, 3L, VOLUME.DEVICE_NUMBER, 0, VOLUME.REMOVED, null)).thenReturn(List.of(volume));
        when(hook.objectManager.find(Volume.class, VOLUME.IMAGE_ID, 7L, VOLUME.REMOVED, null)).thenReturn(List.of(volume));
        when(hook.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenReturn(List.of(instance));
        when(hook.objectManager.find(VolumeStoragePoolMap.class, VOLUME_STORAGE_POOL_MAP.VOLUME_ID, 6L)).thenReturn(List.of(allocation));
        when(hook.objectManager.find(ImageStoragePoolMap.class, IMAGE_STORAGE_POOL_MAP.IMAGE_ID, 7L)).thenReturn(List.of(cache));
        binding = new LinkedHashMap<>(Map.of("keyId", "1c1", "policyRevision", 1L, "principalAccountId", 1L, "accountId", 5L,
                "operation", "delete", "targetType", "container", "targetId", "3", "requestMethod", "DELETE", "requestCollection", false));
        binding.put("preview", false); binding.put("requestId", "aichange-own-container-delete");
        policy(List.of());
    }

    private void policy(List<ApiKeyPolicy.Rule> extra) {
        var rules = new java.util.ArrayList<ApiKeyPolicy.Rule>();
        rules.add(new ApiKeyPolicy.Rule("own-container", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("container", "1i3"), Set.of("delete")));
        rules.addAll(extra);
        key.setData(new ApiKeyPolicyCodec().store(key, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY,
                now.plusSeconds(3600), rules, ApiKeyPolicy.Purpose.AI_BRIDGE), 1));
    }

    private ProcessRecord child(String type, String process, String id) {
        return new ProcessRecord(new LaunchConfiguration(process, type, id, null, 0, Map.of(ProcessAuthorization.DATA_KEY, binding)), null, "offline");
    }

    private void cleanup() {
        var engine = EngineContext.getEngineContext();
        var nativeRoot = engine.pushVerifiedExecution("instance", "3", "instance.remove", binding);
        var nativeVolume = engine.pushVerifiedExecution("volume", "6", "volume.remove", binding);
        try {
            hook.beforeExecution(child("image", "image.deactivate", "7"), binding);
            hook.beforeExecution(child("image", "image.remove", "7"), binding);
            var nativeImage = engine.pushVerifiedExecution("image", "7", "image.remove", binding);
            try {
                hook.beforeExecution(child("imageStoragePoolMap", "imagestoragepoolmap.deactivate", "14"), binding);
                hook.beforeExecution(child("imageStoragePoolMap", "imagestoragepoolmap.remove", "14"), binding);
            } finally { engine.popVerifiedExecution(nativeImage); }
        } finally { engine.popVerifiedExecution(nativeVolume); engine.popVerifiedExecution(nativeRoot); }
    }

    @Test public void customNativeDeleteCleansOnlyItsPrivateRootImageAndCache() {
        assertNull(owner.authorizeObject(image));
        cleanup();
        assertNull(ApiContext.getContext()); assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
    }

    @Test public void exactRemoveFramesAreRequiredAndPublicImageDoesNotGainAuthority() {
        assertThrows(ProcessAuthorizationDeniedException.class, ()->hook.beforeExecution(child("image", "image.remove", "7"), binding));
        var engine = EngineContext.getEngineContext(); var nativeRoot = engine.pushVerifiedExecution("instance", "3", "instance.stop", binding);
        var nativeVolume = engine.pushVerifiedExecution("volume", "6", "volume.remove", binding);
        try { assertThrows(ProcessAuthorizationDeniedException.class, ()->hook.beforeExecution(child("image", "image.remove", "7"), binding)); }
        finally { engine.popVerifiedExecution(nativeVolume); engine.popVerifiedExecution(nativeRoot); }
        binding.put("targetType", "image"); binding.put("targetId", "7");
        assertThrows(ProcessAuthorizationDeniedException.class, this::cleanup);
    }

    @Test public void nonDeleteOrForgedChildMetadataCannotBorrowTheNativeFrames() {
        for (String invalid : List.of("stop", "preview", "collection", "action", "link", "forged-child")) {
            setup();
            switch (invalid) {
                case "stop" -> binding.put("operation", "stop");
                case "preview" -> binding.put("preview", true);
                case "collection" -> binding.put("requestCollection", true);
                case "action" -> binding.put("requestAction", "remove");
                case "link" -> binding.put("requestLink", "other");
            }
            if (!invalid.equals("forged-child")) assertThrows(invalid, ProcessAuthorizationDeniedException.class, this::cleanup);
            else {
                var engine = EngineContext.getEngineContext(); var root = engine.pushVerifiedExecution("instance", "3", "instance.remove", binding);
                var frame = engine.pushVerifiedExecution("volume", "6", "volume.remove", binding);
                try {
                    var forged = child("image", "image.remove", "7"); forged.setData(Map.of(ProcessAuthorization.DATA_KEY, Map.of("targetId", "99")));
                    assertThrows(ProcessAuthorizationDeniedException.class, ()->hook.beforeExecution(forged, binding));
                } finally { engine.popVerifiedExecution(frame); engine.popVerifiedExecution(root); }
            }
        }
    }

    @Test public void nonOsForeignSharedOrReboundGraphStillFailsClosed() {
        for (String invalid : List.of("data-volume", "missing-root-uuid", "missing-volume-uuid", "missing-image-uuid", "foreign-volume",
                "duplicate-root", "shared-image-volume", "shared-image-instance", "other-mount", "rebound-image", "foreign-cache-pool", "managed-instance")) {
            setup();
            switch (invalid) {
                case "data-volume" -> volume.setDeviceNumber(1);
                case "missing-root-uuid" -> instance.setUuid(null);
                case "missing-volume-uuid" -> volume.setUuid(null);
                case "missing-image-uuid" -> image.setUuid(null);
                case "foreign-volume" -> volume.setAccountId(6L);
                case "duplicate-root" -> when(hook.objectManager.find(Volume.class, VOLUME.INSTANCE_ID, 3L, VOLUME.DEVICE_NUMBER, 0, VOLUME.REMOVED,
                        null)).thenReturn(List.of(volume, volume));
                case "shared-image-volume" -> {
                    var other = new VolumeRecord(); other.setId(99L); other.setAccountId(5L); other.setInstanceId(4L); other.setImageId(7L);
                            other.setDeviceNumber(0);
                    when(hook.objectManager.loadResource(Volume.class, 99L)).thenReturn(other);
                    when(hook.objectManager.find(Volume.class, VOLUME.IMAGE_ID, 7L, VOLUME.REMOVED, null)).thenReturn(List.of(volume, other));
                }
                case "shared-image-instance" -> {
                    var other = new InstanceRecord(); other.setId(4L); other.setAccountId(5L); other.setImageId(7L);
                    when(hook.objectManager.loadResource(Instance.class, 4L)).thenReturn(other);
                    when(hook.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenReturn(List.of(instance, other));
                }
                case "other-mount" -> {
                    var mount = new MountRecord(); mount.setId(90L); mount.setVolumeId(6L); mount.setInstanceId(4L); mount.setAccountId(5L);
                    when(hook.objectManager.loadResource(Mount.class, 90L)).thenReturn(mount);
                    when(hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenReturn(List.of(mount));
                }
                case "rebound-image" -> {
                    var replacement = new ImageRecord(); replacement.from(image); replacement.setUuid("different-image-uuid");
                    when(hook.objectManager.loadResource("image", "7")).thenReturn(replacement);
                }
                case "foreign-cache-pool" -> cache.setStoragePoolId(99L);
                case "managed-instance" -> instance.setServiceId(20L);
            }
            assertThrows(invalid, ProcessAuthorizationDeniedException.class, this::cleanup);
        }
    }

    @Test public void everyActualDependencyExplicitDenyStillWinsOverTheContainerGrant() {
        for (var target : List.of(new String[]{"container", "1i3"}, new String[]{"volume", "1v6"}, new String[]{"image", "1img7"},
                new String[]{"imageStoragePoolMap", "1ispm14"}, new String[]{"volumeStoragePoolMap", "1vspm12"}, new String[]{"storagePool", "1sp9"})) {
            setup(); policy(List.of(new ApiKeyPolicy.Rule("explicit-deny", ApiKeyPolicy.Effect.DENY,
                    ApiKeyPolicy.Scope.resource(target[0], target[1]), Set.of("delete"))));
            assertEquals(target[0], "KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class, this::cleanup).getCode());
        }
    }

    @Test public void freshKeyRevisionExpiryOwnerSchemaAndRootGrantAreNeverBypassed() {
        for (String invalid : List.of("revoked", "revision", "expired", "owner", "schema", "no-root-grant")) {
            setup(); String expected = "OwnerPermissionDenied";
            switch (invalid) {
                case "revoked" -> { key.setState("inactive"); expected = "ApiKeyRevoked"; }
                case "revision" -> { binding.put("policyRevision", 0L); expected = "ApiKeyPolicyChanged"; }
                case "expired" -> { hook.clock = Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC); expected = "ApiKeyExpired"; }
                case "owner" -> when(hook.authenticator.currentAuthorization(eq(1L), eq(5L),
                        any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(mock(Policy.class), schemas));
                case "schema" -> when(schema.getResourceMethods()).thenReturn(List.of("GET"));
                case "no-root-grant" -> {
                    key.setData(new ApiKeyPolicyCodec().store(key, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY,
                            now.plusSeconds(3600), List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE), 1)); expected = "KeyScopeDenied";
                }
            }
            assertEquals(invalid, expected, assertThrows(ProcessAuthorizationDeniedException.class, this::cleanup).getCode());
        }
    }
}
