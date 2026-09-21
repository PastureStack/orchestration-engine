package io.cattle.platform.iaas.api.auth.dao.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.dao.GenericResourceDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.object.ObjectManager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class AuthDaoImplAccountCreationTest {

    @Test
    public void completesAccountLifecycleBeforeReturningToLoginFlow() {
        AtomicBoolean synchronousCreate = new AtomicBoolean();
        AtomicBoolean asynchronousSchedule = new AtomicBoolean();
        AccountRecord active = new AccountRecord();
        active.setId(73L);
        active.setState(CommonStatesConstants.ACTIVE);

        GenericResourceDao resourceDao = GenericResourceDao.class.cast(Proxy.newProxyInstance(
                GenericResourceDao.class.getClassLoader(),
                new Class<?>[] {GenericResourceDao.class},
                (proxy, method, args) -> {
                    if ("create".equals(method.getName()) && args.length == 2
                            && args[0] == Account.class) {
                        synchronousCreate.set(true);
                        return active;
                    }
                    if ("createAndSchedule".equals(method.getName())) {
                        asynchronousSchedule.set(true);
                        return active;
                    }
                    return defaultValue(method.getReturnType());
                }));
        ObjectManager objectManager = ObjectManager.class.cast(Proxy.newProxyInstance(
                ObjectManager.class.getClassLoader(), new Class<?>[] {ObjectManager.class},
                (proxy, method, args) -> {
                    if ("convertToPropertiesFor".equals(method.getName())) {
                        return new HashMap<String, Object>();
                    }
                    return defaultValue(method.getReturnType());
                }));

        AuthDaoImpl dao = new AuthDaoImpl() {
            @Override
            public Account getAccountByExternalId(String externalId, String externalType) {
                return null;
            }
        };
        dao.resourceDao = resourceDao;
        dao.objectManager = objectManager;

        Account result = dao.createAccount("OIDC user", "user", "subject-73", "oidc_user");

        assertSame(active, result);
        assertTrue(synchronousCreate.get());
        assertFalse(asynchronousSchedule.get());
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
