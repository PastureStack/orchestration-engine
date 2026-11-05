package io.cattle.platform.iaas.api.auth.ai.view;

import io.cattle.platform.engine.manager.ProcessManager;
import io.cattle.platform.engine.process.ExitReason;
import io.cattle.platform.engine.process.ProcessDefinition;
import io.cattle.platform.engine.process.ProcessPhase;
import io.cattle.platform.util.type.Named;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projection of already-authorized process fields; never an exception/log export. */
public class AiProcessDiagnostics {
    static final int MAX_EXECUTIONS = 50;
    static final int MAX_HANDLERS = 100;
    static final int MAX_TOTAL_ENTRIES = 256;
    static final int MAX_SAFE_UTF8_BYTES = 32768;
    @Inject ProcessManager processes;

    public Map<String, Object> project(String id, Map<String, Object> fields) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("id", id); safe.put("type", "processInstance");
        ProcessDefinition definition = definition(fields.get("processName"));
        // Names originate in the server registry, not persisted labels or exception text.
        safe.put("name", definition == null ? "unknown" : definition.getName());
        if (definition != null) safe.put("processName", definition.getName());
        enumeration(safe, fields, "phase", ProcessPhase.class);
        enumeration(safe, fields, "exitReason", ExitReason.class);
        for (String field : List.of("startTime", "endTime", "runAfter", "created")) time(safe, fields, field);
        if (fields.get("executionCount") instanceof Number number && number.longValue() >= 0
                && number.doubleValue() == number.longValue() && number.longValue() <= 9007199254740991L)
            safe.put("executionCount", number.longValue());
        return safe;
    }

    public Map<String, Object> unknown() {
        return Map.of("diagnosticStatus", "unknown", "diagnosticCompleteness", "unknown");
    }

    public Map<String, Object> sourceLimit() { return truncated("source_limit"); }

    private Map<String, Object> truncated(String reason) {
        return Map.of("diagnosticStatus", "unknown", "diagnosticCompleteness", "unknown",
                "diagnosticTruncated", true, "diagnosticTruncationReason", reason);
    }

    /** Root-level executions only: child process trees and all free-form text stay private. */
    public Map<String, Object> diagnose(Map<String, Object> parent, List<Map<String, Object>> visibleLogs, boolean truncated) {
        ProcessDefinition definition = definition(parent.get("processName"));
        if (definition == null || visibleLogs.isEmpty()) return unknown();
        if (truncated || visibleLogs.size() > MAX_EXECUTIONS) return truncated("source_limit");
        Map<String, Object> result = new LinkedHashMap<>(unknown());
        Budget budget = new Budget();
        boolean hasException = false;
        int inspected = 0;
        for (Map<String, Object> log : visibleLogs) {
            if (!budget.entry()) return truncated(budget.reason);
            if (!(log.get("executions") instanceof List<?> executions) || executions.isEmpty()) return unknown();
            if (executions.size() > MAX_EXECUTIONS) return truncated("source_limit");
            for (Object value : executions) {
                if (++inspected > MAX_EXECUTIONS) return truncated("source_limit");
                if (!budget.entry()) return truncated(budget.reason);
                if (!(value instanceof Map<?, ?> execution)
                        || !Objects.equals(definition.getName(), execution.get("processName"))) return unknown();
                if (!budget.text(definition.getName())) return truncated(budget.reason);
                if (execution.get("exception") instanceof Map<?, ?>) hasException = true;
                if (!(execution.get("processHandlerExecutions") instanceof List<?> handlers)) return unknown();
                if (handlers.size() > MAX_HANDLERS) return truncated("source_limit");
                for (Object item : handlers) {
                    if (!budget.entry()) return truncated(budget.reason);
                    if (!(item instanceof Map<?, ?> handler)) return unknown();
                    if (handler.get("exception") instanceof Map<?, ?>) {
                        hasException = true;
                        String registered = handlerName(definition, handler.get("name"));
                        if (registered != null && !budget.text(registered)) return truncated(budget.reason);
                        if (registered != null && !result.containsKey("failedHandler")) result.put("failedHandler", registered);
                    }
                }
            }
        }
        result.put("diagnosticStatus", "available"); result.put("hasException", hasException);
        // Even an empty authorized page cannot establish that no other failure exists.
        return result;
    }

    /** One budget across all logs, roots and handlers, not a separate allowance per branch. */
    static final class Budget {
        int entries;
        // Reserve the entire fixed flat response (including its single <=128-character handler).
        // Each inspected entry also reserves more than its closed JSON structural overhead.
        // No raw log/exception is serialized or measured to calculate this budget.
        int utf8Bytes = 1024;
        String reason;
        boolean entry() {
            if (++entries > MAX_TOTAL_ENTRIES) { reason = "entry_budget"; return false; }
            return charge(64);
        }
        boolean text(String registered) { return charge(registered.getBytes(StandardCharsets.UTF_8).length); }
        private boolean charge(int bytes) {
            if (bytes > MAX_SAFE_UTF8_BYTES - utf8Bytes) { reason = "utf8_budget"; return false; }
            utf8Bytes += bytes;
            return true;
        }
    }

    ProcessDefinition definition(Object value) {
        if (!(value instanceof String name) || name.length() > 128 || !name.matches("[A-Za-z][A-Za-z0-9_.-]*")) return null;
        ProcessDefinition definition = processes.getProcessDefinition(name);
        return definition != null && name.equals(definition.getName()) ? definition : null;
    }

    private static String handlerName(ProcessDefinition definition, Object value) {
        if (!(value instanceof String name) || name.length() > 128 || !name.matches("[A-Za-z][A-Za-z0-9_.-]*")) return null;
        for (List<? extends Named> handlers : java.util.Arrays.asList(definition.getPreProcessListeners(), definition.getProcessHandlers(),
                definition.getPostProcessListeners())) {
            if (handlers != null) for (Named handler : handlers) if (name.equals(handler.getName())) return handler.getName();
        }
        return null;
    }

    private static <E extends Enum<E>> void enumeration(Map<String, Object> output, Map<String, Object> fields,
            String field, Class<E> type) {
        if (!(fields.get(field) instanceof String value)) return;
        for (E allowed : type.getEnumConstants()) if (allowed.name().equals(value)) output.put(field, allowed.name());
    }

    private static void time(Map<String, Object> output, Map<String, Object> fields, String field) {
        if (!fields.containsKey(field)) return;
        Object value = fields.get(field);
        if (value == null) output.put(field, null);
        else if (value instanceof java.util.Date date) output.put(field, date.toInstant().toString());
        else if (value instanceof String text && text.length() <= 32) {
            try { output.put(field, Instant.parse(text).toString()); }
            catch (DateTimeParseException invalid) { /* Unknown timestamps are not guessed. */ }
        }
    }
}
