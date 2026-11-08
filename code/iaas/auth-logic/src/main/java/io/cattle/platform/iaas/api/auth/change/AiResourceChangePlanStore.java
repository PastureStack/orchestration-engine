package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.json.JsonMapper;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** Durable claim and observation, independent from the service-upgrade receipt. */
public class AiResourceChangePlanStore extends AbstractJooqDao {
    static final Table<?> TABLE = DSL.table(DSL.name("ai_resource_change_plan"));
    static final Field<String> ID = DSL.field(DSL.name("id"), String.class);
    static final Field<String> DOCUMENT = DSL.field(DSL.name("document"), String.class);
    static final Field<String> STATE = DSL.field(DSL.name("state"), String.class);
    static final Field<Long> ACTOR = DSL.field(DSL.name("actor_id"), Long.class);
    static final Field<Long> EXPIRY = DSL.field(DSL.name("expires_at"), Long.class);
    static final Field<String> EXECUTION = DSL.field(DSL.name("execution_binding"), String.class);
    static final Field<String> FAILURE = DSL.field(DSL.name("failure_code"), String.class);
    static final String PRIVATE_FAILURE = "_resourceChangeProcessFailure";
    static final String AUTHORIZATION_DENIED = "ResourceChangeAuthorizationDenied";
    static final String PROCESS_FAILED = "ResourceChangeProcessFailed";
    @Inject JsonMapper json;
    @Inject @jakarta.inject.Named("CoreTransactionManager") org.springframework.transaction.PlatformTransactionManager transactions;

