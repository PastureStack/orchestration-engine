package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Schema;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Collection;

/** The selected Account root's metadata only. Native project owner checks remain authoritative. */
public class AiProjectMetadataChange {
    @Inject ProjectAccessService projectAccess;

    public static Account selected(Object value, long selectedProject) {
        if (!(value instanceof Account project) || project.getId() == null || project.getId() != selectedProject
                || !"project".equals(project.getKind()) || project.getRemoved() != null || !"active".equals(project.getState())
                || project.getUuid() == null || project.getUuid().isBlank()) throw new ClientVisibleException(404, "NotFound");
        return project;
    }

    public void authorize(Account project, Policy nativeOwner, Schema schema, boolean write, Collection<String> fields) {
        if (nativeOwner == null || schema == null || schema.getResourceMethods() == null
                || !schema.getResourceMethods().contains("GET")) throw new ClientVisibleException(403, "OwnerPermissionDenied");
        if (write) {
            if (fields == null || fields.isEmpty() || fields.stream().anyMatch(field -> !Set.of("name", "description").contains(field)))
                throw new ClientVisibleException(403, "OwnerPermissionDenied");
            // Metadata is this adapter's subset. The native Project owner retains all actual schema/owner checks without a grant.
            projectAccess.authorizeUpdate(project, nativeOwner, schema, fields);
        }
    }

    public static Map<String, Object> snapshot(Account project) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", project.getId()); result.put("uuid", project.getUuid()); result.put("kind", project.getKind());
        result.put("name", project.getName()); result.put("description", project.getDescription());
        result.put("state", project.getState()); result.put("removed", project.getRemoved());
        return result;
    }

    public static boolean metadataBinding(Map<String, Object> binding) {
        return binding != null && binding.get("metadataFields") instanceof List<?> fields && !fields.isEmpty() && fields.size() <= 2
                && fields.stream().allMatch(field -> field instanceof String name && Set.of("name", "description").contains(name))
                && Set.copyOf(fields).size() == fields.size() && "project".equals(binding.get("resourceType"))
                && "update_metadata".equals(binding.get("operation"))
                && "PUT".equals(binding.get("method")) && binding.get("action") == null && "update".equals(binding.get("keyOperation"))
                && binding.get("rootId") instanceof Number root && binding.get("accountId") instanceof Number selected
                && root.longValue() > 0 && root.longValue() == selected.longValue();
    }
}
