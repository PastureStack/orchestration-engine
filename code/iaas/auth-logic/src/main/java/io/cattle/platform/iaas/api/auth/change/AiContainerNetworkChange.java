package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.InstanceLink;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.util.PortSpec;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** One existing child per native PUT; no lifecycle, batch transaction or dataplane writer. */
public final class AiContainerNetworkChange {
    private AiContainerNetworkChange() { }

    public static boolean supports(AiResourceChangeDescriptor descriptor) {
        return "port".equals(descriptor.resourceType()) && "update_public_port".equals(descriptor.operation())
                || "instanceLink".equals(descriptor.resourceType()) && "update_target".equals(descriptor.operation());
    }

    public static Map<String, Object> parameters(AiResourceChangeDescriptor descriptor, Object value) {
        String field = "port".equals(descriptor.resourceType()) ? "publicPort" : "targetInstanceId";
        Set<String> fields = "publicPort".equals(field) ? Set.of("publicPort", "resourceUuid", "sourceId", "sourceUuid")
                : Set.of("targetInstanceId", "targetInstanceUuid", "resourceUuid", "sourceId", "sourceUuid");
        if (!(value instanceof Map<?, ?> input) || !input.keySet().equals(fields)
                || !"publicPort".equals(field) && (!(input.get("targetInstanceUuid") instanceof String uuid)
                    || uuid.isBlank() || uuid.length() > 128)) invalid();
        for (String identity : List.of("resourceUuid", "sourceId", "sourceUuid"))
            if (!(((Map<?, ?>) value).get(identity) instanceof String text) || text.isBlank() || text.length() > 128) invalid();
        Object selected = ((Map<?, ?>) value).get(field);
        if ("publicPort".equals(field)) {
            if (selected != null && (!(selected instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() != number.longValue() || number.longValue() < 1 || number.longValue() > 65535)) invalid();
            if (selected != null) selected = ((Number) selected).intValue();
        } else if (!(selected instanceof String id) || !id.matches("[A-Za-z0-9_.-]{1,128}")) {
            // Clearing/deleting a link is a separate native workflow, not this retarget adapter.
            invalid();
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put(field, selected);
        if (!"publicPort".equals(field)) result.put("targetInstanceUuid", ((Map<?, ?>) value).get("targetInstanceUuid"));
        for (String identity : List.of("resourceUuid", "sourceId", "sourceUuid")) result.put(identity, ((Map<?, ?>) value).get(identity));
        return result;
    }

    public static Map<String, Object> body(Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>(parameters);
        for (String field : List.of("targetInstanceUuid", "resourceUuid", "sourceId", "sourceUuid")) body.remove(field);
        return body;
    }

    public static void requireSelection(Object child, String uuid, long sourceId, String sourceUuid, Instance source) {
        String actual = child instanceof Port port ? port.getUuid() : child instanceof InstanceLink link ? link.getUuid() : null;
        if (uuid == null || uuid.isBlank() || !uuid.equals(actual) || sourceId != sourceId(child)
                || source == null || sourceUuid == null || !sourceUuid.equals(source.getUuid()))
            throw new ClientVisibleException(409, "ResourceChangePlanStale");
    }

    public static long sourceId(Object child) {
        Long id = child instanceof Port port ? port.getInstanceId() : child instanceof InstanceLink link ? link.getInstanceId() : null;
        if (id == null || id <= 0) unsupported();
        return id;
    }

    public static void ordinary(Instance source, Account project) {
        Object system = source == null ? null : DataAccessor.field(source, "systemContainer", Object.class);
        if (source == null || source.getId() == null || source.getUuid() == null || source.getUuid().isBlank()
                || !"container".equals(source.getKind()) || source.getRemoved() != null
                || !Set.of("running", "stopped").contains(Objects.toString(source.getState(), ""))
                || system != null && !Boolean.FALSE.equals(system) && !"".equals(system)
                || project == null || !"project".equals(project.getKind()) || project.getRemoved() != null
                || project.getUuid() == null || project.getUuid().isBlank() || !Objects.equals(source.getAccountId(), project.getId())) unsupported();
    }

    public static Map<String, Object> snapshot(Object child, Instance source, Account project,
            Instance currentTarget, Instance requestedTarget, Long requestedTargetHost, JsonMapper json) {
        ordinary(source, project);
        Map<String, Object> result = childSnapshot(child, json);
        if (((Number) result.get("instanceId")).longValue() != source.getId()
                || ((Number) result.get("accountId")).longValue() != project.getId()) unsupported();
        result.put("source", instanceSnapshot(source, child instanceof Port, json));
        result.put("projectUuid", project.getUuid());
        if (child instanceof InstanceLink link) {
            if (link.getTargetInstanceId() != null) {
                ordinary(currentTarget, project);
                if (!Objects.equals(currentTarget.getId(), link.getTargetInstanceId())) unsupported();
                result.put("currentTarget", instanceSnapshot(currentTarget, false, json));
            } else result.put("currentTarget", null);
            ordinary(requestedTarget, project);
            if (Objects.equals(source.getId(), requestedTarget.getId())) invalid();
            String mode = DataAccessor.fieldString(source, "networkMode");
            Long requestedHost = DataAccessor.fieldLong(source, "requestedHostId");
            if (!"managed".equals(mode) && requestedHost != null && !Objects.equals(requestedHost, requestedTargetHost)) unsupported();
            result.put("requestedTarget", instanceSnapshot(requestedTarget, false, json));
            result.put("requestedTargetHost", requestedTargetHost);
        }
        return normalize(result, json);
    }

    public static Map<String, Object> childSnapshot(Object child, JsonMapper json) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (child instanceof Port port) {
            result.put("id", port.getId()); result.put("uuid", port.getUuid()); result.put("accountId", port.getAccountId());
            result.put("instanceId", port.getInstanceId()); result.put("state", port.getState()); result.put("removed", port.getRemoved());
            result.put("publicPort", port.getPublicPort()); result.put("privatePort", port.getPrivatePort()); result.put("protocol", port.getProtocol());
            result.put("publicIpAddressId", port.getPublicIpAddressId()); result.put("privateIpAddressId", port.getPrivateIpAddressId());
            result.put("dataSha256", digest(port.getData(), json));
        } else if (child instanceof InstanceLink link) {
            result.put("id", link.getId()); result.put("uuid", link.getUuid()); result.put("accountId", link.getAccountId());
            result.put("instanceId", link.getInstanceId()); result.put("state", link.getState()); result.put("removed", link.getRemoved());
            result.put("linkName", link.getLinkName()); result.put("targetInstanceId", link.getTargetInstanceId());
            result.put("dataSha256", digest(link.getData(), json));
        } else unsupported();
        if (!(result.get("id") instanceof Number id) || id.longValue() <= 0 || !(result.get("uuid") instanceof String uuid)
                || uuid.isBlank() || result.get("removed") != null
                || !Set.of("active", "inactive").contains(Objects.toString(result.get("state"), ""))) unsupported();
        return normalize(result, json);
    }

    public static Map<String, Object> instanceSnapshot(Instance instance, boolean portChange, JsonMapper json) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", instance.getId()); result.put("uuid", instance.getUuid()); result.put("accountId", instance.getAccountId());
        result.put("kind", instance.getKind()); result.put("name", instance.getName()); result.put("state", instance.getState());
        result.put("removed", instance.getRemoved()); result.put("stackId", instance.getStackId()); result.put("serviceId", instance.getServiceId());
        result.put("startCount", DataAccessor.field(instance, "startCount", Object.class));
        Map<String, Object> data = normalize(instance.getData(), json);
        if (portChange && data.get("fields") instanceof Map<?, ?> fields) {
            Map<String, Object> retained = new LinkedHashMap<>();
            fields.forEach((key, value) -> { if (!"ports".equals(key)) retained.put(key.toString(), value); });
            data.put("fields", retained);
        }
        result.put("configurationSha256", digest(data, json));
        return normalize(result, json);
    }

