package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiExternalServiceProofTest {
    @Test public void actualNativeCaptureSurvivesQueuedRetryWithNumericPersistentParent() {
        for (String process : List.of("serviceexposemap.create", "serviceexposemap.remove")) {
            Map<String, Object> binding = binding();
            ProcessRecord record = capture(binding, "service", "2", process, "serviceExposeMap", "3");
            var proof = ProcessAuthorization.verifiedChild(record);
            assertNotNull(proof); assertEquals("2", proof.parentId()); assertEquals(binding, proof.rootAuthorization());
        }
    }

    @Test public void foreignParentsOperationsAndNonEndpointChildrenCannotAcquireProof() {
        for (String mutation : List.of("parent", "kind", "method", "operation", "collection", "link", "action", "instance", "volume")) {
            var binding = binding(); String child = "serviceExposeMap", process = "serviceexposemap.create";
            switch (mutation) {
            case "kind" -> binding.put("targetType", "service");
            case "method" -> binding.put("requestMethod", "GET");
            case "operation" -> binding.put("operation", "read");
            case "collection" -> binding.put("requestCollection", true);
            case "link" -> binding.put("requestLink", "serviceExposeMaps");
            case "action" -> binding.put("requestAction", "confirm");
            case "instance" -> { child = "instance"; process = "instance.remove"; }
            case "volume" -> { child = "volume"; process = "volume.remove"; }
            default -> { }
            }
            assertNull(mutation, ProcessAuthorization.verifiedChild(capture(binding, "service", mutation.equals("parent") ? "9" : "2", process, child, "3")));
        }
    }

    @Test public void deleteMayOnlyRetireItsOwnEndpointMapAndCannotCreateOne() {
        var binding = binding(); binding.put("requestMethod", "DELETE"); binding.put("operation", "delete"); binding.remove("requestAction");
        assertNotNull(ProcessAuthorization.verifiedChild(capture(binding, "service", "2", "serviceexposemap.remove", "serviceExposeMap", "3")));
        assertNull(ProcessAuthorization.verifiedChild(capture(binding, "service", "2", "serviceexposemap.create", "serviceExposeMap", "3")));
    }

    @Test public void publicDataIsStrippedAndAValidProofCannotBeMovedToAnotherChildOrOperation() {
        var binding = binding(); ProcessRecord record = capture(binding, "service", "2", "serviceexposemap.create", "serviceExposeMap", "3");
        assertNotNull(ProcessAuthorization.verifiedChild(record));
        var wrongChild = new LaunchConfiguration("serviceexposemap.create", "serviceExposeMap", "4", 5L, 0, record.getData());
        assertNull(ProcessAuthorization.verifiedChild(new ProcessRecord(wrongChild, 100L, "server")));
        var publicLaunch = new LaunchConfiguration("serviceexposemap.create", "serviceExposeMap", "3", 5L, 0, record.getData());
        ProcessAuthorization.prepare(publicLaunch, List.of());
        assertFalse(publicLaunch.getData().containsKey(ProcessAuthorization.VERIFIED_CHILD_KEY));
        assertTrue(ProcessAuthorization.metadata(publicLaunch).isEmpty());
    }

    private static ProcessRecord capture(Map<String, Object> binding, String parentType, String parentId,
            String process, String childType, String childId) {
        var engine = EngineContext.getEngineContext(); engine.pushAuthorization(binding);
        var parent = engine.pushVerifiedExecution(parentType, parentId, binding);
        var launch = new LaunchConfiguration(process, childType, childId, 5L, 0, Map.of());
        try { ProcessAuthorization.prepare(launch, List.of()); }
        finally { engine.popVerifiedExecution(parent); engine.popAuthorization(); }
        return new ProcessRecord(launch, 99L, "server");
    }

    private static Map<String, Object> binding() {
        var result = new LinkedHashMap<String, Object>();
        result.put("keyId", "1c1"); result.put("policyRevision", 1L); result.put("principalAccountId", 10L); result.put("accountId", 5L);
        result.put("requestId", "aichange-11111111-1111-1111-1111-111111111111"); result.put("operation", "update");
        result.put("targetType", "externalService"); result.put("targetId", "2"); result.put("preview", false);
        result.put("requestMethod", "POST"); result.put("requestAction", "activate"); result.put("requestCollection", false); return result;
    }
}
