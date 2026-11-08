package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Correlation only. Native managers, roles and Key policy remain the authorization owners. */
final class AiCookieLifecycleWitness {
    static final String SNAPSHOT = "cookieLifecycleWitness";
    private static final String ATTRIBUTE = "ai.resource.change.cookie.lifecycle.dispatch";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AiCookieLifecycleWitness.class);
    private final AiResourceChangeProcessObserver owner;
    private record Dispatch(ApiRequest request, String id) { }
    private record Edge(String id, Map<String, Object> binding, Map<String, Object> target, Long parent) { }

    AiCookieLifecycleWitness(AiResourceChangeProcessObserver owner) { this.owner = owner; }

    static boolean supports(AiResourceChangeDescriptor descriptor) {
        return Set.of("registry", "loadBalancerService", "externalService", "dnsService", "stack").contains(descriptor.resourceType())
                && Set.of("start", "stop").contains(descriptor.operation())
                || "container".equals(descriptor.resourceType()) && Set.of("start", "stop", "restart").contains(descriptor.operation())
                || "service".equals(descriptor.resourceType()) && "restart".equals(descriptor.operation());
    }

    static boolean binding(Map<String, Object> binding) {
        return binding != null && AiResourceChangePlanStore.sessionLifecycle(binding)
                && supports(AiResourceChangeDescriptor.require((String) binding.get("resourceType"), (String) binding.get("operation")));
    }

    Map<String, Object> snapshot(AiResourceChangeDescriptor descriptor, Object target) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("root", identity(target));
        List<Map<String, Object>> children = new ArrayList<>();
        if ("stack".equals(descriptor.resourceType())) {
            require(target instanceof Stack);
            for (Service child : owner.objects.find(Service.class, "stackId", ObjectUtils.getId(target), "removed", null)) {
                require(Objects.equals(child.getAccountId(), ObjectUtils.getAccountId(target)));
                children.add(identity(child));
            }
            children.sort(Comparator.comparingLong(row -> number(row.get("id"))));
        }
        result.put("children", children); return result;
    }

    private Map<String, Object> identity(Object target) {
        require(target != null && ObjectUtils.getRemoved(target) == null && number(ObjectUtils.getId(target)) > 0
                && number(ObjectUtils.getAccountId(target)) > 0 && uuid(target) != null);
        Account project = owner.objects.loadResource(Account.class, number(ObjectUtils.getAccountId(target)));
        require(project != null && "project".equals(project.getKind()) && project.getRemoved() == null && uuid(project) != null);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : List.of("id", "uuid", "accountId", "kind", "state"))
            result.put(name, ObjectUtils.getPropertyIgnoreErrors(target, name));
        if (target instanceof Service service) result.put("stackId", service.getStackId());
        result.put("projectUuid", project.getUuid());
        try { return owner.json.readValue(owner.json.writeValueAsBytes(result)); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Exact lifecycle identity unavailable", failure); }
    }

    boolean identityMatches(Map<String, Object> before, Object target) { return !before.isEmpty() && same(before, target); }

    private boolean same(Map<String, Object> before, Object target) {
        Map<String, Object> current = identity(target); Map<String, Object> expected = new LinkedHashMap<>(before);
        expected.remove("state"); current.remove("state"); return expected.equals(current);
    }

    <T> T dispatch(ApiRequest request, String id, Supplier<T> action) {
        Map<String, Object> binding = owner.store.execution(id); Map<String, Object> plan = owner.store.load(id);
        Policy policy = ApiUtils.getPolicy();
        require(binding(binding) && policy != null && number(binding.get("actorId")) == policy.getAuthenticatedAsAccountId()
                && number(binding.get("accountId")) == policy.getAccountId() && requestMatches(binding, request)
                && plan != null && id.equals(plan.get("operationId")) && "dispatching".equals(plan.get("status"))
                && Map.of().equals(plan.get("parameters")) && Objects.equals(plan.get("body"), request.getRequestObject()));
        Object previous = request.getAttribute(ATTRIBUTE); request.setAttribute(ATTRIBUTE, new Dispatch(request, id));
        try {
            T result = action.get();
            Map<String, Object> before = owner.store.execution(id); require(binding(before));
            Map<String, Object> after = new LinkedHashMap<>(before); after.put("cookieDispatchClosed", true);
            require(owner.store.attest(id, before, after)); return result;
        } finally { request.setAttribute(ATTRIBUTE, previous); }
    }

    /** Runs after common prepare stripped every caller-supplied correlation value. */
    void prepare(LaunchConfiguration config) {
        Edge edge = trackingEdge(config); if (edge == null) return;
        Map<String, Object> data = new LinkedHashMap<>(config.getData() == null ? Map.of() : config.getData());
        data.put(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION, Map.of("planId", edge.id())); config.setData(data);
    }

    ProcessRecord create(LaunchConfiguration config, Supplier<ProcessRecord> insert) {
        Edge edge = trackingEdge(config); if (edge == null) return insert.get();
        if (edge.parent() != null) {
            // Native execution is already accepted. Correlation can never veto its recovery/chain.
            ProcessRecord record = insert.get();
            try { owner.store.cookieTracking(() -> { attest(edge, config, record, true); return null; }); }
            catch (RuntimeException unavailable) {
                owner.store.retainNativeTransactionFailure(unavailable);
                try { owner.store.markCookieTrackingUnavailable(edge.id()); }
                catch (RuntimeException trackingUnavailable) { owner.store.retainNativeTransactionFailure(trackingUnavailable); }
                LOG.warn("Resource-change native chain tracking unavailable");
            }
            return record;
        }
        require(Map.of("planId", edge.id()).equals(config.getData().get(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION)));
        return owner.store.processCreation(() -> {
            ProcessRecord record = insert.get(); attest(edge, config, record, false); return record;
        });
    }

    private void attest(Edge edge, LaunchConfiguration config, ProcessRecord record, boolean chain) {
        Map<String, Object> before = owner.store.execution(edge.id()); require(binding(before));
        String key = key(config); Map<String, Object> processes = map(before.get("cookieProcesses")); require(!processes.containsKey(key));
        require(record != null && record.getId() != null && record.getId() > 0 && key.equals(key(record))
                && Objects.equals(config.getAccountId(), record.getAccountId()) && canonical(config.getData()).equals(canonical(record.getData())));
        Map<String, Object> proof = new LinkedHashMap<>(); proof.put("id", record.getId()); proof.put("target", edge.target());
        proof.put("parentId", edge.parent()); processes.put(key, proof);
        Map<String, Object> after = new LinkedHashMap<>(before); after.put("cookieProcesses", processes);
        require(chain ? owner.store.attestCookieChain(edge.id(), before, after) : owner.store.attest(edge.id(), before, after));
    }

    private Edge trackingEdge(LaunchConfiguration config) {
        try { return config.getChainParentProcessId() == null ? edge(config) : owner.store.cookieTracking(() -> edge(config)); }
        catch (RuntimeException unavailable) {
            if (config.getChainParentProcessId() == null) throw unavailable;
            owner.store.retainNativeTransactionFailure(unavailable);
            LOG.warn("Resource-change native chain tracking unavailable"); return null;
        }
    }

    private Edge edge(LaunchConfiguration config) {
        ApiContext context = ApiContext.getContext(); ApiRequest request = context == null ? null : context.getApiRequest();
        if (!EngineContext.hasParentProcess() && request != null && request.getAttribute(ATTRIBUTE) instanceof Dispatch scope
                && scope.request() == request) {
            Map<String, Object> binding = owner.store.execution(scope.id()); require(binding(binding) && requestMatches(binding, request));
            Map<String, Object> pin = expected(binding, config); require(pin != null && same(pin, load(config)));
            require(number(config.getAccountId()) == number(binding.get("accountId")) && rootPayload(binding, config, request));
            return new Edge(scope.id(), binding, pin, null);
        }
        if (config.getChainParentProcessId() == null || config.getParentProcessState() == null) return null;
        Object raw = config.getParentProcessState().getData().get(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION);
        if (!(raw instanceof Map<?, ?> marker) || !(marker.get("planId") instanceof String id)) return null;
        Map<String, Object> binding = owner.store.execution(id);
        if (!binding(binding) || !"container".equals(binding.get("resourceType")) || !"restart".equals(binding.get("operation"))
                || !owner.store.sessionLifecycleActive(id, binding)) return null;
        Map<String, Object> pin = map(map(binding.get(SNAPSHOT)).get("root"));
        String parentName = "instance.stop".equals(config.getProcessName()) ? "instance.restart"
                : "instance.start".equals(config.getProcessName()) ? "instance.stop" : "";
        Map<String, Object> parent = map(map(binding.get("cookieProcesses")).get("instance:" + binding.get("rootId") + ":" + parentName));
        var frame = EngineContext.getEngineContext().currentVerifiedExecution();
        require(number(parent.get("id")) == config.getChainParentProcessId() && same(pin, load(config))
                && "instance".equals(config.getResourceType()) && Long.toString(number(pin.get("id"))).equals(config.getResourceId())
                && number(config.getAccountId()) == number(pin.get("accountId")) && frame != null
                && "instance".equals(frame.resourceType()) && config.getResourceId().equals(frame.resourceId())
                && parentName.equals(frame.processName()) && frame.rootAuthorization().isEmpty());
        var row = owner.store.lifecycleProcess(config.getChainParentProcessId());
        require(row != null && parentName.equals(row.getProcessName()) && config.getResourceId().equals(row.getResourceId())
                && "instance".equals(row.getResourceType()) && Objects.equals(row.getAccountId(), config.getAccountId())
                && row.getData() != null && Map.of("planId", id).equals(row.getData().get(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION)));
        Map<String, Object> inherited = new LinkedHashMap<>(config.getParentProcessState().getData());
        inherited.remove(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION);
        Map<String, Object> actual = new LinkedHashMap<>(config.getData()); actual.remove(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION);
        require(inherited.equals(actual)); return new Edge(id, binding, pin, config.getChainParentProcessId());
    }

    private Map<String, Object> expected(Map<String, Object> binding, LaunchConfiguration config) {
        Map<String, Object> snapshot = map(binding.get(SNAPSHOT)); String operation = (String) binding.get("operation");
        if ("stack".equals(binding.get("resourceType"))) {
            for (Map<String, Object> pin : rows(snapshot.get("children"))) {
                boolean needs = "start".equals(operation) ? "inactive".equals(pin.get("state"))
                        : Set.of("active", "activating", "updating-active", "updating-inactive", "restarting").contains(pin.get("state"));
                if (needs && "service".equals(config.getResourceType()) && Objects.toString(pin.get("id")).equals(config.getResourceId())
                        && ("start".equals(operation) ? "service.activate" : "service.deactivate").equals(config.getProcessName())) return pin;
            }
            return null;
        }
        Map<String, Object> root = map(snapshot.get("root"));
        return nativeType(binding).equals(config.getResourceType()) && Objects.toString(root.get("id")).equals(config.getResourceId())
                && nativeProcess(binding).equals(config.getProcessName()) ? root : null;
    }

    private boolean rootPayload(Map<String, Object> binding, LaunchConfiguration config, ApiRequest request) {
        Map<String, Object> data = new LinkedHashMap<>(config.getData() == null ? Map.of() : config.getData());
        data.remove(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION);
        if (data.containsKey("old")) { if (!Map.of().equals(data.remove("old"))) return false; }
        if ("stack".equals(binding.get("resourceType")) && "start".equals(binding.get("operation"))) {
            if (!data.keySet().equals(Set.of("waitForConsumedServicesIds")) || !(data.get("waitForConsumedServicesIds") instanceof List<?> ids)) return false;
            Set<Long> children = new java.util.HashSet<>();
            for (Map<String, Object> child : rows(map(binding.get(SNAPSHOT)).get("children"))) children.add(number(child.get("id")));
            for (Object id : ids) if (number(id) < 1 || !children.contains(number(id))
                    || Objects.toString(id).equals(config.getResourceId())) return false;
            return true;
        }
        if ("service".equals(binding.get("resourceType")) && "restart".equals(binding.get("operation"))) {
            Map<String, Object> body = map(request.getRequestObject());
            if (!data.equals(body) || !body.keySet().equals(Set.of("rollingRestartStrategy"))) return false;
            var approved = owner.json.convertValue(owner.store.load((String) binding.get("id")).get("body"),
                    io.cattle.platform.core.addon.ServiceRestart.class);
            if (approved.getRollingRestartStrategy() == null) return false;
            Map<Long, Long> counts = new LinkedHashMap<>();
            for (Instance instance : owner.restartMaps.get().listServiceManagedInstances((Service) load(config)))
                counts.put(instance.getId(), instance.getStartCount());
            approved.getRollingRestartStrategy().setInstanceToStartCount(counts);
            return owner.json.writeValueAsMap(approved).equals(body);
        }
        return data.isEmpty();
    }

    String outcome(Map<String, Object> plan, Object target) {
        Map<String, Object> binding = owner.store.execution((String) plan.get("id"));
        if (!binding(binding) || Boolean.TRUE.equals(binding.get("cookieTrackingUnavailable"))
                || !Objects.equals(plan.get("id"), plan.get("operationId"))
                || !Objects.equals(binding.get("id"), plan.get("id")) || !Objects.equals(binding.get("operation"), plan.get("operation"))
                || !Objects.equals(binding.get("resourceType"), plan.get("resourceType"))
                || number(binding.get("actorId")) != number(plan.get("actorId")) || number(binding.get("accountId")) != number(plan.get("accountId"))
                || !Boolean.TRUE.equals(binding.get("cookieDispatchClosed"))
                || !owner.store.sessionLifecycleActive((String) plan.get("id"), binding)) return "unknown";
        Map<String, Object> snapshot = map(binding.get(SNAPSHOT));
        if (!same(map(snapshot.get("root")), target)) return "unknown";
        Map<String, Object> processes = map(binding.get("cookieProcesses")); List<String> expected = new ArrayList<>();
        if ("stack".equals(binding.get("resourceType"))) {
            List<Map<String, Object>> current = rows(snapshot(AiResourceChangeDescriptor.require("stack", (String) plan.get("operation")),
                    target).get("children"));
            List<Map<String, Object>> before = rows(snapshot.get("children")); if (before.size() != current.size()) return "unknown";
            for (int i = 0; i < before.size(); i++) {
                Map<String, Object> pin = before.get(i), fresh = current.get(i);
                Map<String, Object> a = new LinkedHashMap<>(pin), b = new LinkedHashMap<>(fresh); a.remove("state"); b.remove("state");
                if (!a.equals(b)) return "unknown";
                boolean start = "start".equals(plan.get("operation")); String desired = start ? "active" : "inactive";
                if (desired.equals(pin.get("state"))) { if (!desired.equals(fresh.get("state"))) return "unknown"; }
                else if (start ? "inactive".equals(pin.get("state"))
                        : Set.of("active", "activating", "updating-active", "updating-inactive", "restarting").contains(pin.get("state")))
                    expected.add("service:" + pin.get("id") + (start ? ":service.activate" : ":service.deactivate"));
                else return "unknown";
            }
        } else {
            expected.add(nativeType(binding) + ":" + binding.get("rootId") + ":" + nativeProcess(binding));
            if ("container".equals(binding.get("resourceType")) && "restart".equals(binding.get("operation"))) {
                expected.add("instance:" + binding.get("rootId") + ":instance.stop"); expected.add("instance:" + binding.get("rootId") + ":instance.start");
            }
        }
        if (!new java.util.HashSet<>(expected).containsAll(processes.keySet())) return "unknown";
        for (String key : expected) {
            Map<String, Object> proof = map(processes.get(key)); if (proof.isEmpty()) return "running";
            var row = owner.store.lifecycleProcess(number(proof.get("id"))); String[] fields = key.split(":");
            if (row == null || !fields[0].equals(row.getResourceType()) || !fields[1].equals(row.getResourceId())
                    || !fields[2].equals(row.getProcessName()) || number(row.getAccountId()) != number(binding.get("accountId"))
                    || row.getData() == null || !Map.of("planId", plan.get("id")).equals(row.getData().get(ProcessAuthorization.COOKIE_LIFECYCLE_CORRELATION))
                    || row.getData().containsKey(ProcessAuthorization.DATA_KEY)) return "unknown";
            if ("container".equals(binding.get("resourceType")) && "restart".equals(binding.get("operation")) && !"instance.restart".equals(fields[2])) {
                String previous = "instance.stop".equals(fields[2]) ? "instance.restart" : "instance.stop";
                Map<String, Object> parent = map(processes.get("instance:" + binding.get("rootId") + ":" + previous));
                if (number(proof.get("parentId")) != number(parent.get("id"))) return "unknown";
            }
            if (row.getEndTime() == null) return "running";
            boolean chain = "container".equals(binding.get("resourceType")) && "restart".equals(binding.get("operation"))
                    && !"instance.start".equals(fields[2]);
            if (!"SUCCESS".equals(row.getResult()) || !"DONE".equals(row.getPhase()) || !(chain ? "CHAIN" : "DONE").equals(row.getExitReason()))
                    return "failed";
        }
        return "succeeded";
    }

    private Object load(LaunchConfiguration config) { return owner.objects.loadResource(config.getResourceType(), config.getResourceId()); }
    private static String nativeType(Map<String, Object> binding) {
        return "registry".equals(binding.get("resourceType")) ? "storagePool" : "container".equals(binding.get("resourceType")) ? "instance" : "service";
    }
    private static String nativeProcess(Map<String, Object> binding) {
        return ("registry".equals(binding.get("resourceType")) ? "storagepool" : nativeType(binding)) + "." + binding.get("action");
    }
    private static String key(LaunchConfiguration config) { return config.getResourceType() + ":" + config.getResourceId() + ":" + config.getProcessName(); }
    private static boolean requestMatches(Map<String, Object> binding, ApiRequest request) {
        return Objects.equals(binding.get("resourceType"), request.getType()) && "POST".equals(request.getMethod())
                && Objects.equals(binding.get("action"), request.getAction()) && request.getLink() == null
                && Long.toString(number(binding.get("rootId"))).equals(request.getId());
    }
    private Map<String, Object> canonical(Map<String, Object> value) {
        try { return owner.json.readValue(owner.json.writeValueAsBytes(value)); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Exact native JSON payload unavailable", failure); }
    }

    private static String uuid(Object value) { Object uuid = ObjectUtils.getPropertyIgnoreErrors(value, "uuid"); return uuid instanceof String text
            && !text.isBlank() ? text : null; }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : -1; }
    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) for (var entry : source.entrySet()) { require(entry.getKey() instanceof String); result.put((String)
                entry.getKey(), entry.getValue()); }
        return result;
    }
    private static List<Map<String, Object>> rows(Object value) {
        List<Map<String, Object>> result = new ArrayList<>(); require(value instanceof List<?>);
        for (Object row : (List<?>) value) result.add(map(row)); return result;
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalStateException("Exact lifecycle process witness unavailable"); }
}
