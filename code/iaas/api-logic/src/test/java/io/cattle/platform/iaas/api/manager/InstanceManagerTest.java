package io.cattle.platform.iaas.api.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.engine.process.ExitReason;
import io.cattle.platform.engine.process.Predicate;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.meta.Relationship;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class InstanceManagerTest {

    @Test
    public void countGreaterThanOneReturnsLegacyListAndSchedulesEachInstance() {
        TestInstanceManager manager = newManager();
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put(InstanceConstants.FIELD_COUNT, Integer.valueOf(3));

        Object result = manager.createForTest(TestInstance.class, properties);

        assertTrue(result instanceof List);
        List<?> instances = List.class.cast(result);
        assertEquals(3, instances.size());
        assertEquals(manager.objectManager.created, instances);
        assertEquals(manager.objectManager.created, manager.processManager.scheduledResources);
        assertEquals(manager.objectManager.created, manager.objectManager.reloaded);
        assertEquals(Collections.nCopies(3, StandardProcess.CREATE), manager.processManager.standardProcesses);
        for (Map<String, Object> scheduledData : manager.processManager.scheduledData) {
            assertSame(properties, scheduledData);
        }
    }

    @Test
    public void missingCountUsesSingleCreatePath() {
        TestInstanceManager manager = newManager();
        Map<String, Object> properties = new LinkedHashMap<String, Object>();

        TestInstance result = manager.createTypedForTest(TestInstance.class, properties);

        assertSame(manager.objectManager.created.get(0), result);
        assertEquals(1, manager.objectManager.created.size());
        assertEquals(Collections.singletonList(result), manager.processManager.scheduledResources);
        assertEquals(Collections.singletonList(result), manager.objectManager.reloaded);
        assertEquals(Collections.singletonList(StandardProcess.CREATE), manager.processManager.standardProcesses);
        assertSame(properties, manager.processManager.scheduledData.get(0));
    }

    private static TestInstanceManager newManager() {
        TestInstanceManager manager = new TestInstanceManager();
        manager.setObjectManager(manager.objectManager);
        manager.setObjectProcessManager(manager.processManager);
        return manager;
    }

    private static class TestInstanceManager extends InstanceManager {
        private final TestObjectManager objectManager = new TestObjectManager();
        private final TestObjectProcessManager processManager = new TestObjectProcessManager();

        Object createForTest(Class<?> clz, Map<String, Object> properties) {
            return createAndScheduleObject(clz, properties);
        }

        <T> T createTypedForTest(Class<T> clz, Map<String, Object> properties) {
            return clz.cast(createAndScheduleObject(clz, properties));
        }
    }

    public static class TestInstance {
    }

    private static class TestObjectManager implements ObjectManager {
        private final List<Object> created = new ArrayList<Object>();
        private final List<Object> reloaded = new ArrayList<Object>();

        @Override
        public <T> T newRecord(Class<T> type) {
            throw unsupported();
        }

        @Override
        public <T> T create(T obj) {
            created.add(obj);
            return obj;
        }

        @Override
        public <T> T create(T obj, Map<String, Object> properties) {
            created.add(obj);
            return obj;
        }

        @Override
        public <T> T create(T obj, Object key, Object... valueKeyValue) {
            throw unsupported();
        }

        @Override
        public <T> T create(Class<T> clz, Map<String, Object> properties) {
            try {
                T instance = clz.getDeclaredConstructor().newInstance();
                created.add(instance);
                return instance;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public <T> T create(Class<T> clz, Object key, Object... valueKeyValue) {
            throw unsupported();
        }

        @Override
        public <T> T reload(T obj) {
            reloaded.add(obj);
            return obj;
        }

        @Override
        public <T> T persist(T obj) {
            throw unsupported();
        }

        @Override
        public void delete(Object obj) {
            throw unsupported();
        }

        @Override
        public <T> T loadResource(Class<T> type, String resourceId) {
            throw unsupported();
        }

        @Override
        public <T> T loadResource(Class<T> type, Long resourceId) {
            throw unsupported();
        }

        @Override
        public <T> T loadResource(String resourceType, String resourceId) {
            throw unsupported();
        }

        @Override
        public <T> T loadResource(String resourceType, Long resourceId) {
            throw unsupported();
        }

        @Override
        public <T> T setFields(Object obj, Map<String, Object> values) {
            throw unsupported();
        }

        @Override
        public <T> T setFields(Schema schema, Object obj, Map<String, Object> values) {
            throw unsupported();
        }

        @Override
        public <T> T setFields(Object obj, Object key, Object... valueKeyValue) {
            throw unsupported();
        }

        @Override
        public Map<String, Object> convertToPropertiesFor(Object obj, Map<Object, Object> object) {
            throw unsupported();
        }

        @Override
        public <T> List<T> children(Object obj, Class<T> type) {
            throw unsupported();
        }

        @Override
        public <T> List<T> children(Object obj, Class<T> type, String propertyName) {
            throw unsupported();
        }

        @Override
        public <T> List<T> mappedChildren(Object obj, Class<T> type) {
            throw unsupported();
        }

        @Override
        public <T> T findOne(Class<T> clz, Map<Object, Object> values) {
            throw unsupported();
        }

        @Override
        public <T> T findOne(Class<T> clz, Object key, Object... valueKeyValue) {
            throw unsupported();
        }

        @Override
        public <T> T findAny(Class<T> clz, Map<Object, Object> values) {
            throw unsupported();
        }

        @Override
        public <T> T findAny(Class<T> clz, Object key, Object... valueKeyValue) {
            throw unsupported();
        }

        @Override
        public <T> List<T> find(Class<T> clz, Map<Object, Object> values) {
            throw unsupported();
        }

        @Override
        public <T> List<T> find(Class<T> clz, Object key, Object... valueKeyValue) {
            throw unsupported();
        }

        @Override
        public <T> List<T> getListByRelationship(Object obj, Relationship relationship) {
            throw unsupported();
        }

        @Override
        public <T> T getObjectByRelationship(Object obj, Relationship relationship) {
            throw unsupported();
        }

        @Override
        public String getType(Object obj) {
            throw unsupported();
        }

        @Override
        public SchemaFactory getSchemaFactory() {
            throw unsupported();
        }

        @Override
        public boolean isKind(Object obj, String kind) {
            throw unsupported();
        }
    }

    private static class TestObjectProcessManager implements ObjectProcessManager {
        private final List<StandardProcess> standardProcesses = new ArrayList<StandardProcess>();
        private final List<Object> scheduledResources = new ArrayList<Object>();
        private final List<Map<String, Object>> scheduledData = new ArrayList<Map<String, Object>>();

        @Override
        public String getStandardProcessName(StandardProcess process, Object object) {
            return process.name();
        }

        @Override
        public String getStandardProcessName(StandardProcess process, String type) {
            return process.name();
        }

        @Override
        public ProcessInstance createProcessInstance(String processName, Object resource, Map<String, Object> data) {
            throw unsupported();
        }

        @Override
        public void scheduleProcessInstance(String processName, Object resource, Map<String, Object> data) {
            scheduledResources.add(resource);
            scheduledData.add(data);
        }

        @Override
        public void scheduleProcessInstance(String processName, Object resource, Map<String, Object> data, Predicate predicate) {
            scheduleProcessInstance(processName, resource, data);
        }

        @Override
        public void scheduleStandardProcess(StandardProcess process, Object resource, Map<String, Object> data) {
            standardProcesses.add(process);
            scheduledResources.add(resource);
            scheduledData.add(data);
        }

        @Override
        public void scheduleStandardProcess(StandardProcess process, Object resource, Map<String, Object> data, Predicate predicate) {
            scheduleStandardProcess(process, resource, data);
        }

        @Override
        public ExitReason executeStandardProcess(StandardProcess process, Object resource, Map<String, Object> data) {
            throw unsupported();
        }

        @Override
        public ExitReason executeProcess(String processName, Object resource, Map<String, Object> data) {
            throw unsupported();
        }

        @Override
        public void scheduleProcessInstanceAsync(String processName, Object resource, Map<String, Object> data) {
            throw unsupported();
        }

        @Override
        public void scheduleStandardProcessAsync(StandardProcess process, Object resource, Map<String, Object> data) {
            throw unsupported();
        }

        @Override
        public String getProcessName(Object resource, StandardProcess process) {
            return process.name();
        }

        @Override
        public void scheduleStandardChainedProcessAsync(StandardProcess from, StandardProcess to, Object resource, Map<String, Object> data) {
            throw unsupported();
        }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Not required by this test");
    }
}
