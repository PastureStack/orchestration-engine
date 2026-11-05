package io.cattle.platform.iaas.api.auth.projects;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Live Project RBAC shared by normal resource access and Key attenuation. */
public class ProjectAccessService {
    private final AuthDao authDao;
    private final AccountDao accountDao;
    private static final ThreadLocal<UpdateScope> UPDATE = new ThreadLocal<>();
    private record UpdateScope(ApiRequest request, Policy owner, long projectId, String uuid, Set<String> fields) { }

    public ProjectAccessService(AuthDao authDao, AccountDao accountDao) {
        this.authDao = Objects.requireNonNull(authDao);
        this.accountDao = Objects.requireNonNull(accountDao);
    }

    /** Check live membership without granting response capabilities or object access. */
    public Account checkAccess(Account project, Policy policy) {
        return evaluate(project, policy) == null ? null : project;
    }

    /** The native manager's owner/schema check, without request-local object grants. */
    public void authorizeUpdate(Account project, Policy owner, Schema schema, Collection<String> fields) {
        if (project == null || project.getId() == null || !ProjectConstants.TYPE.equals(project.getKind())
                || project.getRemoved() != null || owner == null || schema == null || fields == null
                || schema.getResourceMethods() == null || !schema.getResourceMethods().contains("PUT")
                || !authDao.isProjectOwner(project.getId(), owner.getAccountId(),
                        owner.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS), owner.getIdentities())) denied();
        for (String field : fields) {
            if (schema.getResourceFields() == null || schema.getResourceFields().get(field) == null
                    || !schema.getResourceFields().get(field).isUpdate()) denied();
        }
    }

    /** Only ProjectResourceManager can open this scope after its ordinary owner check. */
    <T> T withUpdate(Account project, Policy owner, ApiRequest request, Supplier<T> update) {
        var context = ApiContext.getContext();
        if (context == null || context.getApiRequest() != request || context.getPolicy() != owner
                || !"project".equals(request.getType()) || !"PUT".equals(request.getMethod())
                || request.getAction() != null || request.getLink() != null || project == null || project.getId() == null
                || !project.getId().toString().equals(request.getId()) || project.getUuid() == null || project.getUuid().isBlank()
                || !(request.getRequestObject() instanceof Map<?, ?>)) denied();
        Map<?, ?> body = (Map<?, ?>) request.getRequestObject();
        Set<String> fields = body.keySet().stream().filter(k -> !"type".equals(k)).map(k -> {
            if (!(k instanceof String field)) { denied(); return ""; }
            return field;
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
        authorizeUpdate(project, owner, request.getSchemaFactory().getSchema("project"), fields);
        UpdateScope previous = UPDATE.get(); UPDATE.set(new UpdateScope(request, owner, project.getId(), project.getUuid(), fields));
        try { return update.get(); }
        finally { if (previous == null) UPDATE.remove(); else UPDATE.set(previous); }
    }

    /** Native Account identity and actual changed fields; never copied from client provenance flags. */
    public Map<String, Object> captureUpdate(ApiRequest request, Policy owner, LaunchConfiguration process) {
        UpdateScope scope = UPDATE.get();
        if (scope == null) return Map.of();
        if (scope.request() != request || scope.owner() != owner) denied();
        if (!"account".equals(process.getResourceType()) || !"account.update".equals(process.getProcessName())
                || !Long.toString(scope.projectId()).equals(process.getResourceId()) || process.getAccountId() != null) return Map.of();
        List<String> fields = ProcessAuthorization.projectUpdateFields(process);
        if (fields.isEmpty() || !scope.fields().containsAll(fields)) denied();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", scope.projectId()); result.put("projectUpdateUuid", scope.uuid()); result.put("projectUpdateFields", fields);
        return Map.copyOf(result);
    }

    /** One captured native Account.update only. No authority is granted to arbitrary project children. */
    public void authorizeQueuedUpdate(Object root, Object child, Policy owner, Schema schema,
            LaunchConfiguration process, Map<String, Object> metadata) {
        if (!(root instanceof Account) || !(child instanceof Account)
                || !ProcessAuthorization.executionBound(process) || !ProcessAuthorization.projectUpdateExecution(process, metadata)) denied();
        Account project = (Account) root; Account actual = (Account) child;
        if (!Objects.equals(project.getId(), actual.getId()) || !Objects.equals(project.getUuid(), actual.getUuid())
                || !ProjectConstants.TYPE.equals(actual.getKind()) || actual.getRemoved() != null
                || !Objects.equals(project.getUuid(), metadata.get("projectUpdateUuid"))
                || !(metadata.get("accountId") instanceof Number account) || !Objects.equals(project.getId(), account.longValue())) denied();
        authorizeUpdate(project, owner, schema, ProcessAuthorization.projectUpdateFields(process));
    }

    private static void denied() { throw new ClientVisibleException(403, "OwnerPermissionDenied"); }

    private record ProjectAccessRights(boolean owner, boolean setMembers) { }

    private ProjectAccessRights evaluate(Account project, Policy policy) {
        if (project == null || !ProjectConstants.TYPE.equalsIgnoreCase(project.getKind())) return null;
        if (!authDao.hasAccessToProject(project.getId(), policy.getAccountId(),
                policy.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS), policy.getIdentities())) return null;
        boolean owner = authDao.isProjectOwner(project.getId(), policy.getAccountId(),
                policy.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS), policy.getIdentities());
        boolean setMembers = authDao.canSetProjectMembers(project.getId(), policy.getAccountId(),
                policy.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS), policy.getIdentities());
        if (!accountDao.isActiveAccount(project) && !owner) return null;
        return new ProjectAccessRights(owner, setMembers);
    }

    public Account authorize(Account project, Policy policy) {
        var rights = evaluate(project, policy);
        if (rights == null) return null;

        // Background authorization has no request-local grants or capabilities.
        // Never create a synthetic context to make attenuation succeed.
        var context = ApiContext.getContext();
        if (context == null || context.getApiRequest() == null) return project;

        // Only the normal manager materializes formatter capabilities and grants.
        // Repeated manager authorization must remain idempotent.
        if (rights.owner()) {
            addCapability(project, ProjectConstants.OWNER);
            addCapability(project, ProjectConstants.SET_MEMBERS);
        } else if (rights.setMembers()) {
            addCapability(project, ProjectConstants.SET_MEMBERS);
        } else {
            ApiContext.getContext().setCapabilities(project, new ArrayList<String>());
        }
        policy.grantObjectAccess(project);
        return project;
    }

    private void addCapability(Account project, String capability) {
        var context = ApiContext.getContext();
        var existing = context.getCapabilities(project);
        if (existing == null || !existing.contains(capability)) context.addCapability(project, capability);
    }
}
