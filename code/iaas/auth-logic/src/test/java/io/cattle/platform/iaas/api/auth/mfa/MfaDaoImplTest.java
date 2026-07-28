package io.cattle.platform.iaas.api.auth.mfa;

import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.object.ObjectManager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class MfaDaoImplTest {

    @Test
    public void createsInternalCredentialForExplicitAuthorizationPrincipal() {
        AtomicReference<Map<Object, Object>> captured = new AtomicReference<>();
        final CredentialRecord record = new CredentialRecord();
        final AtomicBoolean corrected = new AtomicBoolean();
        ObjectManager objectManager = ObjectManager.class.cast(Proxy.newProxyInstance(
                ObjectManager.class.getClassLoader(), new Class<?>[] {ObjectManager.class},
                (proxy, method, args) -> {
                    if ("convertToPropertiesFor".equals(method.getName())) {
                        if (!(args[1] instanceof Map<?, ?> sourceProperties)) {
                            throw new IllegalArgumentException("Expected credential properties");
                        }
                        Map<Object, Object> properties = new HashMap<>();
                        for (Map.Entry<?, ?> entry : sourceProperties.entrySet()) {
                            properties.put(entry.getKey(), entry.getValue());
                        }
                        captured.set(properties);
                        return new HashMap<String, Object>();
                    }
                    if ("create".equals(method.getName()) && args.length == 2
                            && args[0] == Credential.class) {
                        // Reproduce the legacy unauthenticated request context:
                        // ObjectManager overwrites the requested principal
                        // with the transient token account.
                        record.setAccountId(4L);
                        record.setKind(String.class.cast(captured.get().get(CREDENTIAL.KIND)));
                        record.setPublicValue(String.class.cast(captured.get().get(CREDENTIAL.PUBLIC_VALUE)));
                        record.setSecretValue(String.class.cast(captured.get().get(CREDENTIAL.SECRET_VALUE)));
                        record.setState(String.class.cast(captured.get().get(CREDENTIAL.STATE)));
                        Object rawData = captured.get().get(CREDENTIAL.DATA);
                        if (!(rawData instanceof Map<?, ?> sourceData)) {
                            throw new IllegalArgumentException("Expected credential data");
                        }
                        Map<String, Object> data = new HashMap<>();
                        for (Map.Entry<?, ?> entry : sourceData.entrySet()) {
                            data.put(String.class.cast(entry.getKey()), entry.getValue());
                        }
                        record.setData(data);
                        return record;
                    }
                    if ("persist".equals(method.getName()) && args[0] == record) {
                        corrected.set(true);
                        return record;
                    }
                    return defaultValue(method.getReturnType());
                }));

        MfaDaoImpl dao = new MfaDaoImpl();
        dao.objectManager = objectManager;
        Map<String, Object> data = new HashMap<>();
        data.put("purpose", "login");

        Credential created = dao.create(73L, "mfaLoginChallenge", "public-handle",
                "protected-envelope", data);

        assertSame(record, created);
        assertTrue(corrected.get());
        assertEquals(Long.valueOf(73L), created.getAccountId());
        assertEquals("mfaLoginChallenge", created.getKind());
        assertEquals("public-handle", created.getPublicValue());
        assertEquals("protected-envelope", created.getSecretValue());
        assertEquals(CommonStatesConstants.ACTIVE, created.getState());
        assertEquals("login", created.getData().get("purpose"));
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
