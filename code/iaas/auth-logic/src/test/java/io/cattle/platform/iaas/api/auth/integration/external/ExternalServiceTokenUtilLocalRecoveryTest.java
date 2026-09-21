package io.cattle.platform.iaas.api.auth.integration.external;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.netflix.config.ConfigurationManager;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.iaas.api.auth.AbstractTokenUtil;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.integration.local.LocalAuthConstants;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ExternalServiceTokenUtilLocalRecoveryTest {

    private TestTokenUtil tokenUtil;
    private AccountRecord account;

    @Before
    public void setUp() {
        ConfigurationManager.getConfigInstance().setProperty(
                SecurityConstants.SECURITY_SETTING, true);
        ConfigurationManager.getConfigInstance().setProperty(
                LocalAuthConstants.RECOVERY_ENABLED_SETTING, true);
        ConfigurationManager.getConfigInstance().setProperty(
                ServiceAuthConstants.ACCESSMODE_SETTING,
                AbstractTokenUtil.REQUIRED_ACCESSMODE);
        ConfigurationManager.getConfigInstance().setProperty(
                ServiceAuthConstants.ALLOWED_IDENTITIES_SETTING,
                "oidc_group:allowed-group");
        ConfigurationManager.getConfigInstance().setProperty(
                ServiceAuthConstants.IDENTITY_SEPARATOR_SETTING, "#oidc#");

        account = new AccountRecord();
        account.setId(42L);
        account.setKind(AccountConstants.ADMIN_KIND);
        account.setState("active");

        tokenUtil = new TestTokenUtil();
        tokenUtil.setDependencies(authDao(account), accountDao(account));
    }

    @After
    public void tearDown() {
        ConfigurationManager.getConfigInstance().clearProperty(
                SecurityConstants.SECURITY_SETTING);
        ConfigurationManager.getConfigInstance().clearProperty(
                LocalAuthConstants.RECOVERY_ENABLED_SETTING);
        ConfigurationManager.getConfigInstance().clearProperty(
                ServiceAuthConstants.ACCESSMODE_SETTING);
        ConfigurationManager.getConfigInstance().clearProperty(
                ServiceAuthConstants.ALLOWED_IDENTITIES_SETTING);
        ConfigurationManager.getConfigInstance().clearProperty(
                ServiceAuthConstants.IDENTITY_SEPARATOR_SETTING);
    }

    @Test
    public void requiredModeAllowsOnlyVerifiedLocalRecoveryAdministrator() {
        assertTrue(tokenUtil.allowed(localRecoveryPayload()));

        account.setKind(AccountConstants.USER_KIND);
        assertForbidden(localRecoveryPayload());

        account.setKind(AccountConstants.ADMIN_KIND);
        account.setState("inactive");
        assertForbidden(localRecoveryPayload());
    }

    @Test
    public void externalAndMalformedPayloadsNeverGainTheRecoveryBypass() {
        Map<String, Object> external = localRecoveryPayload();
        external.put(AbstractTokenUtil.TOKEN, "externaljwt");
        assertForbidden(external);

        Map<String, Object> missingPrincipal = localRecoveryPayload();
        missingPrincipal.remove(AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID);
        assertForbidden(missingPrincipal);

        ConfigurationManager.getConfigInstance().setProperty(
                LocalAuthConstants.RECOVERY_ENABLED_SETTING, false);
        assertForbidden(localRecoveryPayload());
    }

    @Test
    public void normalExternalSessionsStillUseTheRequiredAllowList() {
        Map<String, Object> allowed = new HashMap<>();
        allowed.put(AbstractTokenUtil.TOKEN, "externaljwt");
        allowed.put(AbstractTokenUtil.ID_LIST,
                Arrays.asList("oidc_group:allowed-group"));
        assertTrue(tokenUtil.allowed(allowed));

        Map<String, Object> denied = new HashMap<>();
        denied.put(AbstractTokenUtil.TOKEN, "externaljwt");
        denied.put(AbstractTokenUtil.ID_LIST,
                Arrays.asList("oidc_group:other-group"));
        assertForbidden(denied);
    }

    private Map<String, Object> localRecoveryPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put(AbstractTokenUtil.TOKEN, LocalAuthConstants.JWT);
        payload.put(AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID, "42");
        payload.put(AbstractTokenUtil.ID_LIST,
                Arrays.asList("rancher_id:1a42"));
        return payload;
    }

    private void assertForbidden(Map<String, Object> payload) {
        try {
            tokenUtil.allowed(payload);
            fail("Expected required access policy to reject the token");
        } catch (ClientVisibleException expected) {
            assertEquals(403, expected.getStatus());
            assertEquals("AuthError", expected.getCode());
        }
    }

    private static AuthDao authDao(Account expected) {
        return (AuthDao) Proxy.newProxyInstance(
                ExternalServiceTokenUtilLocalRecoveryTest.class.getClassLoader(),
                new Class<?>[]{AuthDao.class},
                (proxy, method, args) -> "getAccountById".equals(method.getName())
                        && Long.valueOf(42L).equals(args[0]) ? expected : null);
    }

    private static AccountDao accountDao(Account expected) {
        return (AccountDao) Proxy.newProxyInstance(
                ExternalServiceTokenUtilLocalRecoveryTest.class.getClassLoader(),
                new Class<?>[]{AccountDao.class},
                (proxy, method, args) -> {
                    if ("isActiveAccount".equals(method.getName())) {
                        return args != null && args.length == 1
                                && args[0] == expected
                                && "active".equals(expected.getState());
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

    private static class TestTokenUtil extends ExternalServiceTokenUtil {
        void setDependencies(AuthDao authDao, AccountDao accountDao) {
            this.authDao = authDao;
            this.accountDao = accountDao;
        }

        boolean allowed(Map<String, Object> payload) {
            return isAllowed(payload);
        }
    }
}
