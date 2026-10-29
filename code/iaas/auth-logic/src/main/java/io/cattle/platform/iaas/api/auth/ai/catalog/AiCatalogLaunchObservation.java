package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.object.util.ObjectUtils;
import io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Authoritative observation of the exact created graph, using the existing deployment owner. */
public class AiCatalogLaunchObservation {
    @Inject AiCatalogLaunchAccess access;
    @Inject AiCatalogLaunchPrivate privateData;
    @Inject AiCatalogLaunchPlanStore store;
    @Inject ServiceDeploymentReadiness deployments;
    Clock clock = Clock.systemUTC();

    void visible(Map<String, Object> plan, ApiRequest request) {
        if (!(plan.get("internalStackId") instanceof Number stackId)) return;
        var authority = access.bound(plan, request);
        String wireId = String.valueOf(ApiContext.getContext().getIdFormatter().formatId("stack", stackId.longValue()));
        Object row = read(request, authority, "stack", wireId);
        if (!(row instanceof Stack stack) || !Objects.equals(stack.getId(), stackId.longValue())
                || !Objects.equals(stack.getAccountId(), ((Number) plan.get("accountId")).longValue())) throw error(403, "OwnerPermissionDenied");
    }

    void refresh(Map<String, Object> plan, ApiRequest request) {
        if (!(plan.get("internalStackId") instanceof Number stackId)) { uncertain(plan); store.save(plan); return; }
        var authority = access.bound(plan, request);
        String wireId = String.valueOf(ApiContext.getContext().getIdFormatter().formatId("stack", stackId.longValue()));
        Object visible = read(request, authority, "stack", wireId);
        if (!(visible instanceof Stack stack) || stack.getRemoved() != null || !Objects.equals(stack.getAccountId(), ((Number)
                plan.get("accountId")).longValue())) {
            plan.put("status", "failed"); plan.put("reason", "CatalogLaunchCreatedResourceGone"); store.save(plan); return;
        }
        if ("error".equals(stack.getState()) || "error".equals(ObjectUtils.getValue(stack, "transitioning"))) {
            plan.put("status", "failed"); plan.put("reason", "CatalogLaunchDispatchFailed"); store.save(plan); return;
        }
        Map<String, Object> values = privateData.open(plan), graph = map(values.get("graph"));
        boolean ready = "active".equals(stack.getState()) && Boolean.TRUE.equals(plan.get("dispatchAcknowledged"));
        Map<String, Object> created = AiCatalogLaunchMaterializer.nullableMap(plan.get("createdGraph"));
        List<Service> actual = access.objects.find(Service.class, "stackId", stackId.longValue(), "removed", null);
        List<?> expected = (List<?>) graph.get("services");
        ready &= actual.size() == expected.size();
        Set<Long> seen = new java.util.HashSet<>();
        for (Object value : expected) {
            Map<String, Object> node = map(value); Object id = created.get("service:" + node.get("name"));
            if (!(id instanceof Number number)) { ready = false; continue; }
            String serviceId = String.valueOf(ApiContext.getContext().getIdFormatter().formatId("service", number.longValue()));
            Object row = read(request, authority, "service", serviceId);
            if (!(row instanceof Service service) || service.getRemoved() != null || !Objects.equals(service.getStackId(), stackId.longValue())
                    || !Objects.equals(service.getAccountId(), stack.getAccountId()) || !Objects.equals(service.getName(), node.get("name"))
                    || !seen.add(service.getId())) { ready = false; continue; }
            if (Boolean.TRUE.equals(plan.get("startOnCreate"))) ready &= "active".equals(service.getState()) && deployments.isReady(service);
            else ready &= "inactive".equals(service.getState());
            ready &= linksSettled(service, node, created);
        }
        for (Object value : (List<?>) graph.get("volumes")) {
            Map<String, Object> node = map(value); Object id = created.get("volumeTemplate:" + node.get("name"));
            if (!(id instanceof Number number)) { ready = false; continue; }
            String volumeId = String.valueOf(ApiContext.getContext().getIdFormatter().formatId("volumeTemplate", number.longValue()));
            Object row = read(request, authority, "volumeTemplate", volumeId);
            ready &= row != null && ObjectUtils.getRemoved(row) == null && "active".equals(ObjectUtils.getState(row))
                    && Objects.equals(ObjectUtils.getId(row), number.longValue()) && Objects.equals(ObjectUtils.getValue(row, "name"), node.get("name"))
                    && Objects.equals(ObjectUtils.getAccountId(row), stack.getAccountId())
                    && Objects.equals(ObjectUtils.getValue(row, "stackId"), stackId.longValue());
        }
        Map<String, Object> ledger = AiCatalogLaunchMaterializer.nullableMap(plan.get("ledger"));
        ready &= ledger.values().stream().allMatch(entry -> "completed".equals(map(entry).get("state")));
        for (Object value : expected) {
            String name = (String) map(value).get("name");
            ready &= completed(ledger, "create:service:" + name) && completed(ledger, "links:" + name);
            if (Boolean.TRUE.equals(plan.get("startOnCreate"))) ready &= completed(ledger, "activate:" + name);
        }
        for (Object value : (List<?>) graph.get("volumes")) ready &= completed(ledger, "create:volumeTemplate:" + map(value).get("name"));
        // A successful Stack process alone does not prove the full graph or its
        // intended service links settled. The verified HTTP ledger records that boundary.
        ready &= Boolean.TRUE.equals(plan.get("linksSettled"));
        if (ready) {
            plan.put("status", "succeeded"); plan.put("reason", "CatalogLaunchSucceeded"); plan.put("verifiedResult", true);
            plan.put("delegationClosed", true);
        } else if (clock.millis() - ((Number) plan.getOrDefault("acceptedAt", clock.millis())).longValue() > 900000) uncertain(plan);
        else { plan.put("status", "running"); plan.put("reason", "CatalogLaunchRunning"); }
        store.save(plan);
    }

