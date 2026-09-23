package io.cattle.platform.iaas.api.auth.projects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.ProjectMember;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.ProjectMemberRecord;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class ProjectResourceManagerDefaultProjectTest {

    @Test
    public void addsStableAccountIdentityAsMemberOfSharedDefault() {
        AccountRecord account = account(42L, "OIDC user", "user");
        AccountRecord project = account(7L, "Renamed shared environment", ProjectConstants.TYPE);
        AtomicInteger ensures = new AtomicInteger();
        AtomicReference<Member> ensured = new AtomicReference<>();
        ProjectResourceManager manager = manager(project, Collections.emptyList(), ensures, ensured);

        Account result = manager.ensureDefaultProjectMembership(account, identities(
                new Identity("oidc_user", "subject-42", "OIDC user", null, null, "user42", true)));

        assertSame(project, result);
        assertEquals(1, ensures.get());
        assertEquals(ProjectConstants.RANCHER_ID, ensured.get().getExternalIdType());
        assertEquals("42", ensured.get().getExternalId());
        assertEquals(ProjectConstants.MEMBER, ensured.get().getRole());
    }

    @Test
    public void preservesExistingGroupRoleWithoutAddingBaselineMember() {
        AccountRecord account = account(42L, "OIDC user", "user");
        AccountRecord project = account(7L, "Default", ProjectConstants.TYPE);
        ProjectMemberRecord restrictedGroup = new ProjectMemberRecord();
        restrictedGroup.setProjectId(7L);
        restrictedGroup.setExternalIdType("oidc_group");
        restrictedGroup.setExternalId("qa-team");
        restrictedGroup.setRole("restricted");
        AtomicInteger ensures = new AtomicInteger();
        ProjectResourceManager manager = manager(project,
                Collections.<ProjectMember>singletonList(restrictedGroup), ensures, new AtomicReference<Member>());

        manager.ensureDefaultProjectMembership(account, identities(
                new Identity("oidc_group", "qa-team", "QA team", null, null, null, false)));

        assertEquals(0, ensures.get());
    }

    @Test
    public void preservesExistingDirectNoAccessRoleWithoutEscalation() {
        AccountRecord account = account(42L, "OIDC user", "user");
        AccountRecord project = account(7L, "Default", ProjectConstants.TYPE);
        ProjectMemberRecord noAccess = new ProjectMemberRecord();
        noAccess.setProjectId(7L);
        noAccess.setExternalIdType(ProjectConstants.RANCHER_ID);
        noAccess.setExternalId("42");
        noAccess.setRole("noaccess");
        AtomicInteger ensures = new AtomicInteger();
        ProjectResourceManager manager = manager(project,
                Collections.<ProjectMember>singletonList(noAccess), ensures, new AtomicReference<Member>());

        manager.ensureDefaultProjectMembership(account, identities(
                new Identity(ProjectConstants.RANCHER_ID, "42", "OIDC user", null, null, null, true)));

        assertEquals(0, ensures.get());
    }

    @Test
    public void failsClosedWhenSharedDefaultIsUnavailable() {
        AccountRecord account = account(42L, "OIDC user", "user");
        ProjectResourceManager manager = manager(null, Collections.emptyList(),
                new AtomicInteger(), new AtomicReference<Member>());

        try {
            manager.ensureDefaultProjectMembership(account, Collections.<Identity>emptySet());
            fail("Expected the missing shared Default to fail closed");
        } catch (ClientVisibleException e) {
            assertEquals(500, e.getStatus());
            assertEquals("DefaultProjectUnavailable", e.getCode());
        }
    }

    private ProjectResourceManager manager(Account project, List<? extends ProjectMember> existing,
                                           AtomicInteger ensures, AtomicReference<Member> ensured) {
        AuthDao authDao = AuthDao.class.cast(Proxy.newProxyInstance(
                AuthDao.class.getClassLoader(), new Class<?>[] {AuthDao.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "getAccountByUuid":
                        assertEquals(ProjectConstants.DEFAULT_PROJECT_UUID, args[0]);
                        return project;
                    case "ensureProjectMemberIfNoIdentityMembership":
                        if (existing.isEmpty()) {
                            ensures.incrementAndGet();
                            ensured.set((Member) args[2]);
                            return new ProjectMemberRecord();
                        }
                        return existing.get(0);
                    default:
                        return defaultValue(method.getReturnType());
                    }
                }));
        ProjectResourceManager manager = new ProjectResourceManager();
        manager.authDao = authDao;
        return manager;
    }

    private Set<Identity> identities(Identity identity) {
        Set<Identity> result = new HashSet<>();
        result.add(identity);
        return result;
    }

    private AccountRecord account(long id, String name, String kind) {
        AccountRecord result = new AccountRecord();
        result.setId(id);
        result.setName(name);
        result.setKind(kind);
        return result;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }
}
