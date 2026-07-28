package io.cattle.platform.core.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.model.tables.AccountTable;
import io.cattle.platform.core.model.tables.DatabasechangeloglockTable;
import io.cattle.platform.core.model.tables.InstanceTable;
import io.cattle.platform.core.model.tables.ProcessExecutionTable;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.DatabasechangeloglockRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ProcessExecutionRecord;
import io.cattle.platform.object.impl.JooqObjectManager;
import io.cattle.platform.object.jooq.utils.JooqUtils;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Identity;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.Test;

public class JooqGeneratedModelRecordTest {

    @Test
    public void generatedRecordKeyAndDataAccessorsUseTypedTableFields() {
        AccountRecord account = new AccountRecord();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", Long.valueOf(42L));

        account.setId(Long.valueOf(42L));
        account.setData(data);

        assertSame(data, account.getData());
        Record1<Long> accountKey = account.key();
        assertSame(AccountTable.ACCOUNT.ID, accountKey.field1());
        assertEquals(Long.valueOf(42L), accountKey.value1());

        ProcessExecutionRecord execution = new ProcessExecutionRecord();
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("stdout", "ready");

        execution.setId(Long.valueOf(99L));
        execution.setLog(log);

        assertSame(log, execution.getLog());
        assertSame(log, execution.get(ProcessExecutionTable.PROCESS_EXECUTION.LOG));

        DatabasechangeloglockRecord lock = new DatabasechangeloglockRecord();
        lock.setId(Integer.valueOf(1));

        Record1<Integer> lockKey = lock.key();
        assertSame(DatabasechangeloglockTable.DATABASECHANGELOGLOCK.ID, lockKey.field1());
        assertEquals(Integer.valueOf(1), lockKey.value1());
    }

    @Test
    public void generatedTableIdentityUsesTypedTableFieldForBaseAndAlias() {
        AccountTable account = AccountTable.ACCOUNT;
        Identity<AccountRecord, Long> identity = account.getIdentity();

        assertSame(account, identity.getTable());
        assertSame(account.ID, identity.getField());
        assertEquals("id", identity.getField().getName());

        AccountTable alias = account.as("account_alias");
        Identity<AccountRecord, Long> aliasIdentity = alias.getIdentity();

        assertSame(alias, aliasIdentity.getTable());
        assertSame(alias.ID, aliasIdentity.getField());
        assertEquals("account_alias", aliasIdentity.getTable().getName());
        assertEquals("id", aliasIdentity.getField().getName());
    }

    @Test
    public void findByIdUsesGeneratedInstanceRecordPrimaryKeyForStringId() {
        CapturingProvider provider = providerReturningInstance();
        DSLContext context = DSL.using(new MockConnection(provider), SQLDialect.MARIADB);

        InstanceRecord found = JooqUtils.findById(context, InstanceRecord.class, "42");

        assertEquals(Long.valueOf(42L), found.getId());
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    @Test
    public void findByIdUsesGeneratedInstanceRecordPrimaryKeyForLongId() {
        CapturingProvider provider = providerReturningInstance();
        DSLContext context = DSL.using(new MockConnection(provider), SQLDialect.MARIADB);

        InstanceRecord found = JooqUtils.findById(context, InstanceRecord.class, Long.valueOf(42L));

        assertEquals(Long.valueOf(42L), found.getId());
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    @Test
    public void loadResourceUsesGeneratedInstanceRecordForStringId() {
        CapturingProvider provider = providerReturningInstance();
        JooqObjectManager manager = managerUsing(provider);

        Instance loaded = manager.loadResource(Instance.class, "42");

        assertTrue(loaded instanceof InstanceRecord);
        assertEquals(Long.valueOf(42L), loaded.getId());
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    @Test
    public void loadResourceUsesGeneratedInstanceRecordForLongId() {
        CapturingProvider provider = providerReturningInstance();
        JooqObjectManager manager = managerUsing(provider);

        Instance loaded = manager.loadResource(Instance.class, Long.valueOf(42L));

        assertTrue(loaded instanceof InstanceRecord);
        assertEquals(Long.valueOf(42L), loaded.getId());
        assertEquals(Arrays.<Object>asList(Long.valueOf(42L)), Arrays.asList(provider.bindings));
        assertTrue(provider.sql.contains("where"));
    }

    private static JooqObjectManager managerUsing(CapturingProvider provider) {
        JooqObjectManager manager = new JooqObjectManager();
        manager.setConfiguration(configurationUsing(provider));
        manager.setSchemaFactory(new TestSchemaFactory());
        return manager;
    }

    private static Configuration configurationUsing(CapturingProvider provider) {
        return new DefaultConfiguration()
                .set(new MockConnection(provider))
                .set(SQLDialect.MARIADB);
    }

    private static CapturingProvider providerReturningInstance() {
        CapturingProvider provider = new CapturingProvider();
        DSLContext context = DSL.using(configurationUsing(provider));
        InstanceRecord record = new InstanceRecord();
        record.setId(Long.valueOf(42L));
        Result<InstanceRecord> result = context.newResult(InstanceTable.INSTANCE);
        result.add(record);
        provider.result = result;
        return provider;
    }

    private static class CapturingProvider implements MockDataProvider {
        private Result<InstanceRecord> result;
        private Object[] bindings;
        private String sql;

        @Override
        public MockResult[] execute(MockExecuteContext context) throws SQLException {
            this.bindings = context.bindings();
            this.sql = context.sql();
            return new MockResult[] { new MockResult(result.size(), result) };
        }
    }

    private static class TestSchemaFactory implements SchemaFactory {
        private final SchemaImpl schema = new SchemaImpl();

        TestSchemaFactory() {
            this.schema.setId("instance");
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
            return Instance.class.equals(clz) || InstanceRecord.class.equals(clz) ? schema.getId() : null;
        }

        @Override
        public String getSchemaName(String type) {
            return type;
        }

        @Override
        public Schema getSchema(Class<?> clz) {
            return Instance.class.equals(clz) || InstanceRecord.class.equals(clz) ? schema : null;
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
            return schema.getId().equals(type) ? InstanceRecord.class : null;
        }

        @Override
        public Class<?> getSchemaClass(Class<?> type) {
            return Instance.class.equals(type) ? InstanceRecord.class : type;
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
