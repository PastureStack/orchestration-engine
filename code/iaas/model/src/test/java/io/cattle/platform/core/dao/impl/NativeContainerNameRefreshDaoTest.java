package io.cattle.platform.core.dao.impl;

import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static org.junit.Assert.*;

import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.tables.records.AgentRecord;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamType;
import org.jooq.conf.Settings;
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

    @Test
    public void lifecyclePairsAreCorrelatedInBothSelectedAndCasSqlAndBindings() {
        Query selected = dao.select(agent, host, "host-uuid", DOCKER_ID);
        Query updated = dao.update(row, agent, host, "host-uuid", "new-name");
        for (Query query : Arrays.asList(selected, updated)) {
            Map<String, String> pairs = lifecyclePairs(query);
            assertEquals("active", pairs.get("running"));
            assertEquals("inactive", pairs.get("stopped"));
            assertEquals(2, pairs.size());
            assertTrue(Collections.indexOfSubList(query.getBindValues(),
                    Arrays.asList("running", "active", "stopped", "inactive")) >= 0);
            assertUniqueMappingPredicate(query.getSQL());
            String sql = query.getSQL(ParamType.INLINED).toLowerCase(Locale.ROOT);
            assertTrue(sql.contains("`service_index_id` is null"));
            assertTrue(sql.contains("`environment_id` is null"));
            assertTrue(sql.contains("binary `cattle`.`host`.`state` = binary 'active'"));
            assertTrue(sql.contains("binary `cattle`.`agent`.`state` = binary 'active'"));
            assertTrue(sql.contains("`service_expose_map`"));
        }
        assertEquals(0, calls); // Rendering is not MockConnection or database execution.
    }

    @Test
    public void renderedPairContractTruthTableAcceptsOnlyTheTwoStablePairs() {
        Map<String, String> pairs = lifecyclePairs(dao.select(agent, host, "host-uuid", DOCKER_ID));
        String[] instanceStates = {"running", "stopped", "starting", "stopping", "removed", "purged", null};
        String[] mapStates = {"active", "inactive", "requested", "activating", "deactivating", "removed", "purged", null};
        for (String instanceState : instanceStates) {
            for (String mapState : mapStates) {
                boolean expected = ("running".equals(instanceState) && "active".equals(mapState))
                        || ("stopped".equals(instanceState) && "inactive".equals(mapState));
                boolean matchesRenderedPair = instanceState != null && mapState != null
                        && mapState.equals(pairs.get(instanceState));
                assertEquals(instanceState + "/" + mapState, expected, matchesRenderedPair);
            }
        }
        assertEquals(0, calls);
        // This is the rendered pair contract's truth table, not a MariaDB SQL execution claim.
    }

    @Test
    public void emitsReadOnlyLifecycleSelectFromTheActualProductionCondition() throws Exception {
        agent.setId(17L);
        host.setId(23L);
        host.setAgentId(17L);
        // Suppress only the generated schema qualifier so Root's synthetic CTEs cannot fall through
        // to real cattle tables. The WHERE still comes from nativeNameRefreshCondition unchanged.
        dao.setConfiguration(DSL.using(SQLDialect.MARIADB,
                new Settings().withRenderSchema(false)).configuration());
        Query query = dao.select(agent, host, "host-uuid", DOCKER_ID);
        assertEquals("inactive", lifecyclePairs(query).get("stopped"));
        String sql = query.getSQL(ParamType.INLINED);
        assertTrue(sql.toLowerCase(Locale.ROOT).startsWith("select "));
        assertFalse(sql.contains("`cattle`."));
        assertFalse(sql.contains("?"));
        assertTrue(sql.contains("`instance_host_map`.`host_id` = 23"));
        assertTrue(sql.contains("`host`.`agent_id` = 17"));
        assertTrue(sql.contains("`instance`.`account_id` = 5"));
        assertEquals(0, calls);
        Path fixture = Paths.get("target", "native-name-refresh-lifecycle-select.sql");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture,
                "-- GENERATED by NativeContainerNameRefreshDaoTest from nativeNameRefreshCondition.\n"
                + "-- SELECT ONLY; renderSchema=false for synthetic instance/instance_host_map/host/agent/service_expose_map CTEs.\n"
                + "-- Synthetic bindings: host=23, agent=17, account=5, hostUuid=host-uuid, Docker ID=64 lowercase a characters.\n"
                + "-- Local tests render this SQL; Root must independently evaluate it in MariaDB with synthetic CTEs.\n"
                + sql + ";\n", StandardCharsets.UTF_8);
    }

    private static Map<String, String> lifecyclePairs(Query query) {
        String sql = query.getSQL(ParamType.INLINED).replace("`cattle`.", "")
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                // jOOQ wraps each raw binary comparison; removing only single-comparison
                // parentheses preserves the AND/OR grouping that this contract checks.
                .replaceAll("\\((binary `(?:instance|instance_host_map)`\\.`state` = binary '[^']+')\\)", "$1");
        Pattern paired = Pattern.compile("\\(\\(binary `instance`\\.`state` = binary '([^']+)'"
                + " and binary `instance_host_map`\\.`state` = binary '([^']+)'\\) or "
                + "\\(binary `instance`\\.`state` = binary '([^']+)'"
                + " and binary `instance_host_map`\\.`state` = binary '([^']+)'\\)\\)");
        Matcher match = paired.matcher(sql);
        assertTrue("Missing state-correlated pair SQL", match.find());
        Map<String, String> pairs = new HashMap<>();
        pairs.put(match.group(1), match.group(2));
        pairs.put(match.group(3), match.group(4));
        assertFalse("Duplicate state-correlated pair SQL", match.find());
        return pairs;
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

        Query select(AgentRecord agent, HostRecord host, String uuid, String dockerId) {
            return create().select(INSTANCE.ID).from(INSTANCE)
                    .where(nativeNameRefreshCondition(agent, host, uuid, dockerId));
        }

        Query update(InstanceRecord row, AgentRecord agent, HostRecord host, String uuid, String name) {
            return nativeNameRefreshUpdate(row, agent, host, uuid, name);
        }
    }
}
