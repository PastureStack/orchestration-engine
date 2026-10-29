package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.json.JsonMapper;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** The Compose owner's exact private HTTP graph; this class does not interpret YAML or invent create fields. */
public class AiCatalogLaunchGraph {
    @Inject JsonMapper json;

    Map<String, Object> accept(Map<String, Object> output) {
        if (!"finalize".equals(output.get("phase"))) throw error(503, "CatalogLaunchUnavailable");
        List<Map<String, Object>> services = requests(output.get("serviceRequests"), "createBody", Set.of("service"));
        List<Map<String, Object>> volumes = requests(output.get("volumeRequests"), "createSkeleton", Set.of("volumeTemplate"));
        if (services.isEmpty()) throw error(422, "CatalogLaunchUnsupported");
        Set<String> serviceNames = services.stream().map(node -> (String) node.get("name")).collect(java.util.stream.Collectors.toSet());
        for (Map<String, Object> service : services) {
            Map<String, Object> body = closed(service.get("links"), Set.of("serviceLinks"));
            if (!(body.get("serviceLinks") instanceof List<?> links) || links.size() > 100) throw error(422, "CatalogLaunchUnsupported");
            Set<String> unique = new java.util.HashSet<>();
            for (Object value : links) {
                Map<String, Object> link = closed(value, Set.of("name", "serviceId"));
                if (!unique.add(digest(link))) throw error(422, "CatalogLaunchUnsupported");
                String target = text(link.get("serviceId"), 1, 128);
                if (!target.startsWith("$service:") || !serviceNames.contains(target.substring("$service:".length())))
                    throw error(422, "CatalogLaunchUnsupported");
                if (link.containsKey("name")) text(link.get("name"), 0, 255);
            }
        }
        // Foreign network/host/dependency creation is deliberately not a part of this owner's first launch graph.
        for (String field : List.of("networks", "hosts", "dependencies")) {
            Object value = output.get(field);
            if (value != null && !map(value).isEmpty()) throw error(422, "CatalogLaunchUnsupported");
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("services", services); graph.put("volumes", volumes);
        graph.put("dockerCompose", text(output.get("dockerCompose"), 1, 1048576));
        graph.put("rancherCompose", text(output.get("rancherCompose"), 0, 1048576));
        graph.put("variables", primitives(output.get("variables")));
        graph.put("inspect", output.get("services"));
        return graph;
    }

    private List<Map<String, Object>> requests(Object value, String bodyField, Set<String> types) {
        if (!(value instanceof List<?> list) || list.size() > 100) throw error(422, "CatalogLaunchUnsupported");
        List<Map<String, Object>> result = new ArrayList<>(); Set<String> names = new java.util.LinkedHashSet<>();
        for (Object item : list) {
            Map<String, Object> source = map(item);
            String name = text(source.get("name"), 1, 63), type = text(source.get("resourceType"), 1, 64);
            if (!name.matches("[A-Za-z0-9-]{1,63}") || !names.add(name) || !types.contains(type)) throw error(422, "CatalogLaunchUnsupported");
            Map<String, Object> body = map(source.get(bodyField));
            if (!name.equals(body.get("name")) || !"$createdStack".equals(body.get("stackId"))) throw error(422, "CatalogLaunchUnsupported");
            // The ordinary external-volume owner binds by mutable project name
            // later in DeploymentUnit. Do not approve an apparently pinned ID
            // until that owner supplies an exact-reference adapter.
            if ("volumeTemplate".equals(type) && Boolean.TRUE.equals(body.get("external"))) throw error(422, "CatalogLaunchUnsupported");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name); entry.put("type", type); entry.put("body", body);
            if ("service".equals(type)) entry.put("links", source.get("setServiceLinks"));
            result.add(entry);
        }
        return result;
    }

    public String digest(Object value) {
        try {
            byte[] bytes = json.writeValueAsString(canonical(value)).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception unavailable) { throw error(503, "CatalogLaunchUnavailable"); }
    }

    /** Only the owner's top-level Stack reference is a symbol; workload strings remain literal. */
    Object bind(Object value, String stackId) {
        Map<String, Object> body = map(value);
        if (!"$createdStack".equals(body.get("stackId"))) throw error(422, "CatalogLaunchUnsupported");
        body.put("stackId", stackId); return body;
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> sorted = new TreeMap<>();
            for (var entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw error(422, "CatalogLaunchUnsupported");
                sorted.put(key, canonical(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw error(422, "CatalogLaunchUnsupported");
            return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        }
        if (value == null || value instanceof Boolean || value instanceof String) return value;
        throw error(422, "CatalogLaunchUnsupported");
    }
}
