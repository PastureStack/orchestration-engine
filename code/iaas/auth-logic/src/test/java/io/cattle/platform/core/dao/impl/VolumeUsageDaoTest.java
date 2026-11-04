package io.cattle.platform.core.dao.impl;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import io.cattle.platform.core.model.Mount;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.Test;

public class VolumeUsageDaoTest {
    @Test public void nativeSqlBoundsRelationshipCandidatesBeforeMaterializationIncludingStoppedContainers() throws Exception {
        try (Connection c = database()) {
            var sql = DSL.using(c); sql.execute("insert into cattle.volume values(7,77,null)");
            for (int id = 1; id <= 70; id++) {
                sql.execute("insert into cattle.instance values(?,77,'container',null,?)", id, id % 2 == 0 ? "running" : "stopped");
                sql.execute("insert into cattle.mount values(?,77,7,?,'/data','rw',null)", id, id);
            }
            VolumeDaoImpl dao = dao(c); List<? extends Mount> rows = dao.getUsageMounts(77, 7);
            assertEquals(51, rows.size()); assertEquals(Long.valueOf(1), rows.getFirst().getInstanceId()); assertEquals(Long.valueOf(51),
                    rows.getLast().getInstanceId());
        }
    }

    @Test public void nativeSqlExcludesEveryForeignRemovedAndNonContainerRelationshipWithoutWrites() throws Exception {
        try (Connection c = database()) {
            var sql = DSL.using(c); sql.execute("insert into cattle.volume values(7,77,null),(8,88,null),(9,77,current_timestamp)");
            sql.execute("insert into cattle.instance values(1,77,'container',null,'stopped'),"
                    + "(2,88,'container',null,'running'),(3,77,'virtualMachine',null,'running'),"
                    + "(4,77,'container',current_timestamp,'removed')");
            sql.execute("insert into cattle.mount values(1,77,7,1,'/data','ro',null),(2,77,7,2,'/foreign','rw',null),"
                    + "(3,77,7,3,'/vm','rw',null),(4,77,7,4,'/removed','rw',null),(5,88,7,1,'/foreign-owner','rw',null),"
                    + "(6,77,7,1,'/removed-mount','rw',current_timestamp),(7,77,8,1,'/foreign-volume','rw',null),"
                    + "(8,77,9,1,'/removed-volume','rw',null)");
            List<? extends Mount> rows = dao(c).getUsageMounts(77, 7); assertEquals(1, rows.size()); assertEquals("/data", rows.getFirst().getPath());
            assertTrue(dao(c).getUsageMounts(77, 8).isEmpty()); assertTrue(dao(c).getUsageMounts(77, 9).isEmpty());
            assertEquals(8, sql.fetchCount(DSL.table("cattle.mount")));
        }
    }

    private static VolumeDaoImpl dao(Connection c) { VolumeDaoImpl d = new VolumeDaoImpl(); d.setConfiguration(DSL.using(c, SQLDialect.H2,
            new Settings().withRenderQuotedNames(RenderQuotedNames.NEVER)).configuration()); return d; }
    private static Connection database() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID()); var sql = DSL.using(c);
        sql.execute("create schema cattle");
        sql.execute("create table cattle.volume(id bigint,account_id bigint,removed timestamp)");
        sql.execute("create table cattle.instance(id bigint,account_id bigint,kind varchar,removed timestamp,state varchar)");
        sql.execute("create table cattle.mount(id bigint,account_id bigint,volume_id bigint,instance_id bigint,"
                + "path varchar,permissions varchar,removed timestamp)"); return c;
    }
}
