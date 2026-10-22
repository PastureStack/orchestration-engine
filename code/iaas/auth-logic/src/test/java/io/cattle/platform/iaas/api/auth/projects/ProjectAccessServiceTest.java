package io.cattle.platform.iaas.api.auth.projects;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.Identity;
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
    @Test public void ownerCapabilitiesAreIdempotentAcrossManagerLookups() {
        when(dao.isProjectOwner(5L, 1L, false, policy.getIdentities())).thenReturn(true);
        assertSame(project, access.authorize(project, policy));
        assertSame(project, access.authorize(project, policy));
        assertEquals(List.of(ProjectConstants.OWNER, ProjectConstants.SET_MEMBERS), ApiContext.getContext().getCapabilities(project));
        verify(dao, times(2)).hasAccessToProject(5L, 1L, false, policy.getIdentities());
        verify(dao, times(2)).isProjectOwner(5L, 1L, false, policy.getIdentities());
        verify(dao, times(2)).canSetProjectMembers(5L, 1L, false, policy.getIdentities());
        verify(accounts, times(2)).isActiveAccount(project);
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
        assertNull(access.authorize(null, policy)); assertNull(access.checkAccess(null, policy)); project.setKind("user");
        assertNull(access.authorize(project, policy)); assertNull(access.checkAccess(project, policy)); verifyNoInteractions(dao, accounts);
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
    @Test public void pureCheckPreservesExistingCapabilitiesAndRequestGrants() {
        var previous = new AccountRecord(); previous.setId(6L); previous.setKind("project");
        policy.grantObjectAccess(previous);
        var context = ApiContext.getContext();
        var capabilities = new java.util.ArrayList<>(List.of("existing-formatter-capability"));
        context.setCapabilities(project, capabilities);
        Object whitelist = context.getApiRequest().getAttribute("whitelist");
        for (String role : List.of("member", "setMembers", "owner")) {
            when(dao.isProjectOwner(5L, 1L, false, policy.getIdentities())).thenReturn(role.equals("owner"));
            when(dao.canSetProjectMembers(5L, 1L, false, policy.getIdentities())).thenReturn(role.equals("setMembers"));
            assertSame(project, access.checkAccess(project, policy));
            assertSame(capabilities, context.getCapabilities(project));
            assertEquals(List.of("existing-formatter-capability"), capabilities);
            assertSame(whitelist, context.getApiRequest().getAttribute("whitelist"));
            assertSame(previous, policy.authorizeObject(previous));
            assertNull(policy.authorizeObject(project));
        }
    }
    @Test public void pureCheckRejectsRevokedMembershipDespiteEarlierManagerGrant() {
        assertSame(project, access.authorize(project, policy));
        var context = ApiContext.getContext();
        var capabilities = context.getCapabilities(project);
        Object whitelist = context.getApiRequest().getAttribute("whitelist");
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(false);
        assertNull(access.checkAccess(project, policy));
        assertSame(capabilities, context.getCapabilities(project));
        assertSame(whitelist, context.getApiRequest().getAttribute("whitelist"));
        verify(dao, times(2)).hasAccessToProject(5L, 1L, false, policy.getIdentities());
        verify(dao, times(1)).isProjectOwner(5L, 1L, false, policy.getIdentities());
    }
    @Test public void pureCheckPreservesInactiveOwnerBoundaryWithoutGranting() {
        when(accounts.isActiveAccount(project)).thenReturn(false);
        assertNull(access.checkAccess(project, policy));
        when(dao.isProjectOwner(5L, 1L, false, policy.getIdentities())).thenReturn(true);
        assertSame(project, access.checkAccess(project, policy));
        assertNull(policy.authorizeObject(project));
        assertNull(ApiContext.getContext().getCapabilities(project));
    }
    @Test public void pureCheckPreservesAllAccountIdentityInputsAndEvaluationOrder() {
        Policy all = mock(Policy.class);
        Set<Identity> identities = Set.of(mock(Identity.class));
        when(all.getAccountId()).thenReturn(9L);
        when(all.getIdentities()).thenReturn(identities);
        when(all.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS)).thenReturn(true);
        when(dao.hasAccessToProject(5L, 9L, true, identities)).thenReturn(true);
        when(dao.isProjectOwner(5L, 9L, true, identities)).thenReturn(true);
        assertSame(project, access.checkAccess(project, all));
        var order = inOrder(dao, accounts);
        order.verify(dao).hasAccessToProject(5L, 9L, true, identities);
        order.verify(dao).isProjectOwner(5L, 9L, true, identities);
        order.verify(dao).canSetProjectMembers(5L, 9L, true, identities);
        order.verify(accounts).isActiveAccount(project);
        verifyNoMoreInteractions(dao, accounts);
        verify(all, never()).grantObjectAccess(any());
    }
    @Test public void pureCheckDoesNotCreateBackgroundContextOrHttpRequest() {
        ApiContext.remove();
        assertSame(project, access.checkAccess(project, policy));
        assertNull(ApiContext.getContext());
        var context = ApiContext.newContext();
        assertSame(project, access.checkAccess(project, policy));
        assertNull(context.getApiRequest());
        assertNull(context.getCapabilities(project));
        assertNull(policy.authorizeObject(project));
    }
}
