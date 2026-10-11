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
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

public class ProjectAccessServiceTest {
    AuthDao dao;
    AccountDao accounts;
    ProjectAccessService access;
    AccountRecord project;
    Policy policy;
    @Before public void setup() {
        var context = ApiContext.newContext();
        context.setApiRequest(new ApiRequest(null, null));
        dao = mock(AuthDao.class); accounts = mock(AccountDao.class);
        access = new ProjectAccessService(dao, accounts);
        var principal = new AccountRecord(); principal.setId(1L); principal.setKind("user");
        project = new AccountRecord(); project.setId(5L); project.setKind("project");
        policy = new AccountPolicy(principal, principal, Set.of(), new NoPolicyOptions());
        context.setPolicy(policy);
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(true);
        when(accounts.isActiveAccount(project)).thenReturn(true);
    }
    @After public void cleanup() { ApiContext.remove(); }
    @Test public void ordinaryMemberCanReadWithoutOwnerCapabilities() {
        assertNull(policy.authorizeObject(project));
        assertSame(project, access.authorize(project, policy));
        assertSame(project, policy.authorizeObject(project));
        assertEquals(List.of(), ApiContext.getContext().getCapabilities(project));
    }
    @Test public void ownerCapabilitiesAreIdempotentAcrossGuardAndManager() {
        when(dao.isProjectOwner(5L, 1L, false, policy.getIdentities())).thenReturn(true);
        assertSame(project, access.authorize(project, policy));
        assertSame(project, access.authorize(project, policy));
        assertEquals(List.of(ProjectConstants.OWNER, ProjectConstants.SET_MEMBERS), ApiContext.getContext().getCapabilities(project));
    }
    @Test public void setMembersDoesNotGrantOwner() {
        when(dao.canSetProjectMembers(5L, 1L, false, policy.getIdentities())).thenReturn(true);
        access.authorize(project, policy); access.authorize(project, policy);
        assertEquals(List.of(ProjectConstants.SET_MEMBERS), ApiContext.getContext().getCapabilities(project));
    }
    @Test public void inaccessibleProjectNeverReceivesRequestGrant() {
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(false);
        assertNull(access.authorize(project, policy));
        assertNull(policy.authorizeObject(project));
        assertNull(ApiContext.getContext().getCapabilities(project));
        verifyNoInteractions(accounts);
    }
    @Test public void previouslyGrantedProjectStillRequiresFreshMembership() {
        assertSame(project, access.authorize(project, policy));
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(false);
        assertNull(access.authorize(project, policy));
        verify(dao, times(2)).hasAccessToProject(5L, 1L, false, policy.getIdentities());
    }
    @Test public void inactiveProjectRequiresExistingOwnerAuthority() {
        when(accounts.isActiveAccount(project)).thenReturn(false);
        assertNull(access.authorize(project, policy));
        assertNull(policy.authorizeObject(project));
        when(dao.isProjectOwner(5L, 1L, false, policy.getIdentities())).thenReturn(true);
        assertSame(project, access.authorize(project, policy));
    }
    @Test public void nonProjectAndMissingProjectDoNotConsultMembership() {
        assertNull(access.authorize(null, policy)); project.setKind("user");
        assertNull(access.authorize(project, policy)); verifyNoInteractions(dao, accounts);
    }
    @Test public void backgroundChecksMembershipWithoutCreatingRequestGrant() {
        ApiContext.remove();
        assertSame(project, access.authorize(project, policy));
        assertNull(ApiContext.getContext());
        assertNull(policy.authorizeObject(project));
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(false);
        assertNull(access.authorize(project, policy));
        assertNull(ApiContext.getContext());
    }
    @Test public void contextWithoutHttpRequestDoesNotCreateGrant() {
        ApiContext.getContext().setApiRequest(null);
        assertSame(project, access.authorize(project, policy));
        assertNull(ApiContext.getContext().getApiRequest());
        assertNull(ApiContext.getContext().getCapabilities(project));
    }
}
