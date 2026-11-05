package io.cattle.platform.core.dao.impl;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.Test;

public class HostWorkloadDaoTest {
    @Test public void boundsNativeHostMappingsBeforeMaterializationAndKeepsStoppedContainers() throws Exception {
        try (Connection c = database()) {
            var sql = DSL.using(c); sql.execute("insert into cattle.host values(7,77,null)");
            for (int id = 1; id <= 70; id++) {
                sql.execute("insert into cattle.instance(id,account_id,kind,removed,state) values(?,77,'container',null,?)",
                        id, id % 2 == 0 ? "running" : "stopped");
                sql.execute("insert into cattle.instance_host_map values(?,7,?,null)", id, id);
            }
            var rows = dao(c).getWorkloadCandidates(77, 7);
            assertEquals(51, rows.size()); assertEquals(Long.valueOf(1), rows.getFirst().getInstanceId());
            assertEquals(Long.valueOf(51), rows.getLast().getInstanceId());
            assertTrue(dao(c).isWorkloadCurrent(77, 7, 1, 1, null));
            assertFalse(dao(c).isWorkloadCurrent(77, 7, 1, 2, null));
            assertEquals(70, sql.fetchCount(DSL.table("cattle.instance_host_map")));
        }
    }

    @Test public void bindsExactHostAndAllTenantOwnersAndRechecksMovedOrRemovedMappings() throws Exception {
        try (Connection c = database()) {
            var sql = DSL.using(c);
            sql.execute("insert into cattle.host values(7,77,null),(8,77,null),(9,88,null),(10,77,current_timestamp)");
            sql.execute("insert into cattle.instance(id,account_id,kind,removed,state) values(1,77,'container',null,'stopped'),"
                    + "(2,88,'container',null,'running'),(3,77,'virtualMachine',null,'running'),"
                    + "(4,77,'container',current_timestamp,'removed')");
            sql.execute("insert into cattle.instance_host_map values(1,7,1,null),(2,7,2,null),"
                    + "(3,7,3,null),(4,7,4,null),(6,7,1,current_timestamp),"
                    + "(7,8,1,null),(8,9,1,null),(9,10,1,null)");
            var dao = dao(c); var rows = dao.getWorkloadCandidates(77, 7);
            assertEquals(1, rows.size()); assertEquals(Long.valueOf(1), rows.getFirst().getId());
            assertTrue(dao.getWorkloadCandidates(77, 9).isEmpty()); assertTrue(dao.getWorkloadCandidates(77, 10).isEmpty());
            assertFalse(dao.isWorkloadCurrent(88, 7, 1, 1, null)); assertFalse(dao.isWorkloadCurrent(77, 8, 1, 1, null));
            sql.execute("update cattle.instance set environment_id=31 where id=1");
            assertTrue(dao.isWorkloadCurrent(77, 7, 1, 1, 31L));
            sql.execute("update cattle.instance set environment_id=32 where id=1");
            assertFalse(dao.isWorkloadCurrent(77, 7, 1, 1, 31L));
            sql.execute("update cattle.instance_host_map set host_id=8 where id=1");
            assertFalse(dao.isWorkloadCurrent(77, 7, 1, 1, null));
            sql.execute("update cattle.instance_host_map set host_id=7,removed=current_timestamp where id=1");
            assertFalse(dao.isWorkloadCurrent(77, 7, 1, 1, null)); assertTrue(dao.getWorkloadCandidates(77, 7).isEmpty());
        }
    }

    private static HostDaoImpl dao(Connection c) {
        HostDaoImpl dao = new HostDaoImpl(); dao.setConfiguration(DSL.using(c, SQLDialect.H2,
                new Settings().withRenderQuotedNames(RenderQuotedNames.NEVER)).configuration()); return dao;
    }
    private static Connection database() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID()); var sql = DSL.using(c);
        sql.execute("create schema cattle"); sql.execute("create table cattle.host(id bigint,account_id bigint,removed timestamp)");
        sql.execute("create table cattle.instance(id bigint,account_id bigint,kind varchar,removed timestamp,state varchar,environment_id bigint)");
        sql.execute("create table cattle.instance_host_map(id bigint,host_id bigint,instance_id bigint,removed timestamp)");
        return c;
    }
}
