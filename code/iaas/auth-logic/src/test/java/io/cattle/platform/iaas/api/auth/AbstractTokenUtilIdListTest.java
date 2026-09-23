package io.cattle.platform.iaas.api.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.netflix.config.ConfigurationManager;
import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.projects.ProjectResourceManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class AbstractTokenUtilIdListTest {

    @Test
    public void identitiesUseCheckedIdListBoundary() {
        TestTokenUtil util = new TestTokenUtil();
        Map<String, Object> jsonData = new HashMap<String, Object>();
        jsonData.put(AbstractTokenUtil.ID_LIST, Arrays.asList("user:alice", "team:devs", "invalid"));

        Set<Identity> identities = util.identities(jsonData);

        assertEquals(2, identities.size());
        assertTrue(identities.contains(new Identity("user", "alice")));
        assertTrue(identities.contains(new Identity("team", "devs")));
    }

    @Test
    public void stringListRejectsNonListTokenFieldLikeLegacyCast() {
        try {
            AbstractTokenUtil.stringList("user:alice");
            fail("Expected non-list token field to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.util.List"));
        }
    }

    @Test
    public void stringListRejectsNonStringElementsLikeLegacyEnhancedForLoop() {
        try {
            AbstractTokenUtil.stringList(Arrays.asList("user:alice", 1));
            fail("Expected non-string token id to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.lang.String"));
        }
    }

    @Test
    public void isAllowedReceivesCheckedIdList() {
        TestTokenUtil util = new TestTokenUtil();
        Map<String, Object> jsonData = new HashMap<String, Object>();
        jsonData.put(AbstractTokenUtil.ID_LIST, Arrays.asList("user:alice"));

        assertTrue(util.isAllowed(jsonData));
        assertEquals(Arrays.asList("user:alice"), util.seenIdList);
    }

    @Test
    public void stableAccountAddsOnlyLinksOwnedByTheActiveProvider() {
        TestTokenUtil util = new TestTokenUtil();
        Map<String, Object> link = new HashMap<String, Object>();
        link.put("provider", "oidcconfig");

        assertTrue(util.linkMatchesProvider(link, "OIDCConfig"));
        assertFalse(util.linkMatchesProvider(link, "localAuthConfig"));
        assertFalse(util.linkMatchesProvider(new HashMap<String, Object>(), "oidcconfig"));
        assertFalse(util.linkMatchesProvider(link, null));
    }

    @Test
    public void restrictedModeSeesAResolvedStableAccountBeforeProjectAccessCheck() {
        assertStableIdentityAtAuthorization(AbstractTokenUtil.RESTRICTED_ACCESSMODE, true);
    }

    @Test
    public void requiredModeDoesNotTurnStableMembershipIntoAnAllowlistBypass() {
        assertStableIdentityAtAuthorization(AbstractTokenUtil.REQUIRED_ACCESSMODE, false);
    }

    @Test
    public void sharedProvisioningReconcilesEveryLogin() {
        ProvisioningProbe manager = new ProvisioningProbe();
        TestTokenUtil util = provisioningUtil("shared", true, false, manager);
        AccountRecord account = account(42L, "OIDC user", "user");
        Identity user = new Identity("oidc_user", "subject-42");
        Set<Identity> identities = new HashSet<Identity>(Collections.singleton(user));

        util.provision(account, user, identities, false);

        assertEquals(1, manager.shared.get());
        assertEquals(0, manager.personal.get());
    }

    @Test
    public void personalProvisioningOnlyCreatesOnFirstLoginWithoutAccess() {
        ProvisioningProbe manager = new ProvisioningProbe();
        TestTokenUtil util = provisioningUtil("personal", true, false, manager);
        AccountRecord account = account(42L, "OIDC user", "user");
        Identity user = new Identity("oidc_user", "subject-42");
        Set<Identity> identities = new HashSet<Identity>(Collections.singleton(user));

        util.provision(account, user, identities, true);
        util.provision(account, user, identities, false);

        assertEquals(1, manager.personal.get());
        assertEquals(0, manager.shared.get());

        util = provisioningUtil("personal", true, true, manager);
        util.provision(account, user, identities, true);
        assertEquals(1, manager.personal.get());
    }

    @Test
    public void noneAndDisabledProvisioningDoNotCreateMemberships() {
        ProvisioningProbe manager = new ProvisioningProbe();
        AccountRecord account = account(42L, "OIDC user", "user");
        Identity user = new Identity("oidc_user", "subject-42");
        Set<Identity> identities = new HashSet<Identity>(Collections.singleton(user));

        provisioningUtil("none", true, false, manager).provision(account, user, identities, true);
        provisioningUtil("shared", false, false, manager).provision(account, user, identities, true);

        assertEquals(0, manager.shared.get());
        assertEquals(0, manager.personal.get());
    }

    @Test
    public void invalidProvisioningModeFailsClosed() {
        TestTokenUtil util = provisioningUtil("surprise", true, false, new ProvisioningProbe());
        try {
            util.provision(account(42L, "OIDC user", "user"),
                    new Identity("oidc_user", "subject-42"), Collections.<Identity>emptySet(), true);
            fail("Expected invalid provisioning mode to fail closed");
        } catch (ClientVisibleException e) {
            assertEquals(500, e.getStatus());
            assertEquals("InvalidDefaultProjectProvisioning", e.getCode());
        }
    }

    private TestTokenUtil provisioningUtil(String mode, boolean enabled, boolean hasAccess,
            ProvisioningProbe manager) {
        TestTokenUtil util = new TestTokenUtil();
        util.provisioning = mode;
        util.provisioningEnabled = enabled;
        util.projectResourceManager = manager;
        util.authDao = (AuthDao) Proxy.newProxyInstance(
                AbstractTokenUtilIdListTest.class.getClassLoader(), new Class<?>[]{AuthDao.class},
                (proxy, method, args) -> {
                    if ("hasAccessToAnyProject".equals(method.getName())) {
                        return hasAccess;
                    }
                    return null;
                });
        return util;
    }

    private void assertStableIdentityAtAuthorization(String mode, boolean expected) {
        ConfigurationManager.getConfigInstance().setProperty(SecurityConstants.SECURITY_SETTING, true);
        ConfigurationManager.getConfigInstance().setProperty(SecurityConstants.AUTH_PROVIDER_SETTING, "oidcconfig");
        try {
            AccountRecord account = new AccountRecord();
            account.setId(42L);
            account.setName("matrix-user");
            account.setState("active");

            TestTokenUtil util = new TestTokenUtil(mode);
            util.setDependencies(authDao(account), accountDao(account));
            util.stopAfterAuthorization = true;
            Set<Identity> identities = new HashSet<Identity>();
            Identity user = new Identity("oidc_user", "matrix-subject");
            identities.add(user);

            try {
                util.getOrCreateAccount(user, identities, null);
                fail("Expected the authorization probe to stop the login flow");
            } catch (AuthorizationProbe expectedProbe) {
                // The assertion below inspects the exact identity set observed
                // by the access decision, before later account mutation.
            }

            assertEquals(expected, util.seenIdentities.contains(
                    new Identity(ProjectConstants.RANCHER_ID, "42")));
        } finally {
            ConfigurationManager.getConfigInstance().clearProperty(SecurityConstants.SECURITY_SETTING);
            ConfigurationManager.getConfigInstance().clearProperty(SecurityConstants.AUTH_PROVIDER_SETTING);
        }
    }

    private static AccountRecord account(long id, String name, String kind) {
        AccountRecord result = new AccountRecord();
        result.setId(id);
        result.setName(name);
        result.setKind(kind);
        result.setState("active");
        return result;
    }

    private static AuthDao authDao(Account expected) {
        return (AuthDao) Proxy.newProxyInstance(
                AbstractTokenUtilIdListTest.class.getClassLoader(),
                new Class<?>[]{AuthDao.class},
                (proxy, method, args) -> {
                    if ("getAccountByIdentityLink".equals(method.getName())) {
                        return expected;
                    }
                    if ("getIdentityLinks".equals(method.getName())) {
                        return Collections.emptyList();
                    }
                    return null;
                });
    }

    private static AccountDao accountDao(Account expected) {
        return (AccountDao) Proxy.newProxyInstance(
                AbstractTokenUtilIdListTest.class.getClassLoader(),
                new Class<?>[]{AccountDao.class},
                (proxy, method, args) -> {
                    if ("isActiveAccount".equals(method.getName())) {
                        return args != null && args.length == 1 && args[0] == expected;
                    }
                    Class<?> type = method.getReturnType();
                    if (type.equals(boolean.class)) {
                        return false;
                    }
                    if (type.equals(int.class)) {
                        return 0;
                    }
                    if (type.equals(long.class)) {
                        return 0L;
                    }
                    return null;
                });
    }

    private static class AuthorizationProbe extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static class TestTokenUtil extends AbstractTokenUtil {
        private List<String> seenIdList;
        private Set<Identity> seenIdentities;
        private String mode = REQUIRED_ACCESSMODE;
        private boolean stopAfterAuthorization;
        private String provisioning = "shared";
        private boolean provisioningEnabled = true;

        TestTokenUtil() {
        }

        TestTokenUtil(String mode) {
            this.mode = mode;
        }

        void setDependencies(AuthDao authDao, AccountDao accountDao) {
            this.authDao = authDao;
            this.accountDao = accountDao;
        }

        void provision(Account account, Identity user, Set<Identity> identities, boolean firstLogin) {
            provisionDefaultProject(account, user, identities, firstLogin);
        }

        @Override
        protected boolean defaultProjectCreationEnabled() {
            return provisioningEnabled;
        }

        @Override
        protected String defaultProjectProvisioning() {
            return provisioning;
        }

        @Override
        public boolean isAllowed(List<String> idList, Set<Identity> identities) {
            seenIdentities = new HashSet<Identity>(identities);
            if (stopAfterAuthorization) {
                throw new AuthorizationProbe();
            }
            return super.isAllowed(idList, identities);
        }

        @Override
        protected boolean isWhitelisted(List<String> idList) {
            seenIdList = idList;
            return true;
        }

        @Override
        protected String accessMode() {
            return mode;
        }

        @Override
        protected String accessToken() {
            return null;
        }

        @Override
        protected void postAuthModification(Account account) {
        }

        @Override
        public String tokenType() {
            return "testjwt";
        }

        @Override
        public String userType() {
            return "user";
        }

        @Override
        public boolean createAccount() {
            return false;
        }

        @Override
        public String getName() {
            return "test";
        }
    }

    private static class ProvisioningProbe extends ProjectResourceManager {
        private final AtomicInteger shared = new AtomicInteger();
        private final AtomicInteger personal = new AtomicInteger();

        @Override
        public Account ensureDefaultProjectMembership(Account account, Set<Identity> identities) {
            shared.incrementAndGet();
            return account;
        }

        @Override
        public Account createProjectForUser(Identity identity) {
            personal.incrementAndGet();
            return account(99L, "Personal", ProjectConstants.TYPE);
        }
    }
}
