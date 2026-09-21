package io.cattle.platform.iaas.api.auth.dao.impl;

import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.iaas.api.auth.identity.IdentityLinkKey;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockCallbackWithException;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.provider.LockProvider;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class AuthDaoImplIdentityLinkOwnershipTest {

    private static final String PROVIDER = "oidcconfig";
    private static final String EXTERNAL_TYPE = "oidc_user";
    private static final String EXTERNAL_ID = "authentik-user-73";

    @Test
    public void correctsTransientTokenOwnershipWhenCreatingIdentityLink() {
        AtomicReference<Map<Object, Object>> captured = new AtomicReference<>();
        AtomicBoolean corrected = new AtomicBoolean();
        CredentialRecord created = credential(91L, 4L, null);
        ObjectManager objectManager = objectManager(captured, created, corrected);
        TestAuthDao dao = dao(objectManager);
        AccountRecord target = account(73L, AccountConstants.USER_KIND, "account-73",
                EXTERNAL_ID, EXTERNAL_TYPE);
        Identity identity = identity(EXTERNAL_ID);
        String linkKey = IdentityLinkKey.create(PROVIDER, EXTERNAL_TYPE, EXTERNAL_ID);

        Credential result = dao.linkIdentity(target, identity, PROVIDER, linkKey);

        assertSame(created, result);
        assertTrue(corrected.get());
        assertEquals(Long.valueOf(73L), result.getAccountId());
        assertEquals(CredentialConstants.KIND_AUTH_IDENTITY, captured.get().get(CREDENTIAL.KIND));
        assertEquals(linkKey, captured.get().get(CREDENTIAL.PUBLIC_VALUE));
    }

    @Test
    public void repairsLegacyLinkOnlyWhenVerifiedIdentityMatchesTargetAccount() {
        AtomicBoolean persisted = new AtomicBoolean();
        TestAuthDao dao = dao(persistOnlyObjectManager(persisted));
        AccountRecord token = account(4L, "token", "token", null, null);
        AccountRecord target = account(73L, AccountConstants.USER_KIND, "account-73",
                EXTERNAL_ID, EXTERNAL_TYPE);
        CredentialRecord legacy = credential(92L, 4L, identityData(EXTERNAL_ID));
        dao.existing = legacy;
        dao.accounts.put(4L, token);
        String linkKey = IdentityLinkKey.create(PROVIDER, EXTERNAL_TYPE, EXTERNAL_ID);

        Credential result = dao.linkIdentity(target, identity(EXTERNAL_ID), PROVIDER, linkKey);

        assertSame(legacy, result);
        assertTrue(persisted.get());
        assertEquals(Long.valueOf(73L), result.getAccountId());
        assertEquals(Long.valueOf(4L), result.getData().get("repairedFromAccountId"));
        assertTrue(result.getData().get("repairedAt") instanceof java.util.Date);
    }

    @Test(expected = ClientVisibleException.class)
    public void refusesToMoveLegacyLinkToAccountWithDifferentExternalIdentity() {
        AtomicBoolean persisted = new AtomicBoolean();
        TestAuthDao dao = dao(persistOnlyObjectManager(persisted));
        dao.accounts.put(4L, account(4L, "token", "token", null, null));
        dao.existing = credential(93L, 4L, identityData(EXTERNAL_ID));
        AccountRecord wrongTarget = account(74L, AccountConstants.USER_KIND, "account-74",
                "different-user", EXTERNAL_TYPE);
        String linkKey = IdentityLinkKey.create(PROVIDER, EXTERNAL_TYPE, EXTERNAL_ID);

        try {
            dao.linkIdentity(wrongTarget, identity(EXTERNAL_ID), PROVIDER, linkKey);
        } finally {
            assertEquals(Long.valueOf(4L), dao.existing.getAccountId());
            assertTrue(!persisted.get());
        }
    }

    @Test(expected = ClientVisibleException.class)
    public void refusesToMoveLinkOwnedByAnotherLoginAccount() {
        AtomicBoolean persisted = new AtomicBoolean();
        TestAuthDao dao = dao(persistOnlyObjectManager(persisted));
        dao.accounts.put(75L, account(75L, AccountConstants.USER_KIND, "account-75",
                EXTERNAL_ID, EXTERNAL_TYPE));
        dao.existing = credential(94L, 75L, identityData(EXTERNAL_ID));
        AccountRecord target = account(73L, AccountConstants.USER_KIND, "account-73",
                EXTERNAL_ID, EXTERNAL_TYPE);
        String linkKey = IdentityLinkKey.create(PROVIDER, EXTERNAL_TYPE, EXTERNAL_ID);

        try {
            dao.linkIdentity(target, identity(EXTERNAL_ID), PROVIDER, linkKey);
        } finally {
            assertEquals(Long.valueOf(75L), dao.existing.getAccountId());
            assertTrue(!persisted.get());
        }
    }

    private static TestAuthDao dao(ObjectManager objectManager) {
        TestAuthDao dao = new TestAuthDao();
        dao.objectManager = objectManager;
        dao.lockManager = new ImmediateLockManager();
        return dao;
    }

    private static ObjectManager objectManager(AtomicReference<Map<Object, Object>> captured,
                                               CredentialRecord created,
                                               AtomicBoolean corrected) {
        return ObjectManager.class.cast(Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                new Class<?>[] {ObjectManager.class}, (proxy, method, args) -> {
                    if ("convertToPropertiesFor".equals(method.getName())) {
                        Map<Object, Object> properties = copyMap(args[1]);
                        captured.set(properties);
                        return new HashMap<String, Object>();
                    }
                    if ("create".equals(method.getName()) && args.length == 2
                            && args[0] == Credential.class) {
                        created.setAccountId(4L);
                        created.setKind(String.valueOf(captured.get().get(CREDENTIAL.KIND)));
                        created.setPublicValue(String.valueOf(captured.get().get(CREDENTIAL.PUBLIC_VALUE)));
                        created.setState(String.valueOf(captured.get().get(CREDENTIAL.STATE)));
                        created.setData(copyStringMap(captured.get().get(CREDENTIAL.DATA)));
                        return created;
                    }
                    if ("persist".equals(method.getName()) && args[0] == created) {
                        corrected.set(true);
                        return created;
                    }
                    return defaultValue(method.getReturnType());
                }));
    }

    private static ObjectManager persistOnlyObjectManager(AtomicBoolean persisted) {
        return ObjectManager.class.cast(Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                new Class<?>[] {ObjectManager.class}, (proxy, method, args) -> {
                    if ("persist".equals(method.getName())) {
                        persisted.set(true);
                        return args[0];
                    }
                    return defaultValue(method.getReturnType());
                }));
    }

    private static AccountRecord account(long id, String kind, String uuid,
                                         String externalId, String externalIdType) {
        AccountRecord account = new AccountRecord();
        account.setId(id);
        account.setKind(kind);
        account.setUuid(uuid);
        account.setState(CommonStatesConstants.ACTIVE);
        account.setExternalId(externalId);
        account.setExternalIdType(externalIdType);
        return account;
    }

    private static CredentialRecord credential(long id, long accountId, Map<String, Object> data) {
        CredentialRecord credential = new CredentialRecord();
        credential.setId(id);
        credential.setAccountId(accountId);
        credential.setKind(CredentialConstants.KIND_AUTH_IDENTITY);
        credential.setState(CommonStatesConstants.ACTIVE);
        credential.setData(data);
        return credential;
    }

    private static Identity identity(String externalId) {
        return new Identity(EXTERNAL_TYPE, externalId, "OIDC user", null, null,
                "oidc-user", true);
    }

    private static Map<String, Object> identityData(String externalId) {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", PROVIDER);
        data.put("externalIdType", EXTERNAL_TYPE);
        data.put("externalId", externalId);
        return data;
    }

    private static Map<Object, Object> copyMap(Object value) {
        Map<Object, Object> result = new HashMap<>();
        if (value instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static Map<String, Object> copyStringMap(Object value) {
        Map<String, Object> result = new HashMap<>();
        if (value instanceof Map<?, ?> source) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
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

    private static class TestAuthDao extends AuthDaoImpl {
        private Credential existing;
        private final Map<Long, Account> accounts = new HashMap<>();

        @Override
        public Credential getIdentityLink(String linkKey) {
            return existing;
        }

        @Override
        public Account getAccountById(Long id) {
            return accounts.get(id);
        }
    }

    private static class ImmediateLockManager implements LockManager {
        @Override
        public <T, E extends Throwable> T lock(LockDefinition lockDef,
                                               LockCallbackWithException<T, E> callback,
                                               Class<E> clz) throws E {
            return callback.doWithLock();
        }

        @Override
        public <T> T lock(LockDefinition lockDef, LockCallback<T> callback) {
            return callback.doWithLock();
        }

        @Override
        public <T> T tryLock(LockDefinition lockDef, LockCallback<T> callback) {
            return callback.doWithLock();
        }

        @Override
        public <T, E extends Throwable> T tryLock(LockDefinition lockDef,
                                                  LockCallbackWithException<T, E> callback,
                                                  Class<E> clz) throws E {
            return callback.doWithLock();
        }

        @Override
        public LockProvider getLockProvider() {
            return null;
        }
    }
}
