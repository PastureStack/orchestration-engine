package io.cattle.platform.engine.process;

import static org.junit.Assert.*;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import java.util.*;
import org.junit.Test;

public class ProcessAuthorizationProjectUpdateTest {
    @Test public void capturedNativeProjectUpdateProofCannotBeForgedRetargetedOrGrowFields() {
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of("keyId", "1c7", "principalAccountId", 42L, "accountId", 77L,
                "targetType", "project", "targetId", "77", "requestId", UUID.randomUUID().toString(), "requestMethod", "PUT",
                "requestCollection", false, "operation", "update", "preview", false));
        var sourceFields = new ArrayList<>(List.of("name", "virtualMachine"));
        metadata.put("projectUpdateUuid", "native-uuid-77"); metadata.put("projectUpdateFields", sourceFields);
        Map<String, Object> payload = new HashMap<>(Map.of("name", "after", "virtualMachine", true, "old", Map.of("name", "before", "virtualMachine", false)));
        payload.put(ProcessAuthorization.CAPTURED_EXECUTION_KEY, Map.of("version", 1, "authorization", metadata));
        payload.put(ProcessAuthorization.DATA_KEY, metadata);
        LaunchConfiguration untrusted = new LaunchConfiguration("account.update", "account", "77", null, 0, payload);
        ProcessAuthorization.prepare(untrusted, List.of()); assertTrue(ProcessAuthorization.metadata(untrusted).isEmpty());
        assertFalse(ProcessAuthorization.executionBound(new ProcessRecord(untrusted, 1L, "unit")));
        LaunchConfiguration config = new LaunchConfiguration("account.update", "account", "77", null, 0, payload);
        ProcessAuthorization.prepare(config, List.of(new ProcessAuthorizationHook() {
            @Override public Map<String, Object> capture(LaunchConfiguration ignored) { return metadata; }
        }));
        ProcessRecord actual = new ProcessRecord(config, 2L, "unit"); assertTrue(ProcessAuthorization.executionBound(actual));
        sourceFields.add("labels");
        assertEquals(List.of("name", "virtualMachine"), ProcessAuthorization.metadata(actual).get("projectUpdateFields"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) ProcessAuthorization.metadata(actual).get("projectUpdateFields")).add("labels"));
        for (String mutation : List.of("target", "type", "method", "fields", "uuid", "proof", "metadataFields")) {
            var copy = new ProcessRecord(config, 3L, "unit"); copy.setData(new HashMap<>(copy.getData()));
            switch (mutation) {
            case "target" -> copy.setResourceId("88");
            case "type" -> copy.setResourceType("instance");
            case "method" -> copy.setProcessName("account.remove");
            case "fields" -> copy.getData().put("labels", Map.of("unexpected", "field"));
            case "proof" -> copy.getData().remove(ProcessAuthorization.CAPTURED_EXECUTION_KEY);
            default -> {
                var altered = new HashMap<>(ProcessAuthorization.metadata(copy));
                if (mutation.equals("uuid")) altered.put("projectUpdateUuid", "different"); else altered.remove("projectUpdateFields");
                copy.getData().put(ProcessAuthorization.DATA_KEY, altered);
            }
            }
            assertFalse(mutation, ProcessAuthorization.executionBound(copy));
            assertTrue(mutation, ProcessAuthorization.projectUpdateCaptured(copy, ProcessAuthorization.metadata(copy)));
        }
        assertFalse(ProcessAuthorization.sanitize(Map.of("projectUpdateFields", List.of("name", "name"))).containsKey("projectUpdateFields"));
        assertFalse(ProcessAuthorization.sanitize(Map.of("projectUpdateFields", List.of("name", "a\nsecret"))).containsKey("projectUpdateFields"));
    }
}
