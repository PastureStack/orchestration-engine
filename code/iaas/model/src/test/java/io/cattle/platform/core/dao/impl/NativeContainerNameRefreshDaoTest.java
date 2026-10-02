package io.cattle.platform.core.dao.impl;

import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static org.junit.Assert.*;

import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.tables.records.AgentRecord;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.Before;
import org.junit.Test;

public class NativeContainerNameRefreshDaoTest {
    static final String DOCKER_ID = "a".repeat(64);
    TestDao dao;
    AgentRecord agent;
    HostRecord host;
    InstanceRecord row;
    List<InstanceRecord> rows;
    int calls;
    int updateCount;
    String lastSql;

    @Before
    public void setup() {
        agent = new AgentRecord();
        agent.setId(1L); agent.setState("active"); agent.setData(map("agentResourcesAccountId", 5L));
        host = new HostRecord();
        host.setId(1L); host.setAgentId(1L); host.setAccountId(5L); host.setState("active");
        host.setData(map("fields", map("reportedUuid", "host-uuid")));
        row = new InstanceRecord();
        row.setId(40L); row.setAccountId(5L); row.setKind("container"); row.setUuid("logical-uuid");
        row.setName("original"); row.setState("stopped"); row.setExternalId(DOCKER_ID);
        row.setNativeContainer(true); row.setSystem(false);
        row.setData(map("fields", map("labels", new HashMap<String, String>())));
        rows = Arrays.asList(row);
        updateCount = 1;
        dao = new TestDao();
        dao.setConfiguration(DSL.using(new MockConnection(ctx -> {
            calls++;
            lastSql = ctx.sql();
            if (lastSql.toLowerCase(Locale.ROOT).startsWith("select")) {
                DSLContext fixture = DSL.using(SQLDialect.MARIADB);
                Result<InstanceRecord> result = fixture.newResult(INSTANCE);
                result.addAll(rows);
                return new MockResult[] {new MockResult(result.size(), result)};
            }
            return new MockResult[] {new MockResult(updateCount, null)};
        }), SQLDialect.MARIADB).configuration());
    }

    @Test
    public void exactExistingImportedSnapshotAndNameOnlyCas() {
        Instance snapshot = dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", DOCKER_ID);
        assertNotNull(snapshot);
        assertTrue(dao.updateNativeContainerName(snapshot, agent, host, "host-uuid", "rollback-496"));
        String sql = lastSql.toLowerCase(Locale.ROOT);
        String set = sql.substring(sql.indexOf(" set "), sql.indexOf(" where "));
        assertTrue(set.contains("`name` = ?"));
        assertFalse(set.contains("`state`"));
        assertFalse(set.contains("`data`"));
        assertEquals("original", row.getName());
    }

    @Test
    public void compareAndSwapContainsEveryOriginalColumnIncludingBinaryTextAndJson() {
        String sql = dao.sql(row, agent, host, "host-uuid", "new-name").toLowerCase(Locale.ROOT);
        String where = sql.substring(sql.indexOf(" where "));
        for (Field<?> field : row.fields()) {
            assertTrue(field.getName(), where.contains("`instance`.`" + field.getName() + "`"));
        }
        assertTrue(where.contains("binary `cattle`.`instance`.`name`"));
        assertTrue(where.contains("binary `cattle`.`instance`.`data`"));
        assertTrue(where.contains("`instance`.`id` = ?"));
        assertTrue(where.contains("not exists"));
        assertTrue(where.contains("`service_expose_map`"));
        assertTrue(where.contains("`instance_host_map`.`state`"));
        assertTrue(where.contains("count(*)"));
        assertTrue(where.contains("json_type"));
        assertTrue(where.contains("`host`.`agent_id`"));
        assertTrue(where.contains("`agent`.`data`"));
    }

    @Test
    public void zeroRowCasAndSameNameDoNotReportSuccess() {
        updateCount = 0;
        assertFalse(dao.updateNativeContainerName(row, agent, host, "host-uuid", "new-name"));
        int previous = calls;
        assertFalse(dao.updateNativeContainerName(row, agent, host, "host-uuid", "original"));
        assertEquals(previous, calls);
    }

