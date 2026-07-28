package io.cattle.platform.core.cleanup;

import static io.cattle.platform.core.model.tables.AgentTable.AGENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import org.junit.Test;

public class TableCleanupTest {

    @Test
    public void countBindMarkersForCleanupQueries() {
        assertEquals(2, TableCleanup.bindMarkerCount(TableCleanup.MOUNT_DELETE_QUERY));
        assertEquals(2, TableCleanup.bindMarkerCount(TableCleanup.H2_MOUNT_DELETE_QUERY));
        assertEquals(0, TableCleanup.bindMarkerCount(TableCleanup.SECRET_CLEANUP_VSPM_QUERY));
        assertEquals(0, TableCleanup.bindMarkerCount(TableCleanup.SECRET_CLEAUP_VOLUME_QUERY));
    }

    @Test
    public void cleanableTableValidatesFieldTypesWithoutSqlCast() {
        CleanableTable table = CleanableTable.from(AGENT);

        assertEquals(Long.class, table.idField.getType());
        assertEquals(Date.class, table.removeField.getType());

        String sql = DSL.using(SQLDialect.MARIADB)
                .select(table.idField)
                .from(table.table)
                .where(table.removeField.isNull())
                .getSQL();

        assertFalse(sql.toLowerCase().contains("cast("));
    }

    @Test
    public void cleanableTableRejectsUnexpectedFieldType() {
        try {
            CleanableTable.requireFieldType(AGENT.REMOVED, Long.class);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("agent"));
            assertTrue(e.getMessage().contains("removed"));
            assertTrue(e.getMessage().contains("java.lang.Long"));
            assertTrue(e.getMessage().contains("java.util.Date"));
            return;
        }

        throw new AssertionError("Expected field type validation to reject mismatched type");
    }
}
