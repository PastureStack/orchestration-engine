package io.cattle.platform.engine.process;

import static org.junit.Assert.*;
import io.cattle.platform.engine.context.EngineContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ProcessAuthorizationTest {
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

    private static LaunchConfiguration config(Map<String, Object> data) {
        return new LaunchConfiguration("stack.update", "stack", "12", 1L, 0, new HashMap<>(data));
    }
}
