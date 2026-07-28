package io.cattle.platform.object.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.cattle.platform.object.meta.ActionDefinition;
import io.cattle.platform.object.meta.MapRelationship;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.meta.Relationship;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import org.junit.Test;
import org.jooq.Comment;
import org.jooq.Configuration;
import org.jooq.Constraint;
import org.jooq.ForeignKey;
import org.jooq.InverseForeignKey;
import org.jooq.Name;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.UpdatableRecord;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.Internal;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;
import org.jooq.impl.UpdatableRecordImpl;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;

public class JooqObjectManagerTest {

    @SafeVarargs
    private static <R extends org.jooq.Record> TableField<R, ?>[] tableFields(TableField<R, ?>... fields) {
        return fields;
    }

    @Test
    public void castsQueryResultsThroughCallerType() {
        TestJooqObjectManager manager = new TestJooqObjectManager();

        List<String> results = manager.cast(String.class, Arrays.asList("one", "two"));

        assertEquals(Arrays.asList("one", "two"), results);
    }

    @Test(expected = ClassCastException.class)
    public void rejectsUnexpectedQueryResultTypesAtCastBoundary() {
        TestJooqObjectManager manager = new TestJooqObjectManager();

        manager.cast(String.class, Arrays.asList("one", Integer.valueOf(2)));
    }

    @Test
    public void castsChildrenThroughCallerType() {
        TestJooqObjectManager manager = new TestJooqObjectManager();

        List<String> results = manager.castChildrenResults(String.class, Arrays.asList("one", "two"));

        assertEquals(Arrays.asList("one", "two"), results);
    }

    @Test(expected = ClassCastException.class)
    public void rejectsUnexpectedChildrenTypesAtCastBoundary() {
        TestJooqObjectManager manager = new TestJooqObjectManager();

        manager.castChildrenResults(String.class, Arrays.asList("one", Integer.valueOf(2)));
    }

    @Test
    public void setFieldsInternalReturnsOriginalObjectAtGenericBoundary() {
        TestJooqObjectManager manager = new TestJooqObjectManager();
        Object resource = new Object();

        Object result = manager.setFieldsDirect(resource, Collections.<String, Object>emptyMap());

        assertSame(resource, result);
        assertTrue(manager.toWrite.isEmpty());
    }

    @Test
    public void objectRecordMappingBoundariesKeepErasedGenericMethodShapes() throws Exception {
        Method setFields = JooqObjectManager.class.getDeclaredMethod("setFieldsInternal", Schema.class, Object.class, Map.class);
        assertTrue(Modifier.isProtected(setFields.getModifiers()));
        assertEquals(1, setFields.getTypeParameters().length);
        assertEquals("T", setFields.getTypeParameters()[0].getName());
        assertEquals("T", setFields.getGenericReturnType().getTypeName());

        Method loadResource = JooqObjectManager.class.getDeclaredMethod("loadResource", String.class, Object.class);
        assertTrue(Modifier.isProtected(loadResource.getModifiers()));
        assertEquals(1, loadResource.getTypeParameters().length);
        assertEquals("T", loadResource.getTypeParameters()[0].getName());
        assertEquals("T", loadResource.getGenericReturnType().getTypeName());
    }

    @Test
    public void objectRecordCastBoundaryStaysPrivate() throws Exception {
        Object resource = new Object();
        Method objectRecordCast = JooqObjectManager.class.getDeclaredMethod("objectRecordCast", Object.class);

        assertTrue(Modifier.isPrivate(objectRecordCast.getModifiers()));
        assertTrue(Modifier.isStatic(objectRecordCast.getModifiers()));
        objectRecordCast.setAccessible(true);
        assertSame(resource, objectRecordCast.invoke(null, resource));
    }

