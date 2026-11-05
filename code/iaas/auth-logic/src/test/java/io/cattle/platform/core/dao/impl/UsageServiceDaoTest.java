package io.cattle.platform.core.dao.impl;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.Test;

public class UsageServiceDaoTest {
    private static final String UUID = "11111111-1111-1111-1111-111111111111";

    @Test public void nativeSemUsesTwoDistinctServiceSentinelAndBindsAllOwnersIncludingLegacyNullStack() throws Exception {
        try (Connection c = database()) {
            var sql = DSL.using(c);
            for (int id = 51; id <= 120; id++) {
                sql.execute("insert into cattle.service values(?,77,31,null)", id);
                sql.execute("insert into cattle.service_expose_map values(?,77,11,?,null)", id, id);
            }
            sql.execute("insert into cattle.service_expose_map values(1,77,11,51,null)");
            ServiceDaoImpl dao = dao(c); assertEquals(List.of(51L, 52L), dao.getUsageServiceIds(77, 11, UUID));
            assertTrue(dao.getUsageServiceIds(88, 11, UUID).isEmpty()); assertTrue(dao.getUsageServiceIds(77, 12, UUID).isEmpty());
            assertTrue(dao.getUsageServiceIds(77, 11, "wrong-uuid").isEmpty());
            assertEquals(71, sql.fetchCount(DSL.table("cattle.service_expose_map")));
        }
    }

    @Test public void finalNativeQueryRequiresCapturedUniqueSemServiceStackAndOptionalExactHostMapping() throws Exception {
        try (Connection c = database()) {
            var sql = DSL.using(c); ServiceDaoImpl dao = dao(c);
            sql.execute("insert into cattle.service values(51,77,31,null),(52,77,31,null),(99,88,31,null)");
            sql.execute("insert into cattle.service_expose_map values(1,77,11,51,null),(2,77,11,99,null),(3,88,11,51,null)");
            assertEquals(List.of(51L), dao.getUsageServiceIds(77, 11, UUID));
            assertTrue(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, null, null));
            assertTrue(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, 7L, 1L));
            assertFalse(dao.isUsageServiceCurrent(77, 11, "wrong-uuid", 51, 31, null, null));
            assertFalse(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, null, 1L));
            sql.execute("update cattle.service set environment_id=32 where id=51");
            assertFalse(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, null, null));
            sql.execute("update cattle.service set environment_id=31 where id=51");
            sql.execute("insert into cattle.service_expose_map values(4,77,11,52,null)");
            assertFalse(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, null, null)); // Even two Services in the same Stack stay unknown.
            sql.execute("update cattle.service_expose_map set removed=current_timestamp where id=4");
            sql.execute("update cattle.instance_host_map set host_id=8 where id=1");
            assertFalse(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, 7L, 1L));
            assertTrue(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, null, null)); // Volume has no Host placement requirement.
            sql.execute("update cattle.environment set removed=current_timestamp where id=31");
            assertFalse(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, null, null));
        }
    }

    @Test public void latePersistedStackAssignmentInvalidatesCapturedNullSemFallbackForHostAndVolume() throws Exception {
        try (Connection c = database()) {
            var sql = DSL.using(c); ServiceDaoImpl dao = dao(c);
            sql.execute("insert into cattle.service values(51,77,31,null)");
            sql.execute("insert into cattle.service_expose_map values(1,77,11,51,null)");
            assertEquals(List.of(51L), dao.getUsageServiceIds(77, 11, UUID));
            assertTrue(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, null, null));
            assertTrue(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, 7L, 1L));
            sql.execute("update cattle.instance set environment_id=32 where id=11");
            assertFalse(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, null, null));
            assertFalse(dao.isUsageServiceCurrent(77, 11, UUID, 51, 31, 7L, 1L));
        }
    }

    private static ServiceDaoImpl dao(Connection c) {
        ServiceDaoImpl d = new ServiceDaoImpl(); d.setConfiguration(DSL.using(c, SQLDialect.H2,
                new Settings().withRenderQuotedNames(RenderQuotedNames.NEVER)).configuration()); return d;
    }
    private static Connection database() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID()); var sql = DSL.using(c);
        sql.execute("create schema cattle");
        sql.execute("create table cattle.instance(id bigint,account_id bigint,uuid varchar,kind varchar,removed timestamp,environment_id bigint)");
        sql.execute("create table cattle.service(id bigint,account_id bigint,environment_id bigint,removed timestamp)");
        sql.execute("create table cattle.service_expose_map(id bigint,account_id bigint,instance_id bigint,service_id bigint,removed timestamp)");
        sql.execute("create table cattle.environment(id bigint,account_id bigint,removed timestamp)");
        sql.execute("create table cattle.host(id bigint,account_id bigint,removed timestamp)");
        sql.execute("create table cattle.instance_host_map(id bigint,host_id bigint,instance_id bigint,removed timestamp)");
        sql.execute("insert into cattle.instance values(11,77,?,'container',null,null)", UUID);
        sql.execute("insert into cattle.environment values(31,77,null),(32,77,null)"); sql.execute("insert into cattle.host values(7,77,null),(8,77,null)");
        sql.execute("insert into cattle.instance_host_map values(1,7,11,null)"); return c;
    }
}
