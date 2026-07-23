package io.cattle.platform.api.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.process.StandardProcess;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class ObjectResourceManagerSettingsTest {

    @After
    public void clearProperties() {
        clear("api.show.removed.for.seconds");
    }

    @Test
    public void archaiusSettingsReadDynamicRemovedDelay() {
        ConfigurationManager.getConfigInstance().setProperty("api.show.removed.for.seconds", 30);

        ObjectResourceManagerSettings settings = ArchaiusObjectResourceManagerSettings.create();

        assertEquals(30, settings.removedDelaySeconds());

        ConfigurationManager.getConfigInstance().setProperty("api.show.removed.for.seconds", 45);

        assertEquals(45, settings.removedDelaySeconds());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSettings() {
        new TestResourceManager(null);
    }

    @Test
    public void removedTimeUsesInjectedDelaySeconds() {
        TestResourceManager manager = new TestResourceManager(settings(120));

        long before = System.currentTimeMillis();
        Date removedTime = manager.removed();
        long after = System.currentTimeMillis();

        assertTrue(removedTime.getTime() >= before - 120000);
        assertTrue(removedTime.getTime() <= after - 120000);
    }

    @Test
    public void createAndScheduleObjectReturnsReloadedTypedObject() {
        TestResourceManager manager = new TestResourceManager(settings(0));
        TestObject created = new TestObject();
        TestObject reloaded = new TestObject();
        Map<String, Object> properties = new HashMap<String, Object>();
        manager.setObjectManager(objectManager(created, reloaded));

        TestObject result = manager.createAndSchedule(TestObject.class, properties);

        assertSame(reloaded, result);
        assertEquals(StandardProcess.CREATE, manager.scheduledProcess);
        assertSame(created, manager.scheduledResource);
        assertSame(properties, manager.scheduledData);
    }

    @Test
    public void validActionUsesStateListWithoutGenericCast() {
        TestResourceManager manager = new TestResourceManager(settings(0));
        TestObject object = new TestObject("active");
        Map<String, Object> attributes = new HashMap<String, Object>();
        Action action = new Action(null, null, attributes);

        attributes.put(ObjectMetaDataManager.STATES_FIELD, Arrays.asList("active", "updating"));
        assertTrue(manager.validAction(object, action));

        attributes.put(ObjectMetaDataManager.STATES_FIELD, Arrays.asList("inactive"));
        assertFalse(manager.validAction(object, action));
    }

    @Test(expected = ClassCastException.class)
    public void validActionKeepsNonListStateAttributeFailure() {
        TestResourceManager manager = new TestResourceManager(settings(0));
        TestObject object = new TestObject("active");
        Map<String, Object> attributes = new HashMap<String, Object>();
        attributes.put(ObjectMetaDataManager.STATES_FIELD, "active");

        manager.validAction(object, new Action(null, null, attributes));
    }

    private static ObjectManager objectManager(final Object created, final Object reloaded) {
        return ObjectManager.class.cast(Proxy.newProxyInstance(ObjectResourceManagerSettingsTest.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class }, new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("create".equals(method.getName()) && args.length == 2 && args[0] instanceof Class<?>) {
                            return created;
                        }
                        if ("reload".equals(method.getName())) {
                            return reloaded;
                        }
                        throw new UnsupportedOperationException(method.toString());
                    }
                }));
    }

    private static ObjectResourceManagerSettings settings(final int removedDelaySeconds) {
        return new ObjectResourceManagerSettings() {
            @Override
            public int removedDelaySeconds() {
                return removedDelaySeconds;
            }
        };
    }

    private void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static class TestResourceManager extends AbstractObjectResourceManager {

        StandardProcess scheduledProcess;
        Object scheduledResource;
        Map<String, Object> scheduledData;

        TestResourceManager(ObjectResourceManagerSettings settings) {
            super(settings);
        }

        Date removed() {
            return removedTime();
        }

        TestObject createAndSchedule(Class<TestObject> clz, Map<String, Object> properties) {
            return clz.cast(createAndScheduleObject(clz, properties));
        }

        boolean validAction(Object object, Action action) {
            return isValidAction(object, action);
        }

        @Override
        protected void scheduleProcess(StandardProcess process, Object resource, Map<String, Object> data) {
            scheduledProcess = process;
            scheduledResource = resource;
            scheduledData = data;
        }

        @Override
        protected Object removeFromStore(String type, String id, Object obj, ApiRequest request) {
            return null;
        }

        @Override
        protected Object listInternal(SchemaFactory schemaFactory, String type, Map<Object, Object> criteria,
                ListOptions options) {
            return null;
        }

        @Override
        public String[] getTypes() {
            return new String[0];
        }

        @Override
        public Class<?>[] getTypeClasses() {
            return new Class<?>[0];
        }
    }

    public static class TestObject {
        private String state;

        public TestObject() {
        }

        public TestObject(String state) {
            this.state = state;
        }

        public String getState() {
            return state;
        }
    }
}