    @Test
    public void unknownDuplicateOrWrongExactDockerIdCannotBecomeSnapshot() {
        rows = java.util.Collections.emptyList();
        assertNull(dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", DOCKER_ID));
        rows = Arrays.asList(row, row);
        assertNull(dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", DOCKER_ID));
        rows = Arrays.asList(row);
        row.setExternalId("b".repeat(64));
        assertNull(dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", DOCKER_ID));
        int previous = calls;
        assertNull(dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", "a".repeat(12)));
        assertEquals(previous, calls);
    }

    @Test
    public void foreignSourceOrWrongResourceAccountNeverReachesQuery() {
        host.setAgentId(2L);
        assertNull(dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", DOCKER_ID));
        host.setAgentId(1L);
        host.setAccountId(6L);
        assertNull(dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", DOCKER_ID));
        host.setAccountId(5L);
        assertNull(dao.getNativeContainerForNameRefresh(agent, host, "foreign-host", DOCKER_ID));
        agent.setData(map("agentResourcesAccountId", "5"));
        assertNull(dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", DOCKER_ID));
        assertEquals(0, calls);
    }

    @Test
    public void managedServiceSystemEnvironmentAndLifecycleRowsAreRejected() {
        row.setNativeContainer(false); rejectRow(); row.setNativeContainer(true);
        row.setSystem(true); rejectRow(); row.setSystem(false);
        row.setServiceId(9L); rejectRow(); row.setServiceId(null);
        row.setServiceIndexId(7L); rejectRow(); row.setServiceIndexId(null);
        row.setStackId(8L); rejectRow(); row.setStackId(null);
        row.setRemoved(new Date()); rejectRow(); row.setRemoved(null);
        row.setRemoveTime(new Date()); rejectRow(); row.setRemoveTime(null);
        for (String state : Arrays.asList("removed", "purged", "purging", "starting", "updating-running")) {
            row.setState(state); rejectRow();
        }
        row.setState("stopped");
        for (String marker : Arrays.asList("io.rancher.container.uuid", "io.rancher.container.display_name",
                "io.rancher.container.name", "io.rancher.container.system", "io.rancher.stack_service.name",
                "io.rancher.service.deployment.unit", "io.rancher.service.launch.config")) {
            row.setData(map("fields", map("labels", map(marker, "logical"))));
            rejectRow();
        }
        row.setData(map("fields", map("labels", new HashMap<>(), "systemContainer", "managed"))); rejectRow();
        row.setData(map("fields", map("labels", new HashMap<>(), "serviceIndexId", 7L))); rejectRow();
        row.setData(map("fields", map("labels", map("custom", false)))); rejectRow();
    }

    @Test
    public void everyNonremovedMapMustBeUniqueAtSelectionAndAtCas() {
        assertNotNull(dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", DOCKER_ID));
        assertUniqueMappingPredicate(lastSql);
        updateCount = 0; // Another host map appeared after the selected snapshot: CAS must fail closed.
        assertFalse(dao.updateNativeContainerName(row, agent, host, "host-uuid", "new-name"));
        assertUniqueMappingPredicate(lastSql);
        assertEquals(2, calls);
    }

    private static void assertUniqueMappingPredicate(String sql) {
        String normalized = sql.toLowerCase(Locale.ROOT);
        int begin = normalized.indexOf("select count(*)");
        assertTrue(begin >= 0);
        int end = normalized.indexOf(") = ?", begin);
        assertTrue(end > begin);
        String count = normalized.substring(begin, end);
        assertTrue(count.contains("`instance_host_map`.`instance_id` = `cattle`.`instance`.`id`"));
        assertTrue(count.contains("`instance_host_map`.`removed` is null"));
        assertFalse(count.contains("`instance_host_map`.`host_id`"));
        assertFalse(count.contains("`instance_host_map`.`state`"));
    }

    private void rejectRow() {
        assertNull(dao.getNativeContainerForNameRefresh(agent, host, "host-uuid", DOCKER_ID));
        int previous = calls;
        assertFalse(dao.updateNativeContainerName(row, agent, host, "host-uuid", "new-name"));
        assertEquals(previous, calls);
    }

    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static class TestDao extends InstanceDaoImpl {
        String sql(InstanceRecord row, AgentRecord agent, HostRecord host, String uuid, String name) {
            return nativeNameRefreshUpdate(row, agent, host, uuid, name).getSQL();
        }
    }
}
