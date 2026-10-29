package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.*;

import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ArchiveEligibilityTransactionTest {
    @Test public void shippedOwnerReadAdviceSeesCommittedRelationsWithoutReplacingTheLockedTarget() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:archive-read-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate sql = new JdbcTemplate(source);
        sql.execute("create table related_instance (id bigint primary key, state varchar(32))");
        sql.update("insert into related_instance values (1, 'stopped')");
        ArchiveValidationFilter owner = new ArchiveValidationFilter();
        owner.objectManager = (ObjectManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ObjectManager.class},
                (proxy, method, args) -> {
                    if ("loadResource".equals(method.getName()) && args[0] == Stack.class) return null;
                    throw new AssertionError("Unexpected target reload or owner lookup: " + method.getName());
                });
        owner.instances = new ArchiveValidationFilter.ArchiveInstances() {
            @Override public List<? extends Instance> related(Object resource) {
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                assertEquals(Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED),
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                InstanceRecord instance = new InstanceRecord(); instance.setState(sql.queryForObject("select state from related_instance", String.class));
                return List.of(instance);
            }
        };
        ArchiveEligibilityRead reader = new ArchiveEligibilityRead(); reader.owner = () -> owner;
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("CoreTransactionManager", DataSourceTransactionManager.class, () -> manager);
            context.registerBean("archiveReader", ArchiveEligibilityRead.class, () -> reader);
            new XmlBeanDefinitionReader(context).loadBeanDefinitions(new ByteArrayResource(advice().getBytes(StandardCharsets.UTF_8)));
            context.refresh();
            ArchiveEligibilityRead advised = context.getBean(ArchiveEligibilityRead.class);
            TransactionTemplate outer = new TransactionTemplate(manager);
            outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            outer.executeWithoutResult(status -> {
                Connection original = DataSourceUtils.getConnection(source);
                assertEquals("stopped", sql.queryForObject("select state from related_instance", String.class));
                try (Connection writer = source.getConnection(); var statement = writer.createStatement()) {
                    statement.executeUpdate("update related_instance set state='running'");
                } catch (java.sql.SQLException error) { throw new AssertionError(error); }
                assertEquals("the existing RR snapshot is demonstrably stale", "stopped",
                        sql.queryForObject("select state from related_instance", String.class));
                ServiceRecord lockedTarget = new ServiceRecord(); lockedTarget.setId(7L); lockedTarget.setState("inactive");
                ClientVisibleException rejected = assertThrows(ClientVisibleException.class, () -> advised.validate(lockedTarget, true));
                assertEquals("ArchiveRequiresStopped", rejected.getCode());
                assertSame(original, DataSourceUtils.getConnection(source));
                assertEquals(Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ),
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                // Restore does not inspect or start the related running workload.
                advised.validate(lockedTarget, false);
                assertEquals("inactive", lockedTarget.getState());
            });
        }
    }

    private String advice() throws Exception {
        String shipped;
        try (var input = getClass().getResourceAsStream("/META-INF/cattle/legacy/spring-legacy-context.xml")) {
            assertNotNull(input); shipped = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = shipped.indexOf("<tx:advice id=\"ArchiveEligibilityReadAdvice\"");
        String advice = shipped.substring(start, shipped.indexOf("</tx:advice>", start) + "</tx:advice>".length());
        start = shipped.indexOf("<aop:advisor advice-ref=\"ArchiveEligibilityReadAdvice\"");
        String advisor = shipped.substring(start, shipped.indexOf("/>", start) + 2);
        return "<beans xmlns=\"http://www.springframework.org/schema/beans\" xmlns:tx=\"http://www.springframework.org/schema/tx\""
                + " xmlns:aop=\"http://www.springframework.org/schema/aop\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\""
                + "http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd "
                + "http://www.springframework.org/schema/tx https://www.springframework.org/schema/tx/spring-tx.xsd "
                + "http://www.springframework.org/schema/aop https://www.springframework.org/schema/aop/spring-aop.xsd\">"
                + advice + "<aop:config>" + advisor + "</aop:config></beans>";
    }
}
