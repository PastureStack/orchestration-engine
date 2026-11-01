package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.VolumeTable.VOLUME;
import static io.cattle.platform.core.model.tables.MountTable.MOUNT;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.tables.records.MountRecord;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/** Native standalone DELETE schedules its OS allocation removal, then the
 * Instance and Volume finish before that child executes. No API/process runs. */
public class StandaloneVolumePoolCleanupTest {
    StandaloneImageCleanupTest f;

    @Before public void setup() {
        f = new StandaloneImageCleanupTest(); f.setup();
        when(f.hook.objectManager.loadResource("volumeStoragePoolMap", "12")).thenReturn(f.allocation);
        when(f.hook.objectManager.find(Volume.class, VOLUME.INSTANCE_ID, 3L, VOLUME.DEVICE_NUMBER, 0)).thenReturn(List.of(f.volume));
        when(f.hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L)).thenReturn(List.of());
    }

    private ProcessRecord queued() {
        return queued("instance.remove", "volume.remove");
    }

    private ProcessRecord queued(String rootProcess, String volumeProcess) {
        return queued(rootProcess, volumeProcess, false);
    }

    private ProcessRecord queued(String rootProcess, String volumeProcess, boolean deallocation) {
        var engine = EngineContext.getEngineContext(); engine.pushAuthorization(f.binding);
        var root = engine.pushVerifiedExecution("instance", "3", rootProcess, f.binding);
        var volume = engine.pushVerifiedExecution("volume", "6", volumeProcess, f.binding);
        var nested = deallocation ? engine.pushVerifiedExecution("volume", "6", "volume.deallocate", f.binding) : null;
        try {
            var launch = new LaunchConfiguration("volumestoragepoolmap.remove", "volumeStoragePoolMap", "12", null, 0, Map.of());
            ProcessAuthorization.prepare(launch, List.of());
            var record = new ProcessRecord(launch, 99L, "offline");
            f.hook.beforeExecution(record, f.binding);
            return record;
        } finally {
            if (nested != null) engine.popVerifiedExecution(nested);
            engine.popVerifiedExecution(volume); engine.popVerifiedExecution(root); engine.popAuthorization();
        }
    }

    private void retired() {
        f.instance.setState("removed"); f.instance.setRemoved(new Date(1000));
        f.volume.setState("removed"); f.volume.setRemoved(new Date(1000));
        f.allocation.setState("removing");
    }

    private void execute(ProcessRecord record) {
        f.hook.beforeExecution(record, f.binding);
    }

    private void policy(List<ApiKeyPolicy.Rule> extra) {
        var rules = new java.util.ArrayList<ApiKeyPolicy.Rule>();
        rules.add(new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW,
                ApiKeyPolicy.Scope.resource("container", "1i3"), Set.of("delete")));
        rules.addAll(extra);
        f.key.setData(new ApiKeyPolicyCodec().store(f.key, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM,
                ApiKeyPolicy.Effect.DENY, f.now.plusSeconds(3600), rules, ApiKeyPolicy.Purpose.GENERAL), 1));
    }

    @Test public void scheduledOsAllocationRemovalSurvivesItsExactParentCompletion() {
        var record = queued(); retired();
        execute(record);
        assertEquals("own-instance-uuid", ProcessAuthorization.verifiedChild(record).references().get("instanceUuid"));
        assertEquals("own-root-volume-uuid", ProcessAuthorization.verifiedChild(record).references().get("volumeUuid"));
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
    }

    @Test public void nestedVolumeDeallocationPinsTheSameExactNativeCleanup() {
        var record = queued("instance.remove", "volume.remove", true); retired();
        execute(record);
        assertEquals("own-instance-uuid", ProcessAuthorization.verifiedChild(record).references().get("instanceUuid"));
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
    }

    private void crossedPurge(boolean nested) {
        for (String instanceState : List.of("purging", "purged")) {
            for (String volumeState : List.of("purging", "purged")) {
                setup(); var record = queued("instance.remove", "volume.remove", nested); retired();
                f.instance.setState(instanceState); f.volume.setState(volumeState);
                execute(record);
            }
        }
    }

    @Test public void pinnedDirectCleanupSurvivesPurgingAndPurgedAncestors() {
        crossedPurge(false);
    }

    @Test public void pinnedNestedCleanupSurvivesPurgingAndPurgedAncestors() {
        crossedPurge(true);
    }

    @Test public void historicalAncestorsCannotReviveUnpinnedForgedOrUnboundJobs() {
        for (String invalid : List.of("no-refs", "forged-uuid", "foreign-parent", "wrong-child", "unbound-launch")) {
            setup(); var record = queued(); retired();
            f.instance.setState("purged"); f.volume.setState("purged");
            var data = new java.util.HashMap<>(record.getData());
            var proof = new java.util.HashMap<>((Map<String, Object>)data.get(ProcessAuthorization.VERIFIED_CHILD_KEY));
            switch (invalid) {
                case "no-refs" -> proof.remove("references");
                case "forged-uuid" -> {
                    var pins = new java.util.HashMap<>((Map<String, String>)proof.get("references"));
                    pins.put("volumeUuid", "forged"); proof.put("references", pins);
                }
                case "foreign-parent" -> proof.put("parentId", "99");
                case "wrong-child" -> proof.put("childId", "99");
            }
            data.put(ProcessAuthorization.VERIFIED_CHILD_KEY, proof); record.setData(data);
            if (invalid.equals("unbound-launch")) {
                var launch = new LaunchConfiguration("volumestoragepoolmap.remove", "volumeStoragePoolMap", "12", null, 0, data);
                assertThrows(ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(launch, f.binding));
            } else assertThrows(invalid, ProcessAuthorizationDeniedException.class, () -> execute(record));
        }
    }

    @Test public void unpinnedLiveAncillaryVolumesRetainTheirExistingLifecycleAuthorization() {
        f.volume.setDeviceNumber(1);
        var record = queued();
        assertTrue(ProcessAuthorization.verifiedChild(record).references().isEmpty());
        setup();
        var mount = new MountRecord(); mount.setId(91L); mount.setUuid("own-mount"); mount.setVolumeId(6L);
        mount.setInstanceId(3L); mount.setAccountId(5L); mount.setState("inactive");
        when(f.hook.objectManager.loadResource(Mount.class, 91L)).thenReturn(mount);
        when(f.hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L)).thenReturn(List.of(mount));
        record = queued();
        assertTrue(ProcessAuthorization.verifiedChild(record).references().isEmpty());
    }

    @Test public void detachedMetadataCannotInventOrReplaceTheSchedulingProof() {
        var record = queued(); retired();
        record.setData(Map.of(ProcessAuthorization.DATA_KEY, f.binding));
        var missing = record;
        assertThrows(ProcessAuthorizationDeniedException.class, () -> execute(missing));
        setup(); record = queued(); retired();
        var data = new java.util.HashMap<>(record.getData());
        var proof = new java.util.HashMap<>((Map<String, Object>)data.get(ProcessAuthorization.VERIFIED_CHILD_KEY));
        proof.remove("references"); data.put(ProcessAuthorization.VERIFIED_CHILD_KEY, proof); record.setData(data);
        var unpinned = record;
        assertThrows(ProcessAuthorizationDeniedException.class, () -> execute(unpinned));
    }

    @Test public void delayedExecutionRejectsEveryReboundUuidForeignKeyAndOwner() {
        for (String invalid : List.of("root-uuid", "volume-uuid", "map-uuid", "pool-uuid", "volume-instance",
                "map-volume", "map-pool", "root-account", "volume-account", "pool-account")) {
            setup(); var record = queued(); retired();
            switch (invalid) {
                case "root-uuid" -> f.instance.setUuid("rebound");
                case "volume-uuid" -> f.volume.setUuid("rebound");
                case "map-uuid" -> f.allocation.setUuid("rebound");
                case "pool-uuid" -> f.pool.setUuid("rebound");
                case "volume-instance" -> f.volume.setInstanceId(4L);
                case "map-volume" -> f.allocation.setVolumeId(7L);
                case "map-pool" -> f.allocation.setStoragePoolId(10L);
                case "root-account" -> f.instance.setAccountId(6L);
                case "volume-account" -> f.volume.setAccountId(6L);
                case "pool-account" -> f.pool.setAccountId(6L);
            }
            assertThrows(invalid, ProcessAuthorizationDeniedException.class, () -> execute(record));
        }
    }

    @Test public void namedSharedManagedOrInconsistentStorageDoesNotGainCleanupAuthority() {
        for (String invalid : List.of("not-os", "named", "duplicate-os", "mount", "managed", "missing-root-removed", "missing-volume-removed", "active-root")) {
            setup(); var record = queued(); retired();
            switch (invalid) {
                case "not-os" -> f.volume.setDeviceNumber(1);
                case "named" -> { f.volume.setName("named-data"); f.volume.setInstanceId(null); f.volume.setDeviceNumber(null); }
                case "duplicate-os" -> when(f.hook.objectManager.find(Volume.class, VOLUME.INSTANCE_ID, 3L,
                        VOLUME.DEVICE_NUMBER, 0)).thenReturn(List.of(f.volume, f.volume));
                case "mount" -> {
                    var mount = new MountRecord(); mount.setId(91L); mount.setVolumeId(6L); mount.setInstanceId(4L);
                    when(f.hook.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L)).thenReturn(List.of(mount));
                }
                case "managed" -> f.instance.setServiceId(20L);
                case "missing-root-removed" -> { f.instance.setState("purged"); f.instance.setRemoved(null); }
                case "missing-volume-removed" -> { f.volume.setState("purging"); f.volume.setRemoved(null); }
                case "active-root" -> { f.instance.setState("running"); f.instance.setRemoved(null); }
            }
            assertThrows(invalid, ProcessAuthorizationDeniedException.class, () -> execute(record));
        }
    }

    @Test public void onlyTheActualRemoveFramesMayPinTheFutureCleanup() {
        for (var names : List.of(new String[]{"instance.stop", "volume.remove"}, new String[]{"instance.remove", "volume.deallocate"})) {
            setup();
            assertThrows(ProcessAuthorizationDeniedException.class, () -> queued(names[0], names[1]));
            assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
        }
        setup();
        assertThrows(ProcessAuthorizationDeniedException.class, () -> queued("instance.stop", "volume.remove", true));
        setup();
        assertThrows(ProcessAuthorizationDeniedException.class, () -> queued("instance.remove", "volume.deallocate", true));
    }

    @Test public void publicNonDeleteOrDifferentParentCannotBorrowTheNativeCleanupEdge() {
        for (String invalid : List.of("action", "link", "preview", "collection", "method", "operation", "parent")) {
            setup(); var record = queued(); retired();
            switch (invalid) {
                case "action" -> f.binding.put("requestAction", "remove");
                case "link" -> f.binding.put("requestLink", "other");
                case "preview" -> f.binding.put("preview", true);
                case "collection" -> f.binding.put("requestCollection", true);
                case "method" -> f.binding.put("requestMethod", "POST");
                case "operation" -> f.binding.put("operation", "read");
                case "parent" -> {
                    var data = new java.util.HashMap<>(record.getData());
                    var proof = new java.util.HashMap<>((Map<String, Object>)data.get(ProcessAuthorization.VERIFIED_CHILD_KEY));
                    proof.put("parentId", "99"); data.put(ProcessAuthorization.VERIFIED_CHILD_KEY, proof); record.setData(data);
                }
            }
            assertThrows(invalid, ProcessAuthorizationDeniedException.class, () -> execute(record));
        }
    }

    @Test public void actualRootVolumeMapAndPoolExplicitDenialsAlwaysWin() {
        for (var target : List.of(new String[]{"container", "1i3"}, new String[]{"volume", "1v6"},
                new String[]{"volumeStoragePoolMap", "1vspm12"}, new String[]{"storagePool", "1sp9"})) {
            setup(); var record = queued(); retired();
            f.instance.setState("purged"); f.volume.setState("purged");
            policy(List.of(new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY,
                    ApiKeyPolicy.Scope.resource(target[0], target[1]), Set.of("delete"))));
            assertEquals(target[0], "KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class, () -> execute(record)).getCode());
        }
    }

    @Test public void delayedCleanupRechecksCredentialRevisionExpiryRoleSchemaAndRootGrant() {
        for (String invalid : List.of("revoked", "revision", "expired", "role", "schema", "root-grant")) {
            setup(); var record = queued(); retired(); String expected = "OwnerPermissionDenied";
            f.instance.setState("purged"); f.volume.setState("purged");
            switch (invalid) {
                case "revoked" -> { f.key.setState("inactive"); expected = "ApiKeyRevoked"; }
                case "revision" -> { f.binding.put("policyRevision", 0L); expected = "ApiKeyPolicyChanged"; }
                case "expired" -> { f.hook.clock = Clock.fixed(f.now.plusSeconds(3600), ZoneOffset.UTC); expected = "ApiKeyExpired"; }
                case "role" -> when(f.hook.authenticator.currentAuthorization(eq(1L), eq(5L), any()))
                        .thenReturn(new ApiAuthenticator.CurrentAuthorization(mock(Policy.class), f.schemas));
                case "schema" -> when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
                case "root-grant" -> {
                    f.key.setData(new ApiKeyPolicyCodec().store(f.key, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM,
                            ApiKeyPolicy.Effect.DENY, f.now.plusSeconds(3600), List.of(), ApiKeyPolicy.Purpose.GENERAL), 1)); expected = "KeyScopeDenied";
                }
            }
            assertEquals(invalid, expected, assertThrows(ProcessAuthorizationDeniedException.class, () -> execute(record)).getCode());
        }
    }
}
