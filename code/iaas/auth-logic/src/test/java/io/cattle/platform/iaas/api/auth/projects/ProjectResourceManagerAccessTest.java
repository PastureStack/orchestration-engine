package io.cattle.platform.iaas.api.auth.projects;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

public class ProjectResourceManagerAccessTest {
    @After public void cleanup() { ApiContext.remove(); }
    @Test public void idUuidAndCollectionPreserveRoleCapabilitiesAndMembershipBoundary() {
        for (String role : List.of("member", "setMembers", "owner", "noAccess")) {
            var context = ApiContext.newContext();
            var principal = new AccountRecord(); principal.setId(1L); principal.setKind("user");
            var project = new AccountRecord(); project.setId(5L); project.setKind("project");
            project.setUuid("project-five"); project.setName("Named environment");
            Policy policy = new AccountPolicy(principal, principal, Set.of(), new NoPolicyOptions());
            var dao = mock(AuthDao.class); var accounts = mock(AccountDao.class); var objects = mock(ObjectManager.class);
            when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(!role.equals("noAccess"));
            when(dao.isProjectOwner(5L, 1L, false, policy.getIdentities())).thenReturn(role.equals("owner"));
            when(dao.canSetProjectMembers(5L, 1L, false, policy.getIdentities())).thenReturn(role.equals("setMembers"));
            when(accounts.isActiveAccount(project)).thenReturn(true);
            when(objects.loadResource(io.cattle.platform.core.model.Account.class, "5")).thenReturn(project);
            when(dao.getAccountByUuid("project-five")).thenReturn(project);
            when(dao.getAccessibleProjects(policy.getIdentities(), false, 1L)).thenReturn(role.equals("noAccess") ? List.of() : List.of(project));
            var manager = new ProjectResourceManager(); manager.setObjectManager(objects); manager.authDao = dao;
            manager.projectAccess = new ProjectAccessService(dao, accounts);
            for (String lookup : List.of("id", "uuid", "collection")) {
                var request = new ApiRequest(null, null); request.setRequestParams(Map.of());
                context.setApiRequest(request); context.setPolicy(policy);
                Map<Object, Object> criteria = lookup.equals("id") ? Map.of("id", "5")
                        : lookup.equals("uuid") ? Map.of("uuid", "project-five") : Map.of();
                Object result = manager.listInternal(null, "project", criteria, null);
                if (lookup.equals("uuid")) assertSame(role, role.equals("noAccess") ? null : project, result);
                else assertEquals(role, role.equals("noAccess") && lookup.equals("collection") ? List.of()
                        : java.util.Collections.singletonList(role.equals("noAccess") ? null : project), result);
                if (role.equals("noAccess")) {
                    assertNull(policy.authorizeObject(project));
                } else {
                    assertSame(project, policy.authorizeObject(project));
                    var expected = role.equals("owner") ? List.of(ProjectConstants.OWNER, ProjectConstants.SET_MEMBERS)
                            : role.equals("setMembers") ? List.of(ProjectConstants.SET_MEMBERS) : List.of();
                    assertEquals(role, expected, context.getCapabilities(project));
                }
            }
        }
    }
}