    @Test
    public void loadResourceByTypeAndStringIdUsesPrimaryKeyConversionBoundary() {
        CapturingProvider provider = new CapturingProvider();
        Configuration configuration = new DefaultConfiguration()
                .set(new MockConnection(provider))
                .set(SQLDialect.MARIADB);
        TestResourceRecord record = new TestResourceRecord();
        record.set(TestResourceTable.TEST.ID, Long.valueOf(42L));
        Result<TestResourceRecord> result = DSL.using(configuration).newResult(TestResourceTable.TEST);
        result.add(record);
        provider.result = result;
        TestJooqObjectManager manager = new TestJooqObjectManager();
        manager.setConfiguration(configuration);
        manager.setSchemaFactory(new TestSchemaFactory());

        TestResource loaded = manager.loadResource("testResource", "42");

        assertTrue(loaded instanceof TestResourceRecord);
        assertEquals(Long.valueOf(42L), TestResourceRecord.class.cast(loaded).get(TestResourceTable.TEST.ID));
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    @Test
    public void loadResourceByTypeAndLongIdUsesPrimaryKeyConversionBoundary() {
        CapturingProvider provider = new CapturingProvider();
        Configuration configuration = new DefaultConfiguration()
                .set(new MockConnection(provider))
                .set(SQLDialect.MARIADB);
        TestResourceRecord record = new TestResourceRecord();
        record.set(TestResourceTable.TEST.ID, Long.valueOf(42L));
        Result<TestResourceRecord> result = DSL.using(configuration).newResult(TestResourceTable.TEST);
        result.add(record);
        provider.result = result;
        TestJooqObjectManager manager = new TestJooqObjectManager();
        manager.setConfiguration(configuration);
        manager.setSchemaFactory(new TestSchemaFactory());

        TestResource loaded = manager.loadResource("testResource", Long.valueOf(42L));

        assertTrue(loaded instanceof TestResourceRecord);
        assertEquals(Long.valueOf(42L), TestResourceRecord.class.cast(loaded).get(TestResourceTable.TEST.ID));
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    @Test
    public void loadResourceByClassAndStringIdUsesPrimaryKeyConversionBoundary() {
        CapturingProvider provider = new CapturingProvider();
        Configuration configuration = new DefaultConfiguration()
                .set(new MockConnection(provider))
                .set(SQLDialect.MARIADB);
        TestResourceRecord record = new TestResourceRecord();
        record.set(TestResourceTable.TEST.ID, Long.valueOf(42L));
        Result<TestResourceRecord> result = DSL.using(configuration).newResult(TestResourceTable.TEST);
        result.add(record);
        provider.result = result;
        TestJooqObjectManager manager = new TestJooqObjectManager();
        manager.setConfiguration(configuration);
        manager.setSchemaFactory(new TestSchemaFactory());

        TestResource loaded = manager.loadResource(TestResource.class, "42");

        assertTrue(loaded instanceof TestResourceRecord);
        assertEquals(Long.valueOf(42L), TestResourceRecord.class.cast(loaded).get(TestResourceTable.TEST.ID));
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    @Test
    public void loadResourceByClassAndLongIdUsesPrimaryKeyConversionBoundary() {
        CapturingProvider provider = new CapturingProvider();
        Configuration configuration = new DefaultConfiguration()
                .set(new MockConnection(provider))
                .set(SQLDialect.MARIADB);
        TestResourceRecord record = new TestResourceRecord();
        record.set(TestResourceTable.TEST.ID, Long.valueOf(42L));
        Result<TestResourceRecord> result = DSL.using(configuration).newResult(TestResourceTable.TEST);
        result.add(record);
        provider.result = result;
        TestJooqObjectManager manager = new TestJooqObjectManager();
        manager.setConfiguration(configuration);
        manager.setSchemaFactory(new TestSchemaFactory());

        TestResource loaded = manager.loadResource(TestResource.class, Long.valueOf(42L));

        assertTrue(loaded instanceof TestResourceRecord);
        assertEquals(Long.valueOf(42L), TestResourceRecord.class.cast(loaded).get(TestResourceTable.TEST.ID));
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    @Test
    public void childrenFetchUsesForeignKeyFieldsWithoutRawJooqFetchBoundary() {
        CapturingProvider provider = new CapturingProvider();
        Configuration configuration = new DefaultConfiguration()
                .set(new MockConnection(provider))
                .set(SQLDialect.MARIADB);
        TestChildRecord child = new TestChildRecord();
        child.set(TestChildTable.TEST.ID, Long.valueOf(42L));
        child.set(TestChildTable.TEST.PARENT_ID, Long.valueOf(7L));
        Result<TestChildRecord> result = DSL.using(configuration).newResult(TestChildTable.TEST);
        result.add(child);
        provider.result = result;
        TestParentRecord parent = new TestParentRecord();
        parent.setId(Long.valueOf(7L));
        TestJooqObjectManager manager = new TestJooqObjectManager();
        manager.setConfiguration(configuration);
        manager.setSchemaFactory(new TestSchemaFactory());
        manager.setMetaDataManager(new TestObjectMetaDataManager());

        List<TestChild> children = manager.children(parent, TestChild.class);

        assertEquals(1, children.size());
        assertTrue(children.get(0) instanceof TestChildRecord);
        assertEquals(Long.valueOf(42L), TestChildRecord.class.cast(children.get(0)).get(TestChildTable.TEST.ID));
        assertEquals(Arrays.<Object>asList(Long.valueOf(7L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("test_child"));
        assertTrue(provider.sql.contains("parent_id"));
    }

    @Test
    public void mapRelationshipFetchUsesMappingTableBoundary() {
        CapturingProvider provider = new CapturingProvider();
        Configuration configuration = new DefaultConfiguration()
                .set(new MockConnection(provider))
                .set(SQLDialect.MARIADB);
        TestChildRecord child = new TestChildRecord();
        child.set(TestChildTable.TEST.ID, Long.valueOf(42L));
        Result<TestChildRecord> result = DSL.using(configuration).newResult(TestChildTable.TEST);
        result.add(child);
        provider.result = result;
        TestParentRecord parent = new TestParentRecord();
        parent.setId(Long.valueOf(7L));
        TestJooqObjectManager manager = new TestJooqObjectManager();
        manager.setConfiguration(configuration);
        manager.setSchemaFactory(new TestSchemaFactory());
        manager.setMetaDataManager(new TestObjectMetaDataManager());

        List<TestChild> children = manager.mapRelationship(parent, new TestMapRelationship());

        assertEquals(1, children.size());
        assertTrue(children.get(0) instanceof TestChildRecord);
        assertEquals(Long.valueOf(42L), TestChildRecord.class.cast(children.get(0)).get(TestChildTable.TEST.ID));
        assertEquals(Arrays.<Object>asList(Long.valueOf(7L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("join"));
        assertTrue(provider.sql.contains("child_id"));
        assertTrue(provider.sql.contains("parent_id"));
        assertTrue(provider.sql.contains("removed"));
    }

    private static class TestJooqObjectManager extends JooqObjectManager {
        private Map<Object, Object> toWrite;

        <T> List<T> cast(Class<T> type, Iterable<?> results) {
            return castQueryResults(type, results);
        }

        <T> List<T> castChildrenResults(Class<T> type, Iterable<?> results) {
            return castChildren(type, results);
        }

        <T> T setFieldsDirect(T obj, Map<String, Object> values) {
            return setFieldsInternal(null, obj, values);
        }

        List<TestChild> mapRelationship(Object obj, MapRelationship rel) {
            return castQueryResults(TestChild.class, getListByRelationshipMap(obj, rel));
        }

        @Override
        public String getType(Object obj) {
            return "test";
        }

        @Override
        protected void setFields(Schema schema, Object obj, Map<Object, Object> toWrite, List<UpdatableRecord<?>> result) {
            this.toWrite = toWrite;
        }
    }

    private interface TestResource {
    }

    public static class TestResourceRecord extends UpdatableRecordImpl<TestResourceRecord> implements TestResource {
        private static final long serialVersionUID = 1L;

        public TestResourceRecord() {
            super(TestResourceTable.TEST);
        }
    }

    public static class TestResourceTable extends TableImpl<TestResourceRecord> {
        private static final long serialVersionUID = 1L;
        private static final TestResourceTable TEST = new TestResourceTable();

        public final TableField<TestResourceRecord, Long> ID = createField(DSL.name("id"), SQLDataType.BIGINT, this, "");
        private final UniqueKey<TestResourceRecord> primaryKey = Internal.createUniqueKey(
                this, DSL.name("pk_test_resource"), ID);

        public TestResourceTable() {
            super(DSL.name("test_resource"));
        }

        @Override
        public Class<TestResourceRecord> getRecordType() {
            return TestResourceRecord.class;
        }

        @Override
        public UniqueKey<TestResourceRecord> getPrimaryKey() {
            return primaryKey;
        }
    }

    private interface TestParent {
    }

    private interface TestChild {
    }

    private interface TestChildMap {
    }

    public static class TestParentRecord extends UpdatableRecordImpl<TestParentRecord> implements TestParent {
        private static final long serialVersionUID = 1L;

        public TestParentRecord() {
            super(TestParentTable.TEST);
        }

        public Long getId() {
            return get(TestParentTable.TEST.ID);
        }

        public void setId(Long id) {
            set(TestParentTable.TEST.ID, id);
        }
    }

    public static class TestChildRecord extends UpdatableRecordImpl<TestChildRecord> implements TestChild {
        private static final long serialVersionUID = 1L;

        public TestChildRecord() {
            super(TestChildTable.TEST);
        }
    }

    public static class TestChildMapRecord extends UpdatableRecordImpl<TestChildMapRecord> implements TestChildMap {
        private static final long serialVersionUID = 1L;

        public TestChildMapRecord() {
            super(TestChildMapTable.TEST);
        }
    }

    public static class TestParentTable extends TableImpl<TestParentRecord> {
        private static final long serialVersionUID = 1L;
        private static final TestParentTable TEST = new TestParentTable();

        public final TableField<TestParentRecord, Long> ID = createField(DSL.name("id"), SQLDataType.BIGINT, this, "");
        private final UniqueKey<TestParentRecord> primaryKey = Internal.createUniqueKey(
                this, DSL.name("pk_test_parent"), ID);

        public TestParentTable() {
            super(DSL.name("test_parent"));
        }

        @Override
        public Class<TestParentRecord> getRecordType() {
            return TestParentRecord.class;
        }

        @Override
        public UniqueKey<TestParentRecord> getPrimaryKey() {
            return primaryKey;
        }
    }

    public static class TestChildTable extends TableImpl<TestChildRecord> {
        private static final long serialVersionUID = 1L;
        private static final TestChildTable TEST = new TestChildTable();

        public final TableField<TestChildRecord, Long> ID = createField(DSL.name("id"), SQLDataType.BIGINT, this, "");
        public final TableField<TestChildRecord, Long> PARENT_ID = createField(DSL.name("parent_id"), SQLDataType.BIGINT, this, "");
        private final ForeignKey<TestChildRecord, TestParentRecord> parentForeignKey = new TestChildParentForeignKey();

        public TestChildTable() {
            super(DSL.name("test_child"));
        }

        @Override
        public Class<TestChildRecord> getRecordType() {
            return TestChildRecord.class;
        }

        @Override
        public List<ForeignKey<TestChildRecord, ?>> getReferences() {
            return Collections.<ForeignKey<TestChildRecord, ?>>singletonList(parentForeignKey);
        }
    }

    private static class TestChildParentForeignKey implements ForeignKey<TestChildRecord, TestParentRecord> {
        private static final long serialVersionUID = 1L;

        @Override
        public org.jooq.impl.QOM.ForeignKeyRule getDeleteRule() {
            return null;
        }

        @Override
        public org.jooq.impl.QOM.ForeignKeyRule getUpdateRule() {
            return null;
        }

        @Override
        public InverseForeignKey<TestParentRecord, TestChildRecord> getInverseKey() {
            return null;
        }

        @Override
        public UniqueKey<TestParentRecord> getKey() {
            return TestParentTable.TEST.getPrimaryKey();
        }

        @Override
        public List<TableField<TestParentRecord, ?>> getKeyFields() {
            return Collections.<TableField<TestParentRecord, ?>>singletonList(TestParentTable.TEST.ID);
        }

        @Override
        public TableField<TestParentRecord, ?>[] getKeyFieldsArray() {
            return tableFields(TestParentTable.TEST.ID);
        }

        @Override
        public TestParentRecord fetchParent(TestChildRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<TestParentRecord> fetchParents(TestChildRecord... records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<TestParentRecord> fetchParents(Collection<? extends TestChildRecord> records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<TestChildRecord> fetchChildren(TestParentRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<TestChildRecord> fetchChildren(TestParentRecord... records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<TestChildRecord> fetchChildren(Collection<? extends TestParentRecord> records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table<TestParentRecord> parent(TestChildRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table<TestParentRecord> parents(TestChildRecord... records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table<TestParentRecord> parents(Collection<? extends TestChildRecord> records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table<TestChildRecord> children(TestParentRecord record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table<TestChildRecord> children(TestParentRecord... records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table<TestChildRecord> children(Collection<? extends TestParentRecord> records) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Table<TestChildRecord> getTable() {
            return TestChildTable.TEST;
        }

        @Override
        public List<TableField<TestChildRecord, ?>> getFields() {
            return Collections.<TableField<TestChildRecord, ?>>singletonList(TestChildTable.TEST.PARENT_ID);
        }

        @Override
        public TableField<TestChildRecord, ?>[] getFieldsArray() {
            return tableFields(TestChildTable.TEST.PARENT_ID);
        }

        @Override
        public Constraint constraint() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean enforced() {
            return true;
        }

        @Override
        public boolean nullable() {
            return true;
        }

        @Override
        public String getName() {
            return "fk_test_child_parent";
        }

        @Override
        public Name getQualifiedName() {
            return DSL.name(getName());
        }

        @Override
        public Name getUnqualifiedName() {
            return DSL.name(getName());
        }

        @Override
        public String getComment() {
            return "";
        }

        @Override
        public Comment getCommentPart() {
            return DSL.comment("");
        }

        @Override
        public Name $name() {
            return DSL.name(getName());
        }
    }

    public static class TestChildMapTable extends TableImpl<TestChildMapRecord> {
        private static final long serialVersionUID = 1L;
        private static final TestChildMapTable TEST = new TestChildMapTable();

        public final TableField<TestChildMapRecord, Long> PARENT_ID = createField(DSL.name("parent_id"), SQLDataType.BIGINT, this, "");
        public final TableField<TestChildMapRecord, Long> CHILD_ID = createField(DSL.name("child_id"), SQLDataType.BIGINT, this, "");
        public final TableField<TestChildMapRecord, java.sql.Timestamp> REMOVED = createField(DSL.name("removed"), SQLDataType.TIMESTAMP, this, "");

        public TestChildMapTable() {
            super(DSL.name("test_child_map"));
        }

        @Override
        public Class<TestChildMapRecord> getRecordType() {
            return TestChildMapRecord.class;
        }
    }

    private static class TestMapRelationship implements MapRelationship {
        private final Relationship otherRelationship = new TestRelationship("child", "child_id", TestChildMap.class);

        @Override
        public Class<?> getMappingType() {
            return TestChildMap.class;
        }

        @Override
        public Relationship getSelfRelationship() {
            return this;
        }

        @Override
        public Relationship getOtherRelationship() {
            return otherRelationship;
        }

        @Override
        public boolean isListResult() {
            return true;
        }

        @Override
        public RelationshipType getRelationshipType() {
            return RelationshipType.MAP;
        }

        @Override
        public String getName() {
            return "children";
        }

        @Override
        public String getPropertyName() {
            return "parent_id";
        }

        @Override
        public Class<?> getObjectType() {
            return TestChild.class;
        }
    }

    private static class TestRelationship implements Relationship {
        private final String name;
        private final String propertyName;
        private final Class<?> objectType;

        TestRelationship(String name, String propertyName, Class<?> objectType) {
            this.name = name;
            this.propertyName = propertyName;
            this.objectType = objectType;
        }

        @Override
        public boolean isListResult() {
            return false;
        }

        @Override
        public RelationshipType getRelationshipType() {
            return RelationshipType.REFERENCE;
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

    private static class CapturingProvider implements MockDataProvider {
        private Result<?> result;
        private Object[] bindings;
        private String sql;

        @Override
        public MockResult[] execute(MockExecuteContext context) throws SQLException {
            this.bindings = context.bindings();
            this.sql = context.sql();
            return new MockResult[] { new MockResult(result.size(), result) };
        }
    }

    private static class TestObjectMetaDataManager implements ObjectMetaDataManager {
        @Override
        public Map<String, Relationship> getLinkRelationships(SchemaFactory schemaFactory, String type) {
            return Collections.emptyMap();
        }

        @Override
        public String convertToPropertyNameString(Class<?> recordClass, Object key) {
            return String.valueOf(key);
        }

        @Override
        public String lookupPropertyNameFromFieldName(Class<?> recordClass, String fieldName) {
            return fieldName;
        }

        @Override
        public Object convertFieldNameFor(String type, Object key) {
            if ("testChild".equals(type) && ObjectMetaDataManager.ID_FIELD.equals(key)) {
                return TestChildTable.TEST.ID;
            }
            if ("testChildMap".equals(type)) {
                if ("child_id".equals(key)) {
                    return TestChildMapTable.TEST.CHILD_ID;
                }
                if ("parent_id".equals(key)) {
                    return TestChildMapTable.TEST.PARENT_ID;
                }
                if (ObjectMetaDataManager.REMOVED_FIELD.equals(key)) {
                    return TestChildMapTable.TEST.REMOVED;
                }
            }
            return null;
        }

        @Override
        public Map<String, String> getLinks(SchemaFactory schemaFactory, String type) {
            return Collections.emptyMap();
        }

        @Override
        public Relationship getRelationship(String type, String linkName) {
            return null;
        }

        @Override
        public Relationship getRelationship(Class<?> clz, String linkName) {
            return null;
        }

        @Override
        public Relationship getRelationship(String type, String linkName, String fieldName) {
            return null;
        }

        @Override
        public Relationship getRelationship(Class<?> clz, String linkName, String fieldName) {
            return null;
        }

        @Override
        public Map<String, Object> getTransitionFields(Schema schema, Object obj) {
            return Collections.emptyMap();
        }

        @Override
        public Map<String, ActionDefinition> getActionDefinitions(Object obj) {
            return Collections.emptyMap();
        }

        @Override
        public boolean isTransitioningState(Class<?> resourceType, String state) {
            return false;
        }
    }

    private static class TestSchemaFactory implements SchemaFactory {
        private final Map<String, SchemaImpl> schemas = new HashMap<String, SchemaImpl>();

        TestSchemaFactory() {
            schema("testResource");
            schema("testParent");
            schema("testChild");
            schema("testChildMap");
        }

        private SchemaImpl schema(String id) {
            SchemaImpl schema = schemas.get(id);
            if (schema == null) {
                schema = new SchemaImpl();
                schema.setId(id);
                schemas.put(id, schema);
            }
            return schema;
        }

        @Override
        public String getId() {
            return "test";
        }

        @Override
        public List<Schema> listSchemas() {
            return Collections.<Schema>unmodifiableList(Arrays.<Schema>asList(
                    schema("testResource"), schema("testParent"), schema("testChild"), schema("testChildMap")));
        }

        @Override
        public String getSchemaName(Class<?> clz) {
            if (TestResource.class.equals(clz) || TestResourceRecord.class.equals(clz)) {
                return "testResource";
            }
            if (TestParent.class.equals(clz) || TestParentRecord.class.equals(clz)) {
                return "testParent";
            }
            if (TestChild.class.equals(clz) || TestChildRecord.class.equals(clz)) {
                return "testChild";
            }
            if (TestChildMap.class.equals(clz) || TestChildMapRecord.class.equals(clz)) {
                return "testChildMap";
            }
            return null;
        }

        @Override
        public String getSchemaName(String type) {
            return type;
        }

        @Override
        public Schema getSchema(Class<?> clz) {
            String name = getSchemaName(clz);
            return name == null ? null : schema(name);
        }

        @Override
        public Schema getSchema(String type) {
            return schemas.get(type);
        }

        @Override
        public Class<?> getSchemaClass(String type, boolean resolveParent) {
            return getSchemaClass(type);
        }

        @Override
        public Class<?> getSchemaClass(String type) {
            if ("testResource".equals(type)) {
                return TestResourceRecord.class;
            }
            if ("testParent".equals(type)) {
                return TestParentRecord.class;
            }
            if ("testChild".equals(type)) {
                return TestChildRecord.class;
            }
            if ("testChildMap".equals(type)) {
                return TestChildMapRecord.class;
            }
            return null;
        }

        @Override
        public Class<?> getSchemaClass(Class<?> type) {
            Class<?> recordClass = getSchemaClass(getSchemaName(type));
            return recordClass == null ? type : recordClass;
        }

        @Override
        public String getPluralName(String type) {
            return type + "s";
        }

        @Override
        public String getSingularName(String type) {
            return type;
        }

        @Override
        public String getBaseType(String type) {
            return type;
        }

        @Override
        public Schema registerSchema(Object obj) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Schema parseSchema(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getSchemaNames(Class<?> clz) {
            return Collections.singletonList(getSchemaName(clz));
        }

        @Override
        public boolean typeStringMatches(Class<?> clz, String type) {
            return type.equals(getSchemaName(clz));
        }
    }
}
