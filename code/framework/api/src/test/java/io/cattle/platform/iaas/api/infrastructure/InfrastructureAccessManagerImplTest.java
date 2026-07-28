package io.cattle.platform.iaas.api.infrastructure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.api.auth.Policy;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class InfrastructureAccessManagerImplTest {

    @After
    public void clearProperties() {
        clear("modify.infrastructure.roles");
    }

    @Test
    public void archaiusSettingsReadDynamicRoles() {
        ConfigurationManager.getConfigInstance().setProperty("modify.infrastructure.roles", "admin,owner");

        InfrastructureAccessSettings settings = ArchaiusInfrastructureAccessSettings.create();

        assertEquals("admin,owner", settings.modifyInfrastructureRoles());

        ConfigurationManager.getConfigInstance().setProperty("modify.infrastructure.roles", "infra");

        assertEquals("infra", settings.modifyInfrastructureRoles());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSettings() {
        new InfrastructureAccessManagerImpl(null);
    }

    @Test
    public void injectedRolesControlInfrastructureMutation() {
        InfrastructureAccessManagerImpl manager = new InfrastructureAccessManagerImpl(settings("admin,owner"));

        assertTrue(manager.canModifyInfrastructure(policy("owner")));
        assertFalse(manager.canModifyInfrastructure(policy("user")));
    }

    @Test
    public void callbackReloadsInjectedRoles() {
        MutableInfrastructureAccessSettings settings = new MutableInfrastructureAccessSettings("admin");
        InfrastructureAccessManagerImpl manager = new InfrastructureAccessManagerImpl(settings);

        assertTrue(manager.canModifyInfrastructure(policy("admin")));
        assertFalse(manager.canModifyInfrastructure(policy("infra")));

        settings.value = "infra";
        settings.fire();

        assertFalse(manager.canModifyInfrastructure(policy("admin")));
        assertTrue(manager.canModifyInfrastructure(policy("infra")));
        assertEquals(1, settings.callbacks.size());
    }

    private static InfrastructureAccessSettings settings(final String roles) {
        return new InfrastructureAccessSettings() {
            @Override
            public String modifyInfrastructureRoles() {
                return roles;
            }

            @Override
            public void addModifyInfrastructureRolesCallback(Runnable callback) {
            }
        };
    }

    private static Policy policy(String... roles) {
        final Set<String> roleSet = new HashSet<String>(Arrays.asList(roles));
        return (Policy) Proxy.newProxyInstance(InfrastructureAccessManagerImplTest.class.getClassLoader(),
                new Class<?>[] { Policy.class }, (proxy, method, args) -> {
                    if ("getRoles".equals(method.getName())) {
                        return roleSet;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (Boolean.TYPE.equals(type)) {
            return false;
        }
        if (Character.TYPE.equals(type)) {
            return Character.valueOf('\0');
        }
        if (Byte.TYPE.equals(type)) {
            return Byte.valueOf((byte) 0);
        }
        if (Short.TYPE.equals(type)) {
            return Short.valueOf((short) 0);
        }
        if (Integer.TYPE.equals(type)) {
            return Integer.valueOf(0);
        }
        if (Long.TYPE.equals(type)) {
            return Long.valueOf(0L);
        }
        if (Float.TYPE.equals(type)) {
            return Float.valueOf(0F);
        }
        if (Double.TYPE.equals(type)) {
            return Double.valueOf(0D);
        }
        return null;
    }

    private static class MutableInfrastructureAccessSettings implements InfrastructureAccessSettings {
        private final List<Runnable> callbacks = new ArrayList<Runnable>();
        private String value;

        MutableInfrastructureAccessSettings(String value) {
            this.value = value;
        }

        @Override
        public String modifyInfrastructureRoles() {
            return value;
        }

        @Override
        public void addModifyInfrastructureRolesCallback(Runnable callback) {
            callbacks.add(callback);
        }

        void fire() {
            for (Runnable callback : callbacks) {
                callback.run();
            }
        }
    }
}
