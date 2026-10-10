package io.cattle.platform.engine.process;

import java.util.Collections;
import java.util.Map;

/** Optional metadata bridge; the authoritative authorization lives in its implementation. */
public interface ProcessAuthorizationHook {
    default boolean authorizesExecution() { return false; }
    default boolean recordsCompletion() { return false; }
    default Map<String, Object> capture(LaunchConfiguration config) { return Collections.emptyMap(); }
    default void beforeExecution(LaunchConfiguration config, Map<String, Object> metadata) { }
    default void afterExecution(LaunchConfiguration config, Map<String, Object> metadata, ExitReason reason) { }
}
