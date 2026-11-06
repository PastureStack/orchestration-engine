package io.cattle.platform.core.dao.impl;

import static org.junit.Assert.*;
import java.sql.DriverManager;
import java.util.List;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.Test;

public class StackGraphDaoTest {
    @Test public void boundedGraphCandidatesKeepBothTenantAndStackJoinsWithoutPayloadsOrWrites() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID())) {
            var sql = DSL.using(connection);
            sql.execute("create schema cattle");
            sql.execute("create table cattle.service(id bigint,account_id bigint,environment_id bigint,kind varchar,removed timestamp)");
            sql.execute("create table cattle.service_consume_map(id bigint,account_id bigint,service_id bigint,consumed_service_id bigint,removed timestamp)");
            sql.execute("insert into cattle.service values(8,77,7,'service',null),(9,77,12,'service',null),(10,88,7,'service',null),"
                    + "(11,77,7,'loadBalancerService',null),(12,77,7,'service',current_timestamp),(13,77,8,'service',null)");
            sql.execute("insert into cattle.service_consume_map values(1,77,8,9,null),(2,77,8,10,null),(3,77,8,11,null),"
                    + "(4,77,8,12,null),(5,88,8,9,null),(6,77,13,9,null),(7,77,8,9,current_timestamp)");
            ServiceDaoImpl dao = new ServiceDaoImpl(); dao.setConfiguration(DSL.using(connection, SQLDialect.H2,
                    new Settings().withRenderQuotedNames(RenderQuotedNames.NEVER)).configuration());
            var rows = dao.getStackGraphLinks(77, 7, List.of(8L)); assertEquals(1, rows.size());
            assertEquals(8, rows.getFirst().serviceId()); assertEquals(9, rows.getFirst().consumedServiceId());
            assertTrue(dao.getStackGraphLinks(88, 7, List.of(8L)).isEmpty());
            assertTrue(dao.getStackGraphLinks(77, 8, List.of(8L)).isEmpty());
            assertTrue(dao.getStackGraphLinks(77, 7, List.of()).isEmpty());
            for (int id = 20; id < 60; id++) sql.execute("insert into cattle.service_consume_map values(?,77,8,9,null)", id);
            assertEquals(33, dao.getStackGraphLinks(77, 7, List.of(8L)).size());
            assertEquals(47, sql.fetchCount(DSL.table("cattle.service_consume_map")));
            assertThrows(IllegalArgumentException.class, () -> dao.getStackGraphLinks(77, 7, java.util.Collections.nCopies(17, 8L)));
        }
    }
}
