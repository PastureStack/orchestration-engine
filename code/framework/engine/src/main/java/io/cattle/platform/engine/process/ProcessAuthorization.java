package io.cattle.platform.engine.process;

import io.cattle.platform.engine.context.EngineContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reserved, immutable metadata persisted alongside the existing process data. */
public final class ProcessAuthorization {
    public static final String DATA_KEY = "_apiKeyAudit";
    public static final String COMPLETION_PENDING_KEY = "_apiKeyAuditCompletionPending";
    private static final Set<String> FIELDS = Set.of("keyId", "policyRevision", "principalAccountId", "accountId",
            "requestId", "operation", "targetType", "targetId", "preview", "requestMethod", "requestAction", "requestLink", "requestCollection");

    private ProcessAuthorization() { }

    public static Map<String, Object> sanitize(Map<?, ?> source) {
        Map<String, Object> result = new HashMap<>();
        if (source != null) {
            for (String name : FIELDS) {
                Object value = source.get(name);
                if (name.equals("requestCollection") && !(value instanceof Boolean)) {
                    continue;
                }
                if (value instanceof String text) {
                    if (name.equals("requestMethod") && !Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").contains(text)) {
                        continue;
                    }
                    if ((name.equals("requestAction") || name.equals("requestLink")) && !text.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
                        continue;
                    }
                    if (text.length() <= 512 && text.chars().noneMatch(character -> character < 32 || character == 127)) {
                        result.put(name, text);
                    }
                } else if (value instanceof Number || value instanceof Boolean) {
                    result.put(name, value);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public static void prepare(LaunchConfiguration config, List<ProcessAuthorizationHook> hooks) {
        if (config.isAuthorizationCaptured()) {
            return;
        }
        // Payload fields cannot impersonate captured authorization metadata.
        Map<String, Object> data = new HashMap<>();
        if (config.getData() != null) {
            data.putAll(config.getData());
        }
        data.remove(DATA_KEY);
        data.remove(COMPLETION_PENDING_KEY);
        Map<String, Object> metadata = EngineContext.getEngineContext().peekAuthorization();
        if (metadata.isEmpty()) {
            for (ProcessAuthorizationHook hook : hooks) {
                Map<String, Object> captured = sanitize(hook.capture(config));
                if (!captured.isEmpty()) {
                    if (!metadata.isEmpty() && !metadata.equals(captured)) {
                        throw new IllegalStateException("Conflicting process authorization metadata");
                    }
                    metadata = captured;
                }
            }
        }
        if (!metadata.isEmpty()) {
            data.put(DATA_KEY, sanitize(metadata));
        }
        config.setData(data);
        config.setAuthorizationCaptured(true);
    }

    public static Map<String, Object> metadata(LaunchConfiguration config) {
        Object value = config.getData() == null ? null : config.getData().get(DATA_KEY);
        return value instanceof Map<?, ?> map ? sanitize(map) : Collections.emptyMap();
    }
}
