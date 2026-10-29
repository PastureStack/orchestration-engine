package io.cattle.platform.app;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractApiRequestHandler;
import io.github.ibuildthecloud.gdapi.request.handler.write.ReadWriteApiDelegate;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ByteArrayResource;

public class AiDescriptionTransactionWiringTest {
    @Test public void productionJooqProviderKeepsLockAndManagerConnectionsInTheWriteTransaction() throws Exception {
        List<String> calls = new ArrayList<>(); boolean[] autoCommit = {true};
        Connection physical = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit[0];
                    case "setAutoCommit" -> { autoCommit[0] = (boolean) args[0]; yield null; }
                    case "commit", "rollback", "close" -> { calls.add(method.getName()); yield null; }
                    case "isReadOnly", "isClosed" -> false;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_REPEATABLE_READ;
                    case "toString" -> "physical-transaction-connection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) { calls.add("acquire"); return physical; }
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("toString".equals(method.getName())) return "transaction-data-source";
                    return null;
                });
        SystemConfig system = new SystemConfig();
        var provider = system.JooqConnectionProvider(source);
        var target = new IaasApiConfig().DefaultReadWriteApiDelegate(List.of());
        target.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest request) {
                // Both the description locking read and ordinary ObjectManager use
                // this production provider. Releasing either handle must keep the lock.
                for (String operation : List.of("locking-read", "manager-update", "readback")) {
                    Connection connection = provider.acquire();
                    try { assertFalse(connection.getAutoCommit()); calls.add(operation); }
                    catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
                    finally { provider.release(connection); }
                    assertFalse(calls.contains("commit")); assertFalse(calls.contains("close"));
                }
            }
        }));
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("CoreTransactionManager", org.springframework.jdbc.datasource.DataSourceTransactionManager.class,
                    () -> system.CoreTransactionManager(source));
            context.registerBean("writeDelegate", ReadWriteApiDelegate.class, () -> target);
            String shipped;
            try (var input = getClass().getResourceAsStream("/META-INF/cattle/legacy/spring-legacy-context.xml")) {
                assertNotNull(input); shipped = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            int start = shipped.indexOf("<tx:advice id=\"ApiDelegateAdvice\"");
            String advice = shipped.substring(start, shipped.indexOf("</tx:advice>", start) + "</tx:advice>".length());
            start = shipped.indexOf("<aop:advisor advice-ref=\"ApiDelegateAdvice\"");
            String advisor = shipped.substring(start, shipped.indexOf("/>", start) + 2);
            String xml = "<beans xmlns=\"http://www.springframework.org/schema/beans\" xmlns:tx=\"http://www.springframework.org/schema/tx\""
                    + " xmlns:aop=\"http://www.springframework.org/schema/aop\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\""
                    + "http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd "
                    + "http://www.springframework.org/schema/tx https://www.springframework.org/schema/tx/spring-tx.xsd "
                    + "http://www.springframework.org/schema/aop https://www.springframework.org/schema/aop/spring-aop.xsd\">"
                    + advice + "<aop:config>" + advisor + "</aop:config></beans>";
            new XmlBeanDefinitionReader(context).loadBeanDefinitions(new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)));
            context.refresh();
            context.getBean("writeDelegate", ReadWriteApiDelegate.class).write(new ApiRequest(null, null));
        }
        assertEquals(List.of("acquire", "locking-read", "manager-update", "readback", "commit", "close"), calls);
        assertTrue(autoCommit[0]);
    }
}
