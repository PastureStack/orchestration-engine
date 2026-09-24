package io.cattle.platform.iaas.api.auth.projects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.impl.DefaultPolicy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.model.ProjectMember;
import io.cattle.platform.core.model.tables.records.ProjectMemberRecord;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.identity.IdentityManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProjectMemberResourceManagerAuthorizationTest {

    private static final long TOKEN_ACCOUNT_ID = 42L;
    private static final long OTHER_PROJECT_ID = 77L;
    private static final long MEMBER_ID = 901L;

    @Before
    public void setUp() {
        ApiContext context = ApiContext.newContext();
        context.setApiRequest(new ApiRequest(null, null));
        context.setPolicy(new DefaultPolicy(TOKEN_ACCOUNT_ID, TOKEN_ACCOUNT_ID, "user42",
                Collections.singleton(new Identity("rancher_id", "42")), new NoPolicyOptions()));
    }

    @After
    public void tearDown() {
        ApiContext.remove();
    }

    @Test
    public void rejectsForeignProjectCollectionBeforeLoadingMembers() {
        AtomicInteger memberLoads = new AtomicInteger();
        ProjectMemberResourceManager manager = manager(false, memberLoads);

        try {
            manager.listInternal(null, "projectMember", criteria("projectId", String.valueOf(OTHER_PROJECT_ID)), null);
            fail("Expected a foreign project's members to be hidden");
        } catch (ClientVisibleException e) {
            assertEquals(404, e.getStatus());
        }

        assertEquals(0, memberLoads.get());
    }

    @Test
    public void listsMembersWhenTokenCanAccessRequestedProject() {
        AtomicInteger memberLoads = new AtomicInteger();
        ProjectMemberResourceManager manager = manager(true, memberLoads);

        List<?> identities = List.class.cast(manager.listInternal(null, "projectMember",
                criteria("projectId", String.valueOf(OTHER_PROJECT_ID)), null));

        assertEquals(1, memberLoads.get());
        assertEquals(1, identities.size());
        assertSame(expectedIdentity, identities.get(0));
    }

    @Test
    public void rejectsMalformedProjectIdBeforeLoadingMembers() {
        AtomicInteger memberLoads = new AtomicInteger();
        ProjectMemberResourceManager manager = manager(true, memberLoads);

        try {
            manager.listInternal(null, "projectMember", criteria("projectId", "not-a-project-id"), null);
            fail("Expected an invalid project ID to be hidden");
        } catch (ClientVisibleException e) {
            assertEquals(404, e.getStatus());
        }

        assertEquals(0, memberLoads.get());
    }

    @Test
    public void directMemberLookupStillRejectsForeignProject() {
        ProjectMemberResourceManager manager = manager(false, new AtomicInteger());

        try {
            manager.listInternal(null, "projectMember", criteria("id", String.valueOf(MEMBER_ID)), null);
            fail("Expected a foreign project member to be hidden");
        } catch (ClientVisibleException e) {
            assertEquals(404, e.getStatus());
        }
    }

    @Test
    public void directMemberLookupStillAllowsAccessibleProject() {
        ProjectMemberResourceManager manager = manager(true, new AtomicInteger());

        List<?> identities = List.class.cast(manager.listInternal(null, "projectMember",
                criteria("id", String.valueOf(MEMBER_ID)), null));

        assertEquals(1, identities.size());
        assertSame(expectedIdentity, identities.get(0));
    }

    @Test
    public void directMemberLookupHidesInactiveMembershipInAccessibleProject() {
        ProjectMemberResourceManager manager = manager(true, new AtomicInteger(),
                CommonStatesConstants.INACTIVE, null);

        try {
            manager.listInternal(null, "projectMember", criteria("id", String.valueOf(MEMBER_ID)), null);
            fail("Expected an inactive project member to be hidden");
        } catch (ClientVisibleException e) {
            assertEquals(404, e.getStatus());
        }
    }

    @Test
    public void directMemberLookupHidesRemovedMembershipInAccessibleProject() {
        ProjectMemberResourceManager manager = manager(true, new AtomicInteger(),
                CommonStatesConstants.ACTIVE, new Date());

        try {
            manager.listInternal(null, "projectMember", criteria("id", String.valueOf(MEMBER_ID)), null);
            fail("Expected a removed project member to be hidden");
        } catch (ClientVisibleException e) {
            assertEquals(404, e.getStatus());
        }
    }

    private final Identity expectedIdentity = new Identity("rancher_id", "member-77");

    private ProjectMemberResourceManager manager(boolean canAccess, AtomicInteger memberLoads) {
        return manager(canAccess, memberLoads, CommonStatesConstants.ACTIVE, null);
    }

    private ProjectMemberResourceManager manager(boolean canAccess, AtomicInteger memberLoads,
                                                 String state, Date removed) {
        ProjectMemberRecord member = new ProjectMemberRecord();
        member.setId(MEMBER_ID);
        member.setProjectId(OTHER_PROJECT_ID);
        member.setExternalIdType("rancher_id");
        member.setExternalId("member-77");
        member.setState(state);
        member.setRemoved(removed);

        AuthDao authDao = AuthDao.class.cast(Proxy.newProxyInstance(
                AuthDao.class.getClassLoader(), new Class<?>[] {AuthDao.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "hasAccessToProject":
                        assertEquals(OTHER_PROJECT_ID, args[0]);
                        assertEquals(TOKEN_ACCOUNT_ID, args[1]);
                        assertEquals(false, args[2]);
                        return canAccess;
                    case "getActiveProjectMembers":
                        assertEquals(OTHER_PROJECT_ID, args[0]);
                        memberLoads.incrementAndGet();
                        return Collections.singletonList(member);
                    case "getProjectMember":
                        assertEquals(MEMBER_ID, args[0]);
                        return member;
                    default:
                        throw new AssertionError("Unexpected AuthDao call: " + method.getName());
                    }
                }));

        ProjectMemberResourceManager manager = new ProjectMemberResourceManager();
        manager.authDao = authDao;
        manager.identityManager = new IdentityManager() {
            @Override
            public Identity projectMemberToIdentity(ProjectMember projectMember) {
                return expectedIdentity;
            }
        };
        return manager;
    }

    private Map<Object, Object> criteria(String key, String value) {
        Map<Object, Object> criteria = new HashMap<>();
        criteria.put(key, value);
        return criteria;
    }
}
