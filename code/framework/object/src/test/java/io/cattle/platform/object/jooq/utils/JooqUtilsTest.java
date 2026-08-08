package io.cattle.platform.object.jooq.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.object.meta.ActionDefinition;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.meta.Relationship;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jooq.DSLContext;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.TableField;
import org.jooq.UniqueKey;
import org.jooq.UpdatableRecord;
import org.jooq.impl.DSL;
import org.jooq.impl.Internal;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;
import org.jooq.impl.UpdatableRecordImpl;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.Test;

public class JooqUtilsTest {

    @Test
    public void getRecordClassResolvesSchemaInterfaceToRecordClass() {
        TestSchemaFactory factory = new TestSchemaFactory(TestResource.class, TestResourceRecord.class);

        Class<?> recordClass = JooqUtils.getRecordClass(factory, TestResource.class);

        assertSame(TestResourceRecord.class, recordClass);
    }

    @Test
    public void getRecordClassKeepsRecordClassPassthrough() {
        Class<?> recordClass = JooqUtils.getRecordClass(null, TestResourceRecord.class);

        assertSame(TestResourceRecord.class, recordClass);
    }

    @Test(expected = IllegalArgumentException.class)
    public void getRecordClassRejectsSchemaClassThatIsNotARecord() {
        TestSchemaFactory factory = new TestSchemaFactory(BadResource.class, BadResource.class);

        JooqUtils.getRecordClass(factory, BadResource.class);
    }

    @Test
    public void getTableFieldReturnsJooqFieldFromMetadata() {
        TestObjectMetaDataManager metaData = new TestObjectMetaDataManager(TestResourceTable.TEST.ID);

        TableField<?, ?> field = JooqUtils.getTableField(metaData, "testResource", "id");

        assertSame(TestResourceTable.TEST.ID, field);
    }

    @Test
    public void getTableFieldReturnsNullForNonJooqMetadata() {
        TestObjectMetaDataManager metaData = new TestObjectMetaDataManager("id");

        assertNull(JooqUtils.getTableField(metaData, "testResource", "id"));
    }

    @Test
    public void findByIdConvertsStringIdThroughPrimaryKeyConverter() {
        CapturingProvider provider = new CapturingProvider();
        DSLContext context = DSL.using(new MockConnection(provider), SQLDialect.MARIADB);
        TestResourceRecord record = new TestResourceRecord();
        record.set(TestResourceTable.TEST.ID, Long.valueOf(42L));
        Result<TestResourceRecord> result = context.newResult(TestResourceTable.TEST);
        result.add(record);
        provider.result = result;

        TestResourceRecord found = JooqUtils.findById(context, TestResourceRecord.class, "42");

        assertEquals(Long.valueOf(42L), found.get(TestResourceTable.TEST.ID));
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    @Test
    public void findByIdKeepsLongIdThroughPrimaryKeyConverter() {
        CapturingProvider provider = new CapturingProvider();
        DSLContext context = DSL.using(new MockConnection(provider), SQLDialect.MARIADB);
        TestResourceRecord record = new TestResourceRecord();
        record.set(TestResourceTable.TEST.ID, Long.valueOf(42L));
        Result<TestResourceRecord> result = context.newResult(TestResourceTable.TEST);
        result.add(record);
        provider.result = result;

        TestResourceRecord found = JooqUtils.findById(context, TestResourceRecord.class, Long.valueOf(42L));

        assertEquals(Long.valueOf(42L), found.get(TestResourceTable.TEST.ID));
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    @Test
    public void findRecordByIdAcceptsErasedRecordClassForSchemaResolvedCalls() {
        CapturingProvider provider = new CapturingProvider();
        DSLContext context = DSL.using(new MockConnection(provider), SQLDialect.MARIADB);
        TestResourceRecord record = new TestResourceRecord();
        record.set(TestResourceTable.TEST.ID, Long.valueOf(42L));
        Result<TestResourceRecord> result = context.newResult(TestResourceTable.TEST);
        result.add(record);
        provider.result = result;
        Class<?> recordClass = JooqUtils.getRecordClass(new TestSchemaFactory(TestResource.class, TestResourceRecord.class),
                TestResource.class);

        UpdatableRecord<?> found = JooqUtils.findRecordById(context, recordClass, "42");

        assertTrue(recordClass.isInstance(found));
        assertEquals(Long.valueOf(42L), found.get(TestResourceTable.TEST.ID));
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
    }

    @Test
    public void toConditionsBindsValuesThroughDynamicFieldType() {
        CapturingProvider provider = new CapturingProvider();
        DSLContext context = DSL.using(new MockConnection(provider), SQLDialect.MARIADB);
        Result<TestResourceRecord> result = context.newResult(TestResourceTable.TEST);
        provider.result = result;
        TestObjectMetaDataManager metaData = new TestObjectMetaDataManager(TestResourceTable.TEST.ID);

        context.selectFrom(TestResourceTable.TEST)
                .where(JooqUtils.toConditions(metaData, "testResource", Collections.<Object, Object>singletonMap("id", "42")))
                .fetch();

        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
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

    private static class CapturingProvider implements MockDataProvider {
        private Result<TestResourceRecord> result;
        private Object[] bindings;
        private String sql;

        @Override
        public MockResult[] execute(MockExecuteContext context) throws SQLException {
            this.bindings = context.bindings();
            this.sql = context.sql();
            return new MockResult[] { new MockResult(result.size(), result) };
        }
    }

    private static class BadResource {
    }

    private static class TestSchemaFactory implements SchemaFactory {
        private final Class<?> resourceClass;
        private final Class<?> schemaClass;
        private final SchemaImpl schema = new SchemaImpl();

        TestSchemaFactory(Class<?> resourceClass, Class<?> schemaClass) {
            this.resourceClass = resourceClass;
            this.schemaClass = schemaClass;
            this.schema.setId("testResource");
        }

        @Override
        public String getId() {
            return "test";
        }

        @Override
        public List<Schema> listSchemas() {
            return Collections.<Schema>singletonList(schema);
        }

        @Override
        public String getSchemaName(Class<?> clz) {
            return resourceClass.equals(clz) ? schema.getId() : null;
        }

        @Override
        public String getSchemaName(String type) {
            return type;
        }

        @Override
        public Schema getSchema(Class<?> clz) {
            return resourceClass.equals(clz) ? schema : null;
        }

        @Override
        public Schema getSchema(String type) {
            return schema.getId().equals(type) ? schema : null;
        }

        @Override
        public Class<?> getSchemaClass(String type, boolean resolveParent) {
            return getSchemaClass(type);
        }

        @Override
        public Class<?> getSchemaClass(String type) {
            return schema.getId().equals(type) ? schemaClass : null;
        }

        @Override
        public Class<?> getSchemaClass(Class<?> type) {
            return resourceClass.equals(type) ? schemaClass : null;
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

    private static class TestObjectMetaDataManager implements ObjectMetaDataManager {
        private final Object field;

        TestObjectMetaDataManager(Object field) {
            this.field = field;
        }

        @Override
        public Map<String, Relationship> getLinkRelationships(SchemaFactory schemaFactory, String type) {
            return Collections.emptyMap();
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
            return field;
        }

        @Override
        public Map<String, String> getLinks(SchemaFactory schemaFactory, String type) {
            return Collections.emptyMap();
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
}
