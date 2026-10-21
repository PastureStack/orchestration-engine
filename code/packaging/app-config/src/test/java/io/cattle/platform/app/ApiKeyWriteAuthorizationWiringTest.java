package io.cattle.platform.app;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractApiRequestHandler;
import io.github.ibuildthecloud.gdapi.request.handler.write.ReadWriteApiDelegate;
import io.github.ibuildthecloud.gdapi.request.handler.write.WriteAuthorization;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ApiKeyWriteAuthorizationWiringTest {
    @Test public void configuredWriteGuardsRunInsideTheActualWriteAdviceAndLateDenialRollsBack() throws Exception {
        List<String> events = new ArrayList<>();
        boolean[] deny = {false};
        IaasApiConfig config = new IaasApiConfig();
        assertTrue(config.ApiKeyWriteAuthorization() instanceof WriteAuthorization);
        WriteAuthorization guard = new WriteAuthorization() {
            @Override public void beforeWrite(ApiRequest request) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); events.add("before");
            }
            @Override public void beforeCommit(ApiRequest request) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); events.add("after");
                if (deny[0]) throw new ClientVisibleException(403);
            }
        };
        ReadWriteApiDelegate target = config.DefaultReadWriteApiDelegate(List.of(guard));
        target.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest request) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); events.add("manager");
            }
        }));
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean("CoreTransactionManager", TrackingTransactions.class, () -> new TrackingTransactions(events));
            context.registerBean("writeDelegate", ReadWriteApiDelegate.class, () -> target);
            // Reuse the shipped advice and pointcut, rather than a test-only
            // approximation which could pass after production wiring drifts.
            String source;
            try (var input = getClass().getResourceAsStream("/META-INF/cattle/legacy/spring-legacy-context.xml")) {
                assertNotNull(input); source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            int start = source.indexOf("<tx:advice id=\"ApiDelegateAdvice\"");
            assertTrue(start >= 0);
            String advice = source.substring(start, source.indexOf("</tx:advice>", start) + "</tx:advice>".length());
            start = source.indexOf("<aop:advisor advice-ref=\"ApiDelegateAdvice\"");
            assertTrue(start >= 0);
            String advisor = source.substring(start, source.indexOf("/>", start) + 2);
            String xml = "<beans xmlns=\"http://www.springframework.org/schema/beans\""
                    + " xmlns:tx=\"http://www.springframework.org/schema/tx\" xmlns:aop=\"http://www.springframework.org/schema/aop\""
                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\""
                    + "http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd "
                    + "http://www.springframework.org/schema/tx https://www.springframework.org/schema/tx/spring-tx.xsd "
                    + "http://www.springframework.org/schema/aop https://www.springframework.org/schema/aop/spring-aop.xsd\">"
                    + advice + "<aop:config>" + advisor + "</aop:config></beans>";
            new XmlBeanDefinitionReader(context).loadBeanDefinitions(new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)));
            context.refresh();
            ReadWriteApiDelegate delegate = context.getBean("writeDelegate", ReadWriteApiDelegate.class);
            delegate.write(new ApiRequest(null, null));
            assertEquals(List.of("begin", "before", "manager", "after", "commit"), events);
            events.clear(); deny[0] = true;
            assertThrows(ClientVisibleException.class, () -> delegate.write(new ApiRequest(null, null)));
            assertEquals(List.of("begin", "before", "manager", "after", "rollback"), events);
        }
    }

    public static class TrackingTransactions extends AbstractPlatformTransactionManager {
        private final List<String> events;
        TrackingTransactions(List<String> events) { this.events = events; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { events.add("begin"); }
        @Override protected void doCommit(DefaultTransactionStatus status) { events.add("commit"); }
        @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
    }
}
