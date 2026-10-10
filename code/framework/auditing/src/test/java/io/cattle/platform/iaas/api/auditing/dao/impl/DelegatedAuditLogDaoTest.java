package io.cattle.platform.iaas.api.auditing.dao.impl;

import static org.junit.Assert.*;

import io.cattle.platform.core.model.AuditLog;
import io.cattle.platform.core.model.tables.AuditLogTable;
import io.cattle.platform.core.model.tables.AccountTable;
import io.cattle.platform.core.model.tables.records.AuditLogRecord;
import io.cattle.platform.db.jooq.converter.DataConverter;
import io.cattle.platform.object.util.DataUtils;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.TransactionContext;
import org.jooq.TransactionListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.Test;

public class DelegatedAuditLogDaoTest {
    @Test
    public void databaseRowLockAndTransactionKeepFirstTerminalAcrossDaoRestart() {
        String eventId = "a".repeat(64);
        AtomicInteger inserts = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AtomicBoolean inTransaction = new AtomicBoolean();
        AtomicBoolean locked = new AtomicBoolean();
        AtomicReference<AuditLogRecord> stored = new AtomicReference<>();
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(SQLDialect.MYSQL);
        configuration.set(new TransactionListener() {
            @Override public void beginEnd(TransactionContext context) { inTransaction.set(true); locked.set(false); }
            @Override public void commitEnd(TransactionContext context) { commits.incrementAndGet(); inTransaction.set(false); }
        });
        configuration.set(new MockConnection(context -> {
            assertTrue("Every SQL statement belongs to the same transaction", inTransaction.get());
            String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
            if (sql.contains("for update")) {
                assertTrue(sql.contains("account"));
                assertEquals(2L, context.bindings()[0]);
                locked.set(true);
                Result<Record1<Long>> rows = DSL.using(SQLDialect.MYSQL).newResult(AccountTable.ACCOUNT.ID);
                rows.add(DSL.using(SQLDialect.MYSQL).newRecord(AccountTable.ACCOUNT.ID).values(2L));
                return new MockResult[]{new MockResult(1, rows)};
            }
            assertTrue("Lookup and insert follow the principal row lock", locked.get());
            assertTrue(sql.contains("audit_log"));
            if (sql.startsWith("insert")) {
                Object[] values = context.bindings();
                assertEquals("api.stream.terminal." + eventId, values[2]);
                assertEquals("auditLog", values[4]);
                assertNotNull("Created timestamp must be populated", values[5]);
                AuditLogRecord row = new AuditLogRecord();
                row.setId(1L);
                row.setEventType((String) values[2]);
                row.setData(new DataConverter().from((String) values[3]));
                stored.set(row);
                inserts.incrementAndGet();
                return new MockResult[]{new MockResult(1)};
            }
            assertTrue(sql.contains("event_type"));
            assertEquals("api.stream.terminal." + eventId, context.bindings()[0]);
            Result<AuditLogRecord> rows = DSL.using(SQLDialect.MYSQL).newResult(AuditLogTable.AUDIT_LOG);
            if (stored.get() != null) rows.add(stored.get());
            return new MockResult[]{new MockResult(rows.size(), rows)};
        }));
        AuditLog first = terminal(dao(configuration), eventId, "SUCCEEDED");
        AuditLog repeated = terminal(dao(configuration), eventId, "FAILED");
        assertEquals(1, inserts.get());
        assertEquals(2, commits.get());
        assertEquals(first.getId(), repeated.getId());
        assertEquals("SUCCEEDED", DataUtils.getFields(repeated).get("outcome"));
    }

    private static AuditLogDaoImpl dao(DefaultConfiguration configuration) {
        AuditLogDaoImpl dao = new AuditLogDaoImpl();
        dao.setConfiguration(configuration);
        return dao;
    }

    private static AuditLog terminal(AuditLogDaoImpl dao, String eventId, String outcome) {
        return dao.createDelegatedOnce(eventId, "container", 12L, Map.of("eventId", eventId, "outcome", outcome),
                null, 1L, 2L, "api.container.exec", "BasicAuth", 0L, null, "");
    }
}
