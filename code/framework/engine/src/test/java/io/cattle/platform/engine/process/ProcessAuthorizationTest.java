package io.cattle.platform.engine.process;

import static org.junit.Assert.*;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ProcessAuthorizationTest {
    @Test public void legacyVerifiedFramesWithoutProcessNamesKeepTheirExistingChildProof() {
        Map<String, Object> binding = Map.of("keyId", "1c1", "requestId", "legacy-request", "policyRevision", 1L);
        EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(binding);
        try {
            for (String[] edge : List.of(new String[]{"instance", "3", "mount", "7", "mount.deactivate"},
                    new String[]{"volume", "6", "volumeStoragePoolMap", "12", "volumestoragepoolmap.remove"},
                    new String[]{"instance", "3", "agent", "20", "agent.create"})) {
                var frame = engine.pushVerifiedExecution(edge[0], edge[1], binding);
                try {
                    LaunchConfiguration child = new LaunchConfiguration(edge[4], edge[2], edge[3], 5L, 0, Map.of());
                    ProcessAuthorization.prepare(child, List.of());
                    ProcessRecord queued = new ProcessRecord(child, 100L, "server");
                    var proof = ProcessAuthorization.verifiedChild(queued);
                    assertNotNull(proof); assertEquals(edge[0], proof.parentType()); assertEquals(edge[1], proof.parentId());
                    assertEquals(binding, ProcessAuthorization.metadata(queued));
                    assertFalse(((Map<?, ?>) queued.getData().get(ProcessAuthorization.VERIFIED_CHILD_KEY)).containsKey("parentProcess"));
                } finally { engine.popVerifiedExecution(frame); }
            }
        } finally { engine.popAuthorization(); }
        assertTrue(engine.peekAuthorization().isEmpty()); assertNull(engine.currentVerifiedExecution());
    }

    @Test public void nativeAgentSetupProofRecordsAncestryWithoutDomainAuthorization() {
        Map<String, Object> binding = Map.of("keyId", "1c1", "targetType", "service", "operation", "create");
        EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(binding);
        try {
            for (String[] edge : List.of(new String[]{"instance", "3", "agent", "20"},
                    new String[]{"agent", "20", "account", "30"}, new String[]{"account", "30", "credential", "40"})) {
                var frame = engine.pushVerifiedExecution(edge[0], edge[1], edge[0] + ".create", binding);
                ProcessRecord queued;
                try {
                    LaunchConfiguration child = new LaunchConfiguration(edge[2] + ".create", edge[2], edge[3], null, 0, Map.of());
                    ProcessAuthorization.prepare(child, List.of());
                    assertNotNull(ProcessAuthorization.verifiedChild(new ProcessRecord(child, null, "server")));
                    queued = new ProcessRecord(child, 100L, "server");
                    assertEquals(edge[0] + ".create", ((Map<?, ?>) queued.getData().get(ProcessAuthorization.VERIFIED_CHILD_KEY)).get("parentProcess"));
                } finally { engine.popVerifiedExecution(frame); }
                assertNotNull(ProcessAuthorization.verifiedChild(queued));
                assertNull(ProcessAuthorization.verifiedChild(new ProcessRecord(queued, null, "server")));
                Map<String, Object> forged = new HashMap<>((Map<String, Object>) queued.getData().get(ProcessAuthorization.VERIFIED_CHILD_KEY));
                forged.put("parentProcess", edge[0] + ".update"); queued.getData().put(ProcessAuthorization.VERIFIED_CHILD_KEY, forged);
                assertNull(ProcessAuthorization.verifiedChild(queued));
            }
        } finally { engine.popAuthorization(); }
    }

    @Test public void nativeAgentCleanupProofRequiresTheExactNativeParentProcess() {
        Map<String, Object> binding = Map.of("keyId", "1c1", "targetType", "service", "operation", "delete");
        EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(binding);
        try {
            for (String[] edge : List.of(new String[]{"instance", "3", "instance.remove", "agent", "20", "agent.deactivate"},
                    new String[]{"instance", "3", "instance.purge", "agent", "20", "agent.remove"},
                    new String[]{"agent", "20", "agent.deactivate", "agent", "20", "agent.remove"},
                    new String[]{"agent", "20", "agent.remove", "account", "30", "account.deactivate"},
                    new String[]{"agent", "20", "agent.remove", "account", "30", "account.remove"},
                    new String[]{"account", "30", "account.deactivate", "account", "30", "account.remove"},
                    new String[]{"account", "30", "account.remove", "credential", "40", "credential.deactivate"},
                    new String[]{"account", "30", "account.purge", "credential", "40", "credential.remove"},
                    new String[]{"credential", "40", "credential.deactivate", "credential", "40", "credential.remove"})) {
                var frame = engine.pushVerifiedExecution(edge[0], edge[1], edge[2], binding);
                ProcessRecord queued;
                try {
                    LaunchConfiguration child = new LaunchConfiguration(edge[5], edge[3], edge[4], null, 0, Map.of());
                    ProcessAuthorization.prepare(child, List.of());
                    assertNotNull(ProcessAuthorization.verifiedChild(new ProcessRecord(child, null, "server")));
                    queued = new ProcessRecord(child, 100L, "server");
                } finally { engine.popVerifiedExecution(frame); }
                assertNotNull(ProcessAuthorization.verifiedChild(queued));
                assertNull(ProcessAuthorization.verifiedChild(new ProcessRecord(queued, null, "server")));
                Map<String, Object> changed = new HashMap<>((Map<String, Object>) queued.getData().get(ProcessAuthorization.VERIFIED_CHILD_KEY));
                changed.put("parentProcess", edge[0] + ".update"); queued.getData().put(ProcessAuthorization.VERIFIED_CHILD_KEY, changed);
                assertNull(ProcessAuthorization.verifiedChild(queued));
            }
        } finally { engine.popAuthorization(); }
    }

    @Test
    public void captureIsImmutableWhitelistedAndPayloadCannotForgeProvenance() {
        LaunchConfiguration config = config(Map.of(ProcessAuthorization.DATA_KEY, Map.of("keyId", "forged")));
        ProcessAuthorization.prepare(config, List.of(new ProcessAuthorizationHook() {
            @Override public Map<String, Object> capture(LaunchConfiguration launch) {
                return Map.of("keyId", "1a1", "requestId", "request1", "Authorization", "Bearer-secret", "payload", "secret",
                        "requestMethod", "POST", "requestAction", "upgrade", "requestLink", "logs", "requestCollection", true);
            }
        }));
        Map<String, Object> metadata = ProcessAuthorization.metadata(config);
        assertEquals("1a1", metadata.get("keyId"));
        assertEquals(6, metadata.size());
        assertEquals("POST", metadata.get("requestMethod"));
        assertEquals("upgrade", metadata.get("requestAction"));
        assertEquals("logs", metadata.get("requestLink"));
        assertEquals(Boolean.TRUE, metadata.get("requestCollection"));
        assertFalse(ProcessAuthorization.sanitize(Map.of("requestCollection", "true")).containsKey("requestCollection"));
        assertFalse(config.getData().toString().contains("secret"));
        try {
            metadata.put("keyId", "forged");
            fail("Metadata must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void childAlwaysInheritsParentIdentityAndQueuedSnapshotSurvivesContextRemoval() {
        EngineContext engine = EngineContext.getEngineContext();
        engine.pushAuthorization(Map.of("keyId", "parent", "requestId", "request1"));
        LaunchConfiguration config = config(Map.of(ProcessAuthorization.DATA_KEY, Map.of("keyId", "payload-forged")));
        try {
            ProcessAuthorization.prepare(config, List.of(new ProcessAuthorizationHook() {
                @Override public Map<String, Object> capture(LaunchConfiguration launch) {
                    return Map.of("keyId", "different-context");
                }
            }));
        } finally {
            engine.popAuthorization();
        }
        LaunchConfiguration queued = new LaunchConfiguration(config);
        ProcessAuthorization.prepare(queued, List.of());
        assertEquals("parent", ProcessAuthorization.metadata(queued).get("keyId"));
        assertEquals("request1", ProcessAuthorization.metadata(queued).get("requestId"));
        assertTrue(engine.peekAuthorization().isEmpty());
    }

    @Test
    public void requestsWithoutKeyDoNotGainKeyMetadataFromPayload() {
        LaunchConfiguration config = config(Map.of("ordinary", "value", ProcessAuthorization.DATA_KEY, Map.of("keyId", "forged")));
        ProcessAuthorization.prepare(config, List.of());
        assertTrue(ProcessAuthorization.metadata(config).isEmpty());
        assertEquals("value", config.getData().get("ordinary"));
    }

    @Test public void queuedChildProofIsCapturedOnlyFromVerifiedParentAndSurvivesWithoutAFrame() {
        EngineContext engine = EngineContext.getEngineContext();
        Map<String, Object> binding = Map.of("keyId", "1c1", "requestId", "same-request", "policyRevision", 1L);
        engine.pushAuthorization(binding);
        var frame = engine.pushVerifiedExecution("volume", "6", binding);
        LaunchConfiguration child = new LaunchConfiguration("volumestoragepoolmap.remove", "volumeStoragePoolMap", "12", 5L, 0,
                Map.of(ProcessAuthorization.VERIFIED_CHILD_KEY, Map.of("parentId", "99")));
        try { ProcessAuthorization.prepare(child, List.of()); }
        finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
        assertNull(ProcessAuthorization.verifiedChild(child)); // An arbitrary launch payload is not a persisted edge.
        ProcessRecord queued = new ProcessRecord(child, 99L, "server");
        var proof = ProcessAuthorization.verifiedChild(queued);
        assertEquals("volume", proof.parentType()); assertEquals("6", proof.parentId());
        assertEquals(binding, proof.rootAuthorization());
        assertThrows(UnsupportedOperationException.class, () -> proof.rootAuthorization().put("keyId", "forged"));
        assertNull(engine.currentVerifiedExecution());
        for (String changed : List.of("child", "type", "process", "root")) {
            ProcessRecord copy = new ProcessRecord(child, 100L, "server");
            switch (changed) {
                case "child" -> copy.setResourceId("13");
                case "type" -> copy.setResourceType("instanceHostMap");
                case "process" -> copy.setProcessName("volumestoragepoolmap.purge");
                case "root" -> { copy.setData(new HashMap<>(copy.getData())); copy.getData().put(ProcessAuthorization.DATA_KEY, Map.of("keyId", "1c2")); }
            }
            assertNull(changed, ProcessAuthorization.verifiedChild(copy));
        }
    }

    @Test public void serviceRetirementProofRequiresTheExactVerifiedRootAndClosedStopRemoveEdge() {
        Map<String, Object> binding = Map.of("keyId", "1c1", "policyRevision", 1L, "targetType", "service", "targetId", "2",
                "operation", "update", "requestMethod", "PUT", "requestCollection", false, "preview", false);
        EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(binding);
        try {
            for (String process : List.of("instance.stop", "instance.remove", "instance.start", "instance.purge")) {
                var parent = engine.pushVerifiedExecution("service", "2", binding);
                ProcessRecord queued;
                try {
                    LaunchConfiguration child = new LaunchConfiguration(process, "instance", "3", 5L, 0, Map.of());
                    ProcessAuthorization.prepare(child, List.of());
                    ProcessRecord initial = new ProcessRecord(child, null, "server");
                    boolean retirement = process.equals("instance.stop") || process.equals("instance.remove");
                    assertEquals(retirement, ProcessAuthorization.verifiedChild(initial) != null);
                    queued = new ProcessRecord(child, 99L, "server");
                } finally { engine.popVerifiedExecution(parent); }
                boolean retirement = process.equals("instance.stop") || process.equals("instance.remove");
                assertEquals(retirement, ProcessAuthorization.verifiedChild(queued) != null);
                if (retirement) {
                    ProcessRecord unsaved = new ProcessRecord(queued, null, "server");
                    assertNull(ProcessAuthorization.verifiedChild(unsaved));
                }
            }
            var wrong = engine.pushVerifiedExecution("service", "9", binding);
            try {
                LaunchConfiguration child = new LaunchConfiguration("instance.stop", "instance", "3", 5L, 0, Map.of());
                ProcessAuthorization.prepare(child, List.of());
                assertNull(ProcessAuthorization.verifiedChild(new ProcessRecord(child, 99L, "server")));
            } finally { engine.popVerifiedExecution(wrong); }
            var mount = engine.pushVerifiedExecution("mount", "7", binding);
            LaunchConfiguration volume = new LaunchConfiguration("volume.deactivate", "volume", "6", 5L, 0, Map.of());
            try { ProcessAuthorization.prepare(volume, List.of()); }
            finally { engine.popVerifiedExecution(mount); }
            assertEquals("mount", ProcessAuthorization.verifiedChild(new ProcessRecord(volume, 100L, "server")).parentType());
        } finally { engine.popAuthorization(); }
    }

    @Test public void unverifiedPayloadAndWrongParentBindingCannotMintQueuedAuthority() {
        Map<String, Object> forged = Map.of("parentType", "volume", "parentId", "6", "childType", "stack", "childId", "12",
                "processName", "stack.update", "rootAuthorization", Map.of("keyId", "1c1"));
        LaunchConfiguration child = config(Map.of(ProcessAuthorization.VERIFIED_CHILD_KEY, forged));
        ProcessAuthorization.prepare(child, List.of());
        assertFalse(child.getData().containsKey(ProcessAuthorization.VERIFIED_CHILD_KEY));
        assertNull(ProcessAuthorization.verifiedChild(new ProcessRecord(child, 99L, "server")));
        EngineContext engine = EngineContext.getEngineContext();
        engine.pushAuthorization(Map.of("keyId", "1c1"));
        for (String parent : List.of("image", "service", "volume")) {
            var frame = engine.pushVerifiedExecution(parent, "6", Map.of("keyId", "different"));
            try {
                LaunchConfiguration attempted = config(Map.of(ProcessAuthorization.VERIFIED_CHILD_KEY, forged));
                ProcessAuthorization.prepare(attempted, List.of());
                assertFalse(attempted.getData().containsKey(ProcessAuthorization.VERIFIED_CHILD_KEY));
            } finally { engine.popVerifiedExecution(frame); }
        }
        engine.popAuthorization();
    }

    @Test public void relationshipPinsRequireTheActualParentAndCannotChangeOnRetry() {
        EngineContext engine = EngineContext.getEngineContext();
        Map<String, Object> binding = Map.of("keyId", "1c1", "policyRevision", 1L, "requestId", "request1");
        Map<String, String> references = Map.of("childUuid", "map-uuid", "volumeUuid", "volume-uuid",
                "storagePoolId", "9", "storagePoolUuid", "pool-uuid");
        engine.pushAuthorization(binding);
        var frame = engine.pushVerifiedExecution("volume", "6", binding);
        ProcessRecord queued;
        try {
            LaunchConfiguration launch = new LaunchConfiguration("volumestoragepoolmap.remove", "volumeStoragePoolMap", "12", 5L, 0, Map.of());
            ProcessAuthorization.prepare(launch, List.of()); queued = new ProcessRecord(launch, 99L, "server");
            ProcessAuthorization.bindVerifiedChildReferences(queued, references);
            assertEquals(references, ProcessAuthorization.verifiedChild(queued).references());
            assertThrows(UnsupportedOperationException.class,
                    () -> ProcessAuthorization.verifiedChild(queued).references().put("childUuid", "forged"));
            assertThrows(IllegalStateException.class,
                    () -> ProcessAuthorization.bindVerifiedChildReferences(queued, Map.of("childUuid", "changed")));
        } finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
        assertNull(engine.currentVerifiedExecution());
        assertEquals(references, ProcessAuthorization.verifiedChild(queued).references());
        assertThrows(IllegalStateException.class, () -> ProcessAuthorization.bindVerifiedChildReferences(queued, references));
        for (String invalid : List.of("parent", "binding")) {
            var wrong = engine.pushVerifiedExecution("volume", invalid.equals("parent") ? "7" : "6",
                    invalid.equals("binding") ? Map.of("keyId", "other") : binding);
            try { assertThrows(IllegalStateException.class, () -> ProcessAuthorization.bindVerifiedChildReferences(queued, references)); }
            finally { engine.popVerifiedExecution(wrong); }
        }
    }

    @Test public void onlyExactUpgradeMountUnmountCanCaptureNewSchedulingEdge() {
        EngineContext engine = EngineContext.getEngineContext();
        for (String scenario : List.of("valid", "finish", "rollback", "remove", "other-parent", "other-operation", "other-action")) {
            var binding = new HashMap<String, Object>(Map.of("keyId", "1c1", "targetType", "service", "targetId", "2",
                    "operation", "upgrade", "requestAction", "upgrade"));
            if (scenario.equals("other-operation")) binding.put("operation", "remove");
            if (scenario.equals("finish")) binding.put("requestAction", "finishupgrade");
            if (scenario.equals("rollback")) binding.put("requestAction", "rollback");
            if (scenario.equals("other-action")) binding.put("requestAction", "cancelupgrade");
            engine.pushAuthorization(binding);
            var frame = engine.pushVerifiedExecution(scenario.equals("other-parent") ? "nic" : "mount", "7", binding);
            try {
                var launch = new LaunchConfiguration(scenario.equals("remove") ? "volume.remove" : "volume.deactivate", "volume", "6", 5L, 0, Map.of());
                ProcessAuthorization.prepare(launch, List.of());
                var proof = ProcessAuthorization.verifiedChild(new ProcessRecord(launch, 999L, "server"));
                assertEquals(scenario, List.of("valid", "finish", "rollback").contains(scenario), proof != null);
            } finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
        }
    }

    private static LaunchConfiguration config(Map<String, Object> data) {
        return new LaunchConfiguration("stack.update", "stack", "12", 1L, 0, new HashMap<>(data));
    }
}