    <T> T processCreation(java.util.function.Supplier<T> action) {
        return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> action.get());
    }

    static boolean ordinaryLifecycle(AiResourceChangeDescriptor descriptor) {
        return "service".equals(descriptor.resourceType()) && java.util.Set.of("start", "stop").contains(descriptor.operation());
    }

    static boolean lifecycle(AiResourceChangeDescriptor descriptor) {
        return AiHostLifecycleChange.supports(descriptor) || ordinaryLifecycle(descriptor) || AiServiceUpgradeLifecycleChange.supports(descriptor);
    }

    static boolean sessionLifecycle(Map<String, Object> binding) {
        if (binding == null || !Boolean.TRUE.equals(binding.get("sessionLifecycle")) || binding.containsKey("credentialId")) return false;
        AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require((String) binding.get("resourceType"), (String) binding.get("operation"));
        return lifecycle(descriptor) && "POST".equals(binding.get("method")) && descriptor.action().equals(binding.get("action"));
    }

    boolean sessionLifecycleActive(String id, Map<String, Object> binding) {
        return sessionLifecycle(binding) && create().fetchCount(TABLE, ID.eq(id)
                .and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull()).and(EXECUTION.eq(encode(binding)))) == 1;
    }

    boolean attestSessionProcess(String id, Map<String, Object> before, Map<String, Object> after) {
        if (!sessionLifecycle(before) || before.containsKey("rootProcessId")
                || !(after.get("rootProcessId") instanceof Number process) || process.longValue() <= 0) return false;
        Map<String, Object> expected = new java.util.LinkedHashMap<>(before); expected.put("rootProcessId", process);
        return expected.equals(after) && attest(id, before, after);
    }

    public void insert(Map<String, Object> plan) {
        create().insertInto(TABLE).set(ID, (String) plan.get("id")).set(STATE, "preview")
                .set(DOCUMENT, encode(plan)).set(ACTOR, ((Number) plan.get("actorId")).longValue())
                .set(EXPIRY, java.time.Instant.parse((String) plan.get("expiresAt")).toEpochMilli()).execute();
    }

    public Map<String, Object> load(String id) {
        var row = create().select(DOCUMENT, FAILURE).from(TABLE).where(ID.eq(id)).fetchOne();
        return row == null ? null : withFailure(decode(row.get(DOCUMENT)), row.get(FAILURE));
    }

    public Map<String, Object> loadForUpdate(String id) {
        create().connection(connection -> {
            if (connection.getAutoCommit()) throw new IllegalStateException("Resource-change write transaction required");
        });
        var row = create().select(DOCUMENT, FAILURE).from(TABLE).where(ID.eq(id)).forUpdate().fetchOne();
        return row == null ? null : withFailure(decode(row.get(DOCUMENT)), row.get(FAILURE));
    }

    public void save(Map<String, Object> plan) {
        String id = (String) plan.get("id");
        // A terminal callback owns only FAILURE, never this document. A late
        // observation must condition its write on the marker it actually saw.
        for (int attempt = 0; attempt < 3; attempt++) {
            String failure = failure(id); withFailure(plan, failure); projectFailure(plan);
            var expected = failure == null ? FAILURE.isNull() : FAILURE.eq(failure);
            if (create().update(TABLE).set(STATE, (String) plan.get("status")).set(DOCUMENT, encode(plan))
                    .where(ID.eq(id).and(expected)).execute() == 1) {
                // Covers a callback committed between the conditional UPDATE
                // and this readback, before the caller constructs its DTO.
                withFailure(plan, failure(id)); projectFailure(plan); return;
            }
        }
        throw new IllegalStateException("Resource-change observation unavailable");
    }

    public boolean claim(Map<String, Object> plan) {
        Map<String, Object> binding = execution(plan);
        return create().update(TABLE).set(STATE, "dispatching").set(DOCUMENT, encode(plan))
                .set(EXECUTION, binding == null ? null : encode(binding))
                .where(ID.eq((String) plan.get("id")).and(STATE.eq("preview")).and(FAILURE.isNull())).execute() == 1;
    }

    public List<Map<String, Object>> pending(long actor, long now) {
        return create().select(DOCUMENT).from(TABLE).where(ACTOR.eq(actor).and(STATE.eq("preview")).and(EXPIRY.gt(now)))
                .orderBy(EXPIRY.asc(), ID.asc()).limit(20).fetch(DOCUMENT).stream().map(this::decode).toList();
    }

    public int pendingCount(long actor) { return create().fetchCount(TABLE, ACTOR.eq(actor).and(STATE.eq("preview"))); }

    public void prune(long now) {
        create().deleteFrom(TABLE).where(STATE.in("preview", "cancelled").and(EXPIRY.le(now))).execute();
    }

    /** Only this small owner binding is read by the process observer. */
    Map<String, Object> execution(String id) {
        String value = create().select(EXECUTION).from(TABLE).where(ID.eq(id)).fetchOne(EXECUTION);
        return value == null ? null : decode(value);
    }

    boolean attest(String id, Map<String, Object> before, Map<String, Object> after) {
        return create().update(TABLE).set(EXECUTION, encode(after))
                .where(ID.eq(id).and(STATE.eq("dispatching")).and(EXECUTION.eq(encode(before)))).execute() == 1;
    }

    boolean loadBalancerMetadataExecutionActive(String id, Map<String, Object> binding) {
        return loadBalancerMetadata(binding) && create().fetchCount(TABLE,
                ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull()).and(EXECUTION.eq(encode(binding)))) == 1;
    }

    boolean loadBalancerScaleExecutionActive(String id, Map<String, Object> binding) {
        return loadBalancerScale(binding) && create().fetchCount(TABLE,
                ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull()).and(EXECUTION.eq(encode(binding)))) == 1;
    }

    boolean attestLoadBalancerScaleProcess(String id, Map<String, Object> before, Map<String, Object> after) {
        if (!loadBalancerScale(before) || before.containsKey("rootProcessId")
                || !(after.get("rootProcessId") instanceof Number process) || process.longValue() <= 0) return false;
        return create().update(TABLE).set(EXECUTION, encode(after))
                .where(ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull())
                        .and(EXECUTION.eq(encode(before)))).execute() == 1;
    }

    static boolean loadBalancerScale(Map<String, Object> binding) {
        return binding != null && "loadBalancerService".equals(binding.get("resourceType"))
                && "scale".equals(binding.get("operation")) && "PUT".equals(binding.get("method"))
                && binding.get("action") == null && "update".equals(binding.get("keyOperation"));
    }

    boolean registryLifecycleExecutionActive(String id, Map<String, Object> binding) {
        return registryLifecycle(binding) && create().fetchCount(TABLE, ID.eq(id)
                .and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull()).and(EXECUTION.eq(encode(binding)))) == 1;
    }

    boolean attestRegistryLifecycleProcess(String id, Map<String, Object> before, Map<String, Object> after) {
        if (!registryLifecycle(before) || before.containsKey("rootProcessId")) return false;
        return create().update(TABLE).set(EXECUTION, encode(after)).where(ID.eq(id)
                .and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull()).and(EXECUTION.eq(encode(before)))).execute() == 1;
    }

    private static boolean registryLifecycle(Map<String, Object> binding) {
        return binding != null && "registry".equals(binding.get("resourceType")) && "POST".equals(binding.get("method"))
                && ("start".equals(binding.get("operation")) && "activate".equals(binding.get("action"))
                    || "stop".equals(binding.get("operation")) && "deactivate".equals(binding.get("action")));
    }

    boolean hostLifecycleExecutionActive(String id, Map<String, Object> binding) {
        return AiHostLifecycleChange.binding(binding) && create().fetchCount(TABLE, ID.eq(id)
                .and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull()).and(EXECUTION.eq(encode(binding)))) == 1;
    }

    boolean attestHostLifecycleProcess(String id, Map<String, Object> before, Map<String, Object> after) {
        if (!AiHostLifecycleChange.binding(before) || before.containsKey("rootProcessId")) return false;
        return create().update(TABLE).set(EXECUTION, encode(after)).where(ID.eq(id)
                .and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull()).and(EXECUTION.eq(encode(before)))).execute() == 1;
    }

    boolean attestLoadBalancerMetadataProcess(String id, Map<String, Object> before, Map<String, Object> after) {
        if (!loadBalancerMetadata(before) || before.containsKey("rootProcessId")) return false;
        return create().update(TABLE).set(EXECUTION, encode(after))
                .where(ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull())
                        .and(EXECUTION.eq(encode(before)))).execute() == 1;
    }

    private static boolean loadBalancerMetadata(Map<String, Object> binding) {
        return binding != null && "loadBalancerService".equals(binding.get("resourceType"))
                && "update_metadata".equals(binding.get("operation")) && "PUT".equals(binding.get("method"));
    }

    boolean loadBalancerCreateExecutionActive(String id, Map<String, Object> binding) {
        return loadBalancerCreate(binding) && create().fetchCount(TABLE,
                ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull()).and(EXECUTION.eq(encode(binding)))) == 1;
    }

    boolean attestLoadBalancerCreateProcess(String id, Map<String, Object> before, Map<String, Object> after) {
        if (!loadBalancerCreate(before) || before.containsKey("rootProcessId")) return false;
        return create().update(TABLE).set(EXECUTION, encode(after))
                .where(ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull())
                        .and(EXECUTION.eq(encode(before)))).execute() == 1;
    }

    private static boolean loadBalancerCreate(Map<String, Object> binding) {
        return binding != null && "loadBalancerService".equals(binding.get("resourceType"))
                && "create".equals(binding.get("operation")) && "POST".equals(binding.get("method"));
    }

    boolean projectExecutionActive(String id, Map<String, Object> binding) {
        return AiProjectMetadataChange.metadataBinding(binding) && create().fetchCount(TABLE,
                ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull()).and(EXECUTION.eq(encode(binding)))) == 1;
    }

    boolean attestProjectProcess(String id, Map<String, Object> before, Map<String, Object> after) {
        if (!AiProjectMetadataChange.metadataBinding(before) || before.containsKey("rootProcessId")) return false;
        return create().update(TABLE).set(EXECUTION, encode(after))
                .where(ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown")).and(FAILURE.isNull())
                        .and(EXECUTION.eq(encode(before)))).execute() == 1;
    }

    boolean recordAuthorizationDenied(String id, Map<String, Object> binding) {
        return create().update(TABLE).set(FAILURE, AUTHORIZATION_DENIED)
                .where(ID.eq(id).and(EXECUTION.eq(encode(binding))).and(STATE.notIn("preview", "cancelled"))
                        .and(FAILURE.isNull().or(FAILURE.eq(PROCESS_FAILED)))).execute() == 1;
    }

    static boolean containerStart(Map<String, Object> binding) {
        return binding != null && "container".equals(binding.get("resourceType"))
                && "start".equals(binding.get("operation")) && "POST".equals(binding.get("method"))
                && "start".equals(binding.get("action"));
    }

    boolean attestContainerStartProcess(String id, Map<String, Object> before, Map<String, Object> after) {
        if (!containerStart(before) || before.containsKey("rootProcessId")
                || !(after.get("rootProcessId") instanceof Number process) || process.longValue() <= 0) return false;
        Map<String, Object> expected = new java.util.LinkedHashMap<>(before);
        expected.put("rootProcessId", process);
        if (!expected.equals(after)) return false;
        return create().update(TABLE).set(EXECUTION, encode(after))
                .where(ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown"))
                        .and(FAILURE.isNull()).and(EXECUTION.eq(encode(before)))).execute() == 1;
    }

    boolean containerStartExecutionActive(String id, Map<String, Object> binding) {
        return containerStart(binding) && create().fetchCount(TABLE,
                ID.eq(id).and(STATE.in("dispatching", "accepted", "running", "unknown"))
                        .and(FAILURE.isNull()).and(EXECUTION.eq(encode(binding)))) == 1;
    }

    boolean recordContainerStartFailure(String id, Map<String, Object> binding, long processId) {
        if (!containerStart(binding) || !(binding.get("rootProcessId") instanceof Number root)
                || root.longValue() != processId || processId <= 0) return false;
        return create().update(TABLE).set(FAILURE, PROCESS_FAILED)
                .where(ID.eq(id).and(EXECUTION.eq(encode(binding))).and(STATE.notIn("preview", "cancelled"))
                        .and(FAILURE.isNull())).execute() == 1;
    }

    String failure(String id) {
        String value = create().select(FAILURE).from(TABLE).where(ID.eq(id)).fetchOne(FAILURE);
        if (value != null && !AUTHORIZATION_DENIED.equals(value) && !PROCESS_FAILED.equals(value))
            throw new IllegalStateException("Resource-change outcome unavailable");
        return value;
    }

    static boolean projectFailure(Map<String, Object> plan) {
        Object failure = plan.get(PRIVATE_FAILURE);
        if (!(AUTHORIZATION_DENIED.equals(failure) || PROCESS_FAILED.equals(failure)) || !(plan.get("id") instanceof String id)
                || !id.matches("aichange-[0-9a-f-]{36}") || !id.equals(plan.get("operationId"))) return false;
        plan.put("status", "failed"); plan.put("verifiedResult", false); plan.put("messageCode", failure); return true;
    }

    private Map<String, Object> withFailure(Map<String, Object> plan, String failure) {
        plan.remove(PRIVATE_FAILURE);
        if (AUTHORIZATION_DENIED.equals(failure) || PROCESS_FAILED.equals(failure)) plan.put(PRIVATE_FAILURE, failure);
        return plan;
    }

    private Map<String, Object> execution(Map<String, Object> plan) {
        AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require((String) plan.get("resourceType"), (String) plan.get("operation"));
        boolean session = !(plan.get("credentialId") instanceof Number);
        if (session && !lifecycle(descriptor)) return null;
        Map<String, Object> binding = new java.util.LinkedHashMap<>();
        for (String field : List.of("id", "actorId", "accountId", "credentialId", "credentialRevision", "credentialKind"))
            if (plan.containsKey(field)) binding.put(field, plan.get(field));
        if (session) {
            if (!(plan.get("snapshot") instanceof Map<?, ?> snapshot) || !(snapshot.get("uuid") instanceof String uuid) || uuid.isBlank())
                throw new IllegalStateException("Resource-change lifecycle identity unavailable");
            binding.put("sessionLifecycle", true); binding.put("rootUuid", uuid);
            if ("service".equals(descriptor.resourceType())) binding.put("rootStackId", snapshot.get("stackId"));
        }
        binding.put("resourceType", descriptor.resourceType()); binding.put("operation", descriptor.operation());
        binding.put("method", descriptor.method()); binding.put("action", descriptor.action()); binding.put("keyOperation", descriptor.keyOperation());
        binding.put("rootId", plan.get("internalResourceId")); binding.put("initial", new java.util.ArrayList<>());
        if (AiHostLifecycleChange.supports(descriptor) && plan.get("snapshot") instanceof Map<?, ?> snapshot) {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            for (var entry : snapshot.entrySet()) if (entry.getKey() instanceof String key) values.put(key, entry.getValue());
            binding.put("rootUuid", snapshot.get("uuid")); binding.put("hostBeforeState", snapshot.get("state"));
            binding.put("hostConfiguration", AiHostLifecycleChange.configuration(values));
        }
        if (AiRegistryLifecycleChange.supports(descriptor) && plan.get("snapshot") instanceof Map<?, ?> snapshot) {
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            for (var entry : snapshot.entrySet()) if (entry.getKey() instanceof String key) values.put(key, entry.getValue());
            binding.put("rootUuid", snapshot.get("uuid"));
            binding.put("registryConfiguration", AiRegistryLifecycleChange.configuration(values));
        }
        if ("project".equals(descriptor.resourceType())) {
            binding.put("metadataFields", descriptor.parameters(plan.get("parameters")).keySet().stream().sorted().toList());
            if (!AiProjectMetadataChange.metadataBinding(binding) || !(plan.get("snapshot") instanceof Map<?, ?> snapshot)
                    || !(snapshot.get("uuid") instanceof String uuid) || uuid.isBlank())
                throw new IllegalStateException("Resource-change execution binding unavailable");
            binding.put("rootUuid", uuid);
        }
        java.util.ArrayList<Object> services = new java.util.ArrayList<>();
        if (plan.get("snapshot") instanceof Map<?, ?> snapshot && snapshot.get("childServices") instanceof List<?> children) {
            for (Object child : children) if (child instanceof Map<?, ?> row && row.get("id") instanceof Number) services.add(row.get("id"));
        }
        binding.put("services", services);
        if (AiLoadBalancerCreateChange.supports(descriptor)) {
            Object references = plan.get("referenceSnapshot") instanceof Map<?, ?> snapshot ? snapshot.get("targets") : null;
            binding.put("loadBalancerTargets", pins(references, List.of("id", "uuid", "accountId", "stackId", "stackUuid", "kind")));
            Object parent = plan.get("parentSnapshot");
            binding.put("loadBalancerParent", pins(List.of(parent), List.of("id", "uuid", "accountId", "kind")).getFirst());
        }
        if ("dnsService".equals(descriptor.resourceType()) || AiAliasServiceChange.ordinaryLinks(descriptor)) {
            if (AiAliasServiceChange.ordinaryLinks(descriptor) && plan.get("snapshot") instanceof Map<?, ?> source) {
                binding.put("rootUuid", source.get("uuid")); binding.put("rootStackId", source.get("stackId"));
                binding.put("rootStackUuid", source.get("linkStackUuid"));
            }
            Object references = plan.get("referenceSnapshot") instanceof Map<?, ?> snapshot ? snapshot.get("after") : null;
            binding.put("aliasReferences", pins(references, List.of("id", "uuid", "accountId", "stackId", "stackUuid", "kind")));
            Object previous = plan.get("referenceSnapshot") instanceof Map<?, ?> snapshot ? snapshot.get("before") : null;
            binding.put("aliasBeforeReferences", pins(previous, List.of("id", "uuid", "accountId", "stackId", "stackUuid", "kind")));
            Object links = plan.get("snapshot") instanceof Map<?, ?> snapshot ? snapshot.get("aliasLinks") : null;
            binding.put("aliasLinks", pins(links, List.of("id", "uuid", "accountId", "serviceId", "consumedServiceId", "consumedUuid")));
        }
        return binding;
    }

    private List<Map<String, Object>> pins(Object value, List<String> fields) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (value instanceof List<?> rows) for (Object row : rows) if (row instanceof Map<?, ?> source) {
            Map<String, Object> pin = new java.util.LinkedHashMap<>();
            for (String field : fields) if (source.containsKey(field)) pin.put(field, source.get(field));
            result.add(pin);
        }
        return result;
    }

    private String encode(Map<String, Object> plan) {
        Map<String, Object> document = new java.util.LinkedHashMap<>(plan); document.remove(PRIVATE_FAILURE);
        try { return json.writeValueAsString(document); }
        catch (IOException error) { throw new IllegalStateException("Cannot persist resource-change receipt", error); }
    }

    private Map<String, Object> decode(String value) {
        try { return json.readValue(value); }
        catch (IOException error) { throw new IllegalStateException("Resource-change receipt unavailable", error); }
    }
}
