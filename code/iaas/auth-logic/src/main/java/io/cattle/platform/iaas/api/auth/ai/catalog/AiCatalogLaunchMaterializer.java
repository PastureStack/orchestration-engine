package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.docker.transform.DockerTransformer;
import io.cattle.platform.object.util.ObjectUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Pagination;
import io.github.ibuildthecloud.gdapi.model.impl.WrappedResource;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Calls the existing Catalog/Compose/Docker owners; never parses or renders Compose in Java. */
public class AiCatalogLaunchMaterializer {
    /** Public reference locators are authorized metadata, separate from the native mutation graph. */
    public record PreparedGraph(Map<String, Object> nativeGraph, Map<String, String> authorizedReferenceIds) {
        public PreparedGraph {
            nativeGraph = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nativeGraph));
            authorizedReferenceIds = Map.copyOf(authorizedReferenceIds);
        }
    }

    @Inject AiCatalogLaunchAccess access;
    @Inject AiCatalogLaunchRunner runner;
    @Inject AiCatalogLaunchGraph graphs;
    @Inject DockerTransformer transformer;

    PreparedGraph prepare(ApiRequest request, AiCatalogLaunchAccess.Authorization authorization,
            Map<String, Object> plan, Map<String, Object> material, Map<String, Object> variables) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", plan.get("name"));
        Account account = access.objects.loadResource(Account.class, authorization.current().policy().getAccountId());
        input.put("environmentName", Objects.toString(account.getName(), ""));
        Map<String, Object> files = map(material.get("files"));
        input.put("dockerCompose", choose(files, "docker-compose.yml", true));
        input.put("rancherCompose", choose(files, "rancher-compose.yml", false));
        input.put("variables", variables);
        Map<String, Object> inspect = runner.run(input), transformed = new LinkedHashMap<>();
        if (!(inspect.get("services") instanceof List<?> services) || services.isEmpty() || services.size() > 100)
            throw error(422, "CatalogLaunchUnsupported");
        var schemas = authorization.current().schemas(); var container = schemas.getSchema("container");
        if (container == null) throw error(403, "OwnerPermissionDenied");
        for (Object value : services) {
            Map<String, Object> service = map(value); String name = text(service.get("name"), 1, 63);
            if (transformed.containsKey(name)) throw error(422, "CatalogLaunchUnsupported");
            InstanceRecord instance = new InstanceRecord();
            try { transformer.transform(map(service.get("dockerInspect")), instance); }
            catch (RuntimeException invalid) { throw error(422, "CatalogLaunchUnsupported"); }
            // Exactly the normal transform response's schema projection, under the original role.
            WrappedResource resource = new WrappedResource(ApiContext.getContext().getIdFormatter(), schemas, container, instance, "POST");
            transformed.put(name, new LinkedHashMap<>(resource.getFields()));
        }
        Map<String, String> authorizedReferenceIds = new LinkedHashMap<>();
        for (var entry : nullableMap(inspect.get("secrets")).entrySet()) {
            Map<String, Object> definition = map(entry.getValue());
            if (!(definition.get("External") instanceof String external) || external.isEmpty()
                    || definition.get("File") instanceof String file && !file.isEmpty()) throw error(422, "CatalogLaunchUnsupported");
            Object reference = named(request, authorization, "secret", entry.getKey());
            authorizedReferenceIds.put(entry.getKey(), String.valueOf(ApiContext.getContext().getIdFormatter()
                    .formatId("secret", ObjectUtils.getId(reference))));
        }
        input.put("phase", "finalize"); input.put("transformedLaunchConfigs", transformed); input.put("resolvedSecrets", authorizedReferenceIds);
        Map<String, Object> graph = graphs.accept(runner.run(input));
        if (!graphs.digest(variables).equals(graphs.digest(graph.get("variables")))) throw error(503, "CatalogLaunchUnavailable");
        // The future child has no persisted ID yet. Authorize its real destination
        // Project and all existing references; do not invent a child grant or Stack ID.
        for (String kind : List.of("services", "volumes")) for (Object value : (List<?>) graph.get(kind)) {
            Map<String, Object> node = map(value);
            access.preflightChild(request, authorization, plan, (String) node.get("type"), map(node.get("body")));
            if ("services".equals(kind)) access.childActions(authorization, (String) node.get("type"), Boolean.TRUE.equals(plan.get("startOnCreate")));
        }
        return new PreparedGraph(graph, authorizedReferenceIds);
    }

    Object reference(ApiRequest request, AiCatalogLaunchAccess.Authorization authorization, String type, String wireId) {
        String parsed = ApiContext.getContext().getIdFormatter().parseId(wireId);
        if (parsed == null || !parsed.matches("[1-9][0-9]*")
                || !wireId.equals(String.valueOf(ApiContext.getContext().getIdFormatter().formatId(type, Long.valueOf(parsed)))))
            throw error(422, "CatalogLaunchInputInvalid");
        return access.domain(request, authorization, type, "GET", wireId, null, Map.of(), domain -> {
            Object row = access.manager(type).getById(type, domain.getId(), new ListOptions(domain));
            if (row == null || ObjectUtils.getRemoved(row) != null
                    || !Objects.equals(ObjectUtils.getAccountId(row), authorization.current().policy().getAccountId()))
                throw error(403, "OwnerPermissionDenied");
            return row;
        });
    }

    private Object named(ApiRequest request, AiCatalogLaunchAccess.Authorization authorization, String type, String name) {
        return access.domain(request, authorization, type, "GET", null, null, Map.of(), domain -> {
            domain.getConditions().put("name", List.of(new Condition(ConditionType.EQ, name)));
            domain.getConditions().put("removed", List.of(new Condition(ConditionType.NULL)));
            domain.setPagination(new Pagination(2));
            Object result = access.manager(type).list(type, domain);
            if (!(result instanceof List<?> rows) || rows.size() != 1) throw error(403, "OwnerPermissionDenied");
            Object row = rows.getFirst();
            String id = String.valueOf(ApiContext.getContext().getIdFormatter().formatId(type, ObjectUtils.getId(row)));
            return reference(request, authorization, type, id);
        });
    }

    private String choose(Map<String, Object> files, String name, boolean required) {
        Object content = files.containsKey(name + ".tpl") ? files.get(name + ".tpl") : files.get(name);
        if (content == null && !required) return "";
        return text(content, required ? 1 : 0, 1048576);
    }

    static Map<String, Object> nullableMap(Object value) { return value == null ? new LinkedHashMap<>() : map(value); }
}