    private Object read(ApiRequest request, AiCatalogLaunchAccess.Authorization authority, String type, String id) {
        return access.domain(request, authority, type, "GET", id, null, Map.of(), domain ->
                access.manager(type).getById(type, domain.getId(), new ListOptions(domain)));
    }

    private boolean linksSettled(Service service, Map<String, Object> node, Map<String, Object> created) {
        Map<String, Object> desired = map(node.get("links"));
        if (!(desired.get("serviceLinks") instanceof List<?> links)) return false;
        Set<String> expected = new java.util.HashSet<>();
        for (Object value : links) {
            Map<String, Object> link = map(value);
            if (!(link.get("serviceId") instanceof String symbol) || !symbol.startsWith("$service:")) return false;
            Object id = created.get("service:" + symbol.substring("$service:".length()));
            if (!(id instanceof Number number)) return false;
            expected.add(number.longValue() + ":" + Objects.toString(link.get("name"), ""));
        }
        Set<String> actual = new java.util.HashSet<>();
        for (ServiceConsumeMap link : access.objects.find(ServiceConsumeMap.class, "serviceId", service.getId(), "removed", null)) {
            if (!Objects.equals(link.getAccountId(), service.getAccountId()) || !"active".equals(link.getState()) || link.getConsumedServiceId()
                    == null) return false;
            if (!actual.add(link.getConsumedServiceId() + ":" + Objects.toString(link.getName(), ""))) return false;
        }
        return expected.equals(actual);
    }

    private boolean completed(Map<String, Object> ledger, String operation) {
        return ledger.get(operation) instanceof Map<?, ?> entry && "completed".equals(entry.get("state"));
    }

    private void uncertain(Map<String, Object> plan) { plan.put("status", "unknown"); plan.put("reason", "CatalogLaunchDispatchUncertain"); }
}
