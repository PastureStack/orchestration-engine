package io.cattle.platform.iaas.api.auth.description;

import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Description-only compare-and-update through the ordinary service manager. */
public class AiServiceDescriptionUpdateService extends AbstractJooqDao {
    @Inject ObjectManager objects;
    @Inject ResourceManagerLocator managers;
    @Inject List<ApiKeyAuditSink> auditSinks;
    @Inject AiKeyAuthorization keyAuthorization;

    public Map<String, Object> update(ApiRequest request) {
        String receipt = "aidescription-" + UUID.randomUUID();
        Long targetId = null;
        try {
            Map<String, Object> input = input(request);
            try { targetId = Long.valueOf(input.get("serviceId").toString()); }
            catch (NumberFormatException invalid) { throw error(422, "DescriptionInputInvalid"); }
            // ApiDelegateAdvice owns the surrounding write transaction. A locking read
            // must precede normal reads so the ordinary manager sees the same snapshot.
            // The lock is also respected by ordinary UI UPDATEs until the delegate commits.
            Service current = authorize(lockTarget(targetId), request);
            if (!Objects.equals(current.getId(), targetId)) throw error(404, "NotFound");
            if (!Objects.equals(current.getDescription(), input.get("expectedDescription"))) {
                throw error(409, "DescriptionConflict");
            }
            String description = (String) input.get("description");
            audit(request, receipt, targetId, true, "DescriptionUpdateAccepted");
            dispatch(current, description, request);
            Service after = lockTarget(targetId);
            if (after == null || !Objects.equals(after.getAccountId(), current.getAccountId())
                    || !Objects.equals(after.getDescription(), description)) {
                throw error(409, "DescriptionResultMismatch");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", receipt); result.put("serviceId", formattedServiceId(current));
            result.put("status", "succeeded"); result.put("verifiedResult", true);
            result.put("description", after.getDescription());
            return result;
        } catch (ClientVisibleException denied) {
            audit(request, receipt, targetId, false, denied.getCode());
            throw denied;
        }
    }

    protected void dispatch(Service target, String description, ApiRequest original) {
        ApiRequest update = new ApiRequest(original);
        update.setType(target.getKind()); update.setId(target.getId().toString());
        update.setMethod("PUT"); update.setAction(null); update.setLink(null);
        update.setRequestObject(new LinkedHashMap<>(Map.of("description", description)));
        Object result = AiKeyAuthorization.dispatch(original, update,
                () -> managers.getResourceManagerByType(target.getKind()).update(target.getKind(), update.getId(), update));
        if (result == null) throw error(404, "NotFound");
    }

    protected Service lockTarget(long id) {
        // Fail closed if called outside the existing API write transaction: releasing
        // an autocommit SELECT lock before dispatch would make the comparison racy.
        create().connection(connection -> {
            if (connection.getAutoCommit()) throw error(503, "DescriptionTransactionRequired");
        });
        return create().selectFrom(SERVICE).where(SERVICE.ID.eq(id)).forUpdate().fetchOne();
    }

    protected Policy actor(ApiRequest request) {
        Policy policy = keyAuthorization.actor(request);
        Account actor = objects.loadResource(Account.class, policy.getAuthenticatedAsAccountId());
        if (actor == null || actor.getRemoved() != null || !"active".equals(actor.getState())) throw error(403, "PermissionDenied");
        return policy;
    }

    protected Service authorize(Service target, ApiRequest request) {
        Policy policy = actor(request);
        if (target == null || target.getRemoved() != null || !Objects.equals(target.getAccountId(), policy.getAccountId())
                || !"service".equals(target.getKind())) throw error(404, "NotFound");
        keyAuthorization.authorizeService(request, target, "update");
        Schema schema = request.getSchemaFactory().getSchema(target.getKind());
        Field description = schema == null ? null : schema.getResourceFields().get("description");
        if (schema == null || !schema.getResourceMethods().containsAll(List.of("GET", "PUT"))
                || description == null || !description.isUpdate()) throw error(403, "PermissionDenied");
        ResourceManager manager = managers.getResourceManagerByType(target.getKind());
        Object visible = manager.getById(target.getKind(), target.getId().toString(), new ListOptions(request));
        if (!(visible instanceof Service service) || !Objects.equals(service.getId(), target.getId())
                || !Objects.equals(service.getAccountId(), target.getAccountId())
                || !Objects.equals(service.getKind(), target.getKind()) || service.getRemoved() != null) throw error(404, "NotFound");
        return target;
    }

    protected void audit(ApiRequest request, String receipt, Long serviceId, boolean allowed, String reason) {
        if (auditSinks == null || auditSinks.isEmpty()) throw error(503, "DescriptionAuditUnavailable");
        for (ApiKeyAuditSink sink : auditSinks) {
            sink.recordServiceDescriptionDecision(request, ApiUtils.getPolicy(), receipt, serviceId, allowed, reason);
        }
    }

    protected String formattedServiceId(Service target) {
        return ApiContext.getContext().getIdFormatter().formatId("service", target.getId()).toString();
    }

    private Map<String, Object> input(ApiRequest request) {
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        Object id = input.get("serviceId"), description = input.get("description"), expected = input.get("expectedDescription");
        if (!Set.of("serviceId", "description", "expectedDescription", "type").containsAll(input.keySet())
                || !(id instanceof String || id instanceof Number) || id.toString().isEmpty()
                || !(description instanceof String) || ((String) description).length() > 1024
                || !input.containsKey("expectedDescription") || expected != null && !(expected instanceof String)
                || expected instanceof String && ((String) expected).length() > 1024) throw error(422, "DescriptionInputInvalid");
        return input;
    }

    private static ClientVisibleException error(int status, String code) {
        return new ClientVisibleException(status, code, code, null);
    }
}
