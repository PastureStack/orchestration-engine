package io.cattle.platform.object.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.object.lifecycle.ObjectLifeCycleHandler;
import io.cattle.platform.object.lifecycle.ObjectLifeCycleHandler.LifeCycleEvent;
import io.cattle.platform.object.meta.ActionDefinition;
import io.cattle.platform.object.meta.MapRelationship;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.meta.Relationship;
import io.cattle.platform.object.postinit.ObjectPostInstantiationHandler;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class AbstractObjectManagerTest {

    @Test
    public void convertsNestedReferenceMapsThroughCentralCastBoundary() {
        TestObjectManager manager = new TestObjectManager();
        Map<String, Object> child = new LinkedHashMap<String, Object>();
        child.put("name", "demo");

        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("child", child);
        values.put("label", "root");

        Map<Object, Object> converted = manager.convert(new Parent(), values);

        assertEquals("root", converted.get("label"));
        assertTrue(converted.containsKey(manager.childRelationship));

        Object nested = converted.get(manager.childRelationship);
        assertTrue(nested instanceof Map);
        assertEquals("demo", Map.class.cast(nested).get("name"));
        assertSame(child, values.get("child"));
    }

    @Test
    public void createExistingInstancePassesRuntimeClassThroughHandlers() {
        TestObjectManager manager = new TestObjectManager();
        manager.postInitHandlers = Arrays.asList(new ObjectPostInstantiationHandler() {
            @Override
            public <T> T postProcess(T obj, Class<?> clz, Map<String, Object> properties) {
                manager.steps.add("postInit");
                assertSame(Parent.class, clz);
                return obj;
            }
        });
        manager.lifeCycleHandlers = Arrays.asList(new ObjectLifeCycleHandler() {
            @Override
            public <T> T onEvent(LifeCycleEvent event, T instance, Class<?> clz, Map<String, Object> properties) {
                manager.steps.add(event.name());
                assertSame(Parent.class, clz);
                return instance;
            }
        });

        Parent parent = new Parent();
        Parent result = manager.create(parent, Collections.<String, Object>emptyMap());

        assertSame(parent, result);
        assertSame(Parent.class, manager.insertClass);
        assertEquals(Arrays.asList("postInit", "insert", "CREATE"), manager.steps);
    }

    @Test
    public void listRelationshipDelegatesChildRelationshipWithPropertyName() {
        TestObjectManager manager = new TestObjectManager();
        Parent parent = new Parent();
        Child child = new Child();
        manager.childResults = Arrays.<Object>asList(child);
        Relationship rel = new TestRelationship("children", "parentId", Child.class, true, Relationship.RelationshipType.CHILD);

        List<Child> result = manager.getListByRelationship(parent, rel);

        assertEquals(Arrays.asList(child), result);
        assertSame(manager.childResultList, result);
        assertSame(parent, manager.childSource);
        assertSame(Child.class, manager.childType);
        assertEquals("parentId", manager.childPropertyName);
    }

    @Test
    public void listRelationshipDelegatesMapRelationship() {
        TestObjectManager manager = new TestObjectManager();
        Parent parent = new Parent();
        Child child = new Child();
        manager.mapResults = Arrays.<Object>asList(child);
        MapRelationship rel = new TestMapRelationship("children", Child.class);

        List<Child> result = manager.getListByRelationship(parent, rel);

        assertEquals(Arrays.asList(child), result);
        assertSame(manager.mapResults, result);
        assertSame(parent, manager.mapSource);
        assertSame(rel, manager.mapRelationship);
    }

    @Test
    public void relationshipBoundariesKeepCallerGenericObjectManagerSignatures() throws Exception {
        Method list = AbstractObjectManager.class.getMethod("getListByRelationship", Object.class, Relationship.class);
        assertTrue(Modifier.isPublic(list.getModifiers()));
        assertEquals(1, list.getTypeParameters().length);
        assertEquals("T", list.getTypeParameters()[0].getName());
        assertEquals("java.util.List<T>", list.getGenericReturnType().getTypeName());

        Method object = AbstractObjectManager.class.getMethod("getObjectByRelationship", Object.class, Relationship.class);
        assertTrue(Modifier.isPublic(object.getModifiers()));
        assertEquals(1, object.getTypeParameters().length);
        assertEquals("T", object.getTypeParameters()[0].getName());
        assertEquals("T", object.getGenericReturnType().getTypeName());
    }

    @Test
    public void relationshipCastBoundaryStaysPrivate() throws Exception {
        Method relationshipCast = AbstractObjectManager.class.getDeclaredMethod("relationshipCast", Object.class);
        Parent parent = new Parent();

        assertTrue(Modifier.isPrivate(relationshipCast.getModifiers()));
        assertTrue(Modifier.isStatic(relationshipCast.getModifiers()));
        relationshipCast.setAccessible(true);
        assertSame(parent, relationshipCast.invoke(null, parent));
    }

    @Test
    public void objectRelationshipLoadsReferenceByStringId() {
        TestObjectManager manager = new TestObjectManager();
        Parent parent = new Parent();
        parent.setChildId(42L);
        Child child = new Child();
        manager.loadedResource = child;
        Relationship rel = new TestRelationship("child", "childId", Child.class);

        Child result = manager.getObjectByRelationship(parent, rel);

        assertSame(child, result);
        assertSame(Child.class, manager.loadedType);
        assertEquals("42", manager.loadedResourceId);
    }

    private static class TestObjectManager extends AbstractObjectManager {
        private final Relationship childRelationship = new TestRelationship("child", "childId", Child.class);
        private final List<String> steps = new ArrayList<String>();
        private Class<?> insertClass;
        private Object childSource;
        private Class<?> childType;
        private String childPropertyName;
        private List<Object> childResults = Collections.emptyList();
        private List<?> childResultList;
        private Object mapSource;
        private MapRelationship mapRelationship;
        private List<?> mapResults = Collections.emptyList();
        private Class<?> loadedType;
        private String loadedResourceId;
        private Object loadedResource;

        TestObjectManager() {
            setMetaDataManager(new TestObjectMetaDataManager(childRelationship));
        }

        Map<Object, Object> convert(Object obj, Map<String, Object> values) {
            return toObjectsToWrite(obj, values);
        }

        @Override
        public String getType(Object obj) {
            return Child.class.equals(obj) ? "child" : "parent";
        }

        @Override
        protected <T> T instantiate(Class<T> clz, Map<String, Object> properties) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected <T> T insert(T instance, Class<?> clz, Map<String, Object> properties) {
            steps.add("insert");
            insertClass = clz;
            return instance;
        }

        @Override
        protected List<?> getListByRelationshipMap(Object obj, MapRelationship rel) {
            mapSource = obj;
            mapRelationship = rel;
            return mapResults;
        }

        @Override
        public <T> T newRecord(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T reload(T obj) {
            return obj;
        }

        @Override
        public <T> T persist(T obj) {
            return obj;
        }

        @Override
        public void delete(Object obj) {
        }

        @Override
        public <T> T loadResource(Class<T> type, String resourceId) {
            loadedType = type;
            loadedResourceId = resourceId;
            return type.cast(loadedResource);
        }

        @Override
        public <T> T loadResource(Class<T> type, Long resourceId) {
            return null;
        }

        @Override
        public <T> T loadResource(String resourceType, String resourceId) {
            return null;
        }

        @Override
        public <T> T loadResource(String resourceType, Long resourceId) {
            return null;
        }

        @Override
        public <T> T setFields(Object obj, Map<String, Object> values) {
            return null;
        }

        @Override
        public <T> T setFields(Schema schema, Object obj, Map<String, Object> values) {
            return null;
        }

        @Override
        public Map<String, Object> convertToPropertiesFor(Object obj, Map<Object, Object> object) {
            return Collections.emptyMap();
        }

        @Override
        public <T> List<T> children(Object obj, Class<T> type) {
            return children(obj, type, null);
        }

        @Override
        public <T> List<T> children(Object obj, Class<T> type, String propertyName) {
            childSource = obj;
            childType = type;
            childPropertyName = propertyName;
            List<T> result = new ArrayList<T>();
            for (Object child : childResults) {
                result.add(type.cast(child));
            }
            childResultList = result;
            return result;
        }

        @Override
        public <T> List<T> mappedChildren(Object obj, Class<T> type) {
            return Collections.emptyList();
        }

        @Override
        public <T> T findOne(Class<T> clz, Map<Object, Object> values) {
            return null;
        }

        @Override
        public <T> T findOne(Class<T> clz, Object key, Object... valueKeyValue) {
            return null;
        }

        @Override
        public <T> T findAny(Class<T> clz, Map<Object, Object> values) {
            return null;
        }

        @Override
        public <T> T findAny(Class<T> clz, Object key, Object... valueKeyValue) {
            return null;
        }

        @Override
        public <T> List<T> find(Class<T> clz, Map<Object, Object> values) {
            return Collections.emptyList();
        }

        @Override
        public <T> List<T> find(Class<T> clz, Object key, Object... valueKeyValue) {
            return Collections.emptyList();
        }
    }

    private static class TestObjectMetaDataManager implements ObjectMetaDataManager {
        private final Relationship relationship;

        TestObjectMetaDataManager(Relationship relationship) {
            this.relationship = relationship;
        }

        @Override
        public Map<String, Relationship> getLinkRelationships(SchemaFactory schemaFactory, String type) {
            if (!"parent".equals(type)) {
                return Collections.emptyMap();
            }

            Map<String, Relationship> relationships = new HashMap<String, Relationship>();
            relationships.put("child", relationship);
            return relationships;
        }

        @Override
        public String convertToPropertyNameString(Class<?> recordClass, Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String lookupPropertyNameFromFieldName(Class<?> recordClass, String fieldName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object convertFieldNameFor(String type, Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> getLinks(SchemaFactory schemaFactory, String type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Relationship getRelationship(String type, String linkName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Relationship getRelationship(Class<?> clz, String linkName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Relationship getRelationship(String type, String linkName, String fieldName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Relationship getRelationship(Class<?> clz, String linkName, String fieldName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> getTransitionFields(Schema schema, Object obj) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, ActionDefinition> getActionDefinitions(Object obj) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isTransitioningState(Class<?> resourceType, String state) {
            throw new UnsupportedOperationException();
        }
    }

    private static class TestRelationship implements Relationship {
        private final String name;
        private final String propertyName;
        private final Class<?> objectType;
        private final boolean listResult;
        private final RelationshipType relationshipType;

        TestRelationship(String name, String propertyName, Class<?> objectType) {
            this(name, propertyName, objectType, false, RelationshipType.REFERENCE);
        }

        TestRelationship(String name, String propertyName, Class<?> objectType, boolean listResult, RelationshipType relationshipType) {
            this.name = name;
            this.propertyName = propertyName;
            this.objectType = objectType;
            this.listResult = listResult;
            this.relationshipType = relationshipType;
        }

        @Override
        public boolean isListResult() {
            return listResult;
        }

        @Override
        public RelationshipType getRelationshipType() {
            return relationshipType;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getPropertyName() {
            return propertyName;
        }

        @Override
        public Class<?> getObjectType() {
            return objectType;
        }
    }

    private static class TestMapRelationship extends TestRelationship implements MapRelationship {
        private final Relationship selfRelationship = new TestRelationship("self", "parentId", Mapping.class);
        private final Relationship otherRelationship = new TestRelationship("other", "childId", Mapping.class);

        TestMapRelationship(String name, Class<?> objectType) {
            super(name, "parentId", objectType, true, RelationshipType.MAP);
        }

        @Override
        public Class<?> getMappingType() {
            return Mapping.class;
        }

        @Override
        public Relationship getSelfRelationship() {
            return selfRelationship;
        }

        @Override
        public Relationship getOtherRelationship() {
            return otherRelationship;
        }
    }

    public static class Parent {
        private Long childId;

        public Long getChildId() {
            return childId;
        }

        public void setChildId(Long childId) {
            this.childId = childId;
        }
    }

    public static class Child {
    }

    public static class Mapping {
    }
}
