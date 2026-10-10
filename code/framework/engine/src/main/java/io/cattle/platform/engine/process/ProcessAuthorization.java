package io.cattle.platform.engine.process;

import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** Reserved, immutable metadata persisted alongside the existing process data. */
public final class ProcessAuthorization {
    public static final String DATA_KEY = "_apiKeyAudit";
    public static final String COMPLETION_PENDING_KEY = "_apiKeyAuditCompletionPending";
    public static final String VERIFIED_CHILD_KEY = "_apiKeyVerifiedChild";
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
        data.remove(VERIFIED_CHILD_KEY);
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
            var parent = EngineContext.getEngineContext().currentVerifiedExecution();
            if (parent != null && Set.of("volume", "instance").contains(parent.resourceType())
                    && parent.resourceId() != null && parent.resourceId().matches("[0-9]+")
                    && parent.rootAuthorization().equals(metadata)
                    && config.getResourceType() != null && config.getResourceId() != null && config.getProcessName() != null) {
                data.put(VERIFIED_CHILD_KEY, Map.of("parentType", parent.resourceType(), "parentId", parent.resourceId(),
                        "childType", config.getResourceType(), "childId", config.getResourceId(),
                        "processName", config.getProcessName(), "rootAuthorization", sanitize(metadata)));
            }
        }
        config.setData(data);
        config.setAuthorizationCaptured(true);
    }

    public static Map<String, Object> metadata(LaunchConfiguration config) {
        Object value = config.getData() == null ? null : config.getData().get(DATA_KEY);
        return value instanceof Map<?, ?> map ? sanitize(map) : Collections.emptyMap();
    }

    /** A durable scheduling edge is not a live execution frame. Only framework
     * persisted records can supply it; every consumer must reload the live graph. */
    public static final class VerifiedChild {
        private final String parentType, parentId;
        private final Map<String, Object> rootAuthorization;
        private final Map<String, String> references;
        private VerifiedChild(String parentType, String parentId, Map<String, Object> binding, Map<String, String> references) {
            this.parentType = parentType; this.parentId = parentId; this.rootAuthorization = Map.copyOf(binding);
            this.references = Map.copyOf(references);
        }
        public String parentType() { return parentType; }
        public String parentId() { return parentId; }
        public Map<String, Object> rootAuthorization() { return rootAuthorization; }
        public Map<String, String> references() { return references; }
    }

    public static VerifiedChild verifiedChild(LaunchConfiguration config) {
        if (!(config instanceof ProcessRecord record) || record.getId() == null || config.getData() == null) return null;
        Object value = config.getData().get(VERIFIED_CHILD_KEY);
        if (!(value instanceof Map<?, ?> proof) || (proof.size() != 6 && proof.size() != 7)
                || !(proof.get("parentType") instanceof String parentType) || !Set.of("volume", "instance").contains(parentType)
                || !(proof.get("parentId") instanceof String parentId) || !parentId.matches("[0-9]+")
                || !Objects.equals(proof.get("childType"), config.getResourceType())
                || !Objects.equals(proof.get("childId"), config.getResourceId())
                || !Objects.equals(proof.get("processName"), config.getProcessName())
                || !(proof.get("rootAuthorization") instanceof Map<?, ?> rawBinding)) return null;
        Map<String, Object> binding = sanitize(rawBinding);
        if (binding.isEmpty() || !binding.equals(rawBinding) || !binding.equals(metadata(config))) return null;
        Map<String, String> references = new HashMap<>();
        if (proof.containsKey("references")) {
            if (!(proof.get("references") instanceof Map<?, ?> raw) || raw.isEmpty()) return null;
            for (var entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !key.matches("[A-Za-z][A-Za-z0-9]{0,63}")
                        || !(entry.getValue() instanceof String text) || text.isBlank() || text.length() > 128
                        || text.chars().anyMatch(c -> c < 32 || c == 127)) return null;
                references.put(key, text);
            }
        }
        return new VerifiedChild(parentType, parentId, binding, references);
    }

    /** Pin source-owned relationships during the first verified scheduling call.
     * A detached retry cannot add or replace them. */
    public static void bindVerifiedChildReferences(LaunchConfiguration config, Map<String, String> references) {
        var proof = verifiedChild(config);
        var frame = EngineContext.getEngineContext().currentVerifiedExecution();
        if (proof == null || frame == null || !frame.resourceType().equals(proof.parentType())
                || !frame.resourceId().equals(proof.parentId()) || !frame.rootAuthorization().equals(proof.rootAuthorization()))
            throw new IllegalStateException("Verified scheduling parent is unavailable");
        if (!proof.references().isEmpty() && !proof.references().equals(references))
            throw new IllegalStateException("Verified child references changed");
        Map<String, Object> pinned = new HashMap<>();
        ((Map<?, ?>) config.getData().get(VERIFIED_CHILD_KEY)).forEach((key, value) -> pinned.put((String) key, value));
        pinned.put("references", Map.copyOf(references));
        config.getData().put(VERIFIED_CHILD_KEY, Map.copyOf(pinned));
    }
}
