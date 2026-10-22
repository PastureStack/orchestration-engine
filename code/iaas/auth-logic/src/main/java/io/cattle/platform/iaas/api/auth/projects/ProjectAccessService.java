package io.cattle.platform.iaas.api.auth.projects;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import java.util.ArrayList;
import java.util.Objects;

/** Live Project RBAC shared by normal resource access and Key attenuation. */
public class ProjectAccessService {
    private final AuthDao authDao;
    private final AccountDao accountDao;

    public ProjectAccessService(AuthDao authDao, AccountDao accountDao) {
        this.authDao = Objects.requireNonNull(authDao);
        this.accountDao = Objects.requireNonNull(accountDao);
    }

    /** Check live membership without granting response capabilities or object access. */
    public Account checkAccess(Account project, Policy policy) {
        return evaluate(project, policy) == null ? null : project;
    }

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
