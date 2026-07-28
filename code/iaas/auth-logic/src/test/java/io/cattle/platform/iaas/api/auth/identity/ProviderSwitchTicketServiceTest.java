package io.cattle.platform.iaas.api.auth.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ProviderSwitchTicketServiceTest {

    @Test
    public void preparesAHashedShortLivedTicketForAnActiveAdministrator() {
        final Map<String, Object> captured = new HashMap<>();
        Account account = account(42L, AccountConstants.ADMIN_KIND, "Admin");
        ProviderSwitchTicketService service = new ProviderSwitchTicketService();
        service.accountDao = proxy(AccountDao.class, (method, args) -> {
            if ("isActiveAccount".equals(method.getName())) {
                return true;
            }
            return defaultValue(method.getReturnType());
        });
        service.authDao = proxy(AuthDao.class, (method, args) -> {
            if ("createProviderSwitchTicket".equals(method.getName())) {
                captured.put("account", args[0]);
                captured.put("key", args[1]);
                captured.put("data", args[2]);
            }
            return defaultValue(method.getReturnType());
        });

        long before = System.currentTimeMillis();
        PreparedProviderSwitch prepared = service.prepare(account, "localAuthConfig",
                new Identity(ProjectConstants.RANCHER_ID, "42", "Admin", null, null, "admin", true));
        long after = System.currentTimeMillis();

        assertNotNull(prepared.getCode());
        assertEquals(43, prepared.getCode().length());
        assertTrue(prepared.getExpiresAt() >= before + 60_000L);
        assertTrue(prepared.getExpiresAt() <= after + 300_000L);
        assertSame(account, captured.get("account"));
        assertEquals(64, String.valueOf(captured.get("key")).length());
        assertTrue(!prepared.getCode().equals(captured.get("key")));
        assertTrue(captured.get("data") instanceof Map<?, ?>);
        Map<?, ?> data = (Map<?, ?>) captured.get("data");
        assertEquals("localAuthConfig", data.get("provider"));
        assertEquals("42", data.get("externalId"));
    }

    @Test(expected = ClientVisibleException.class)
    public void nonAdministratorCannotReceiveProviderSwitchTicket() {
        ProviderSwitchTicketService service = new ProviderSwitchTicketService();
        service.accountDao = proxy(AccountDao.class, (method, args) -> true);
        service.authDao = proxy(AuthDao.class, (method, args) -> defaultValue(method.getReturnType()));
        service.prepare(account(7L, "user", "User"), "localAuthConfig",
                new Identity(ProjectConstants.RANCHER_ID, "7", "User", null, null, "user", true));
    }

    private static Account account(long id, String kind, String name) {
        return proxy(Account.class, (method, args) -> {
            if ("getId".equals(method.getName())) {
                return id;
            }
            if ("getKind".equals(method.getName())) {
                return kind;
            }
            if ("getName".equals(method.getName())) {
                return name;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> handler.invoke(method, args)));
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
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private interface Handler {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