    public static Map<String, Object> identity(Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("id", "uuid", "accountId", "instanceId", "projectUuid")) result.put(field, snapshot.get(field));
        Map<?, ?> source = (Map<?, ?>) snapshot.get("source"); result.put("sourceUuid", source.get("uuid"));
        return result;
    }

    public static boolean childComplete(Object child, Map<String, Object> parameters, Map<String, Object> before, JsonMapper json) {
        Map<String, Object> expected = new LinkedHashMap<>(before);
        for (String field : List.of("source", "projectUuid", "currentTarget", "requestedTarget", "requestedTargetHost")) expected.remove(field);
        if (child instanceof Port) expected.put("publicPort", parameters.get("publicPort"));
        else if (child instanceof InstanceLink) {
            Object requested = ((Map<?, ?>) before.get("requestedTarget")).get("id"); expected.put("targetInstanceId", requested);
        } else return false;
        return childSnapshot(child, json).equals(expected);
    }

    public static List<String> portSpecs(List<? extends Port> ports, Port selected, Object publicPort) {
        List<String> result = new ArrayList<>();
        for (Port port : ports) {
            if (port.getRemoved() != null) continue;
            PortSpec spec = new PortSpec(port);
            if (Objects.equals(port.getId(), selected.getId())) spec.setPublicPort((Integer) publicPort);
            result.add(spec.toSpec());
        }
        return result;
    }

    private static Object ordered(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>(); map.forEach((key, item) -> result.put(key.toString(), ordered(item))); return result;
        }
        if (value instanceof List<?> list) return list.stream().map(AiContainerNetworkChange::ordered).toList();
        return value;
    }

    private static String digest(Object value, JsonMapper json) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(ordered(value)))); }
        catch (IOException | NoSuchAlgorithmException failure) { throw new ClientVisibleException(503, "ResourceChangeUnavailable"); }
    }

    private static Map<String, Object> normalize(Object value, JsonMapper json) {
        if (value == null) return new LinkedHashMap<>();
        try { return json.readValue(json.writeValueAsBytes(value)); }
        catch (IOException failure) { throw new ClientVisibleException(503, "ResourceChangeUnavailable"); }
    }

    private static void invalid() { throw AiResourceChangeDescriptor.error("ResourceChangeInputInvalid"); }
    private static void unsupported() { throw AiResourceChangeDescriptor.error("ResourceChangeUnsupported"); }
}
