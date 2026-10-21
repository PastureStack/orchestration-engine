package io.cattle.platform.db.jooq.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteType;
import org.jooq.Query;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class LoggerListenerTest {
    @Test
    public void debugAndTraceNeverLogSqlBindsResultsOrExceptionMessages() {
        Logger logger = (Logger) LoggerFactory.getLogger(org.jooq.tools.LoggerListener.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender); logger.setLevel(Level.TRACE);
        try {
            LoggerListener listener = new LoggerListener();
            ExecuteContext context = context(ExecuteType.READ, "private-query-canary");
            listener.start(context); listener.renderEnd(context); listener.executeStart(context);
            listener.executeEnd(context); listener.recordEnd(context); listener.fetchEnd(context);
            listener.resultStart(context); listener.resultEnd(context); listener.outEnd(context);
            listener.exception(context); listener.end(context);
            listener.renderEnd(context(ExecuteType.BATCH, "private-batch-canary"));
            listener.renderEnd(context(ExecuteType.OTHER, "private-plain-sql-canary"));
            assertFalse("The diagnostic logger must remain useful", appender.list.isEmpty());
            String logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
            assertFalse(logged, logged.contains("private-"));
            assertTrue(logged, logged.contains("READ"));
            assertTrue(logged, logged.contains("BATCH"));
            assertTrue(logged, logged.contains("rows=1"));
            assertTrue(logged, logged.contains("elapsedMs="));
            assertTrue("No throwable may retain raw SQL or data", appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.setLevel(previous); logger.detachAppender(appender); appender.stop();
        }
    }

    @Test
    public void maxLengthBeanPropertyRemainsCompatible() {
        LoggerListener listener = new LoggerListener();
        listener.setMaxLength(123);
        assertEquals(123L, listener.getMaxLength());
    }

    private ExecuteContext context(ExecuteType type, String canary) {
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(SQLDialect.MYSQL);
        configuration.settings().setExecuteLogging(false);
        Query query = DSL.using(configuration).select(DSL.val(canary));
        Record1<String> record = DSL.using(configuration).newRecord(DSL.field("secret", String.class)).values("private-result-canary");
        Result<Record1<String>> result = DSL.using(configuration).newResult(DSL.field("secret", String.class));
        result.add(record);
        Map<Object, Object> data = new HashMap<>();
        return (ExecuteContext) Proxy.newProxyInstance(ExecuteContext.class.getClassLoader(), new Class<?>[] { ExecuteContext.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "type": return type;
                    case "sql": return type == ExecuteType.BATCH ? "" : "select 'private-sql-canary'";
                    case "query": return type == ExecuteType.READ ? query : null;
                    case "batchSQL": return new String[] { "select 'private-batch-canary'" };
                    case "batchQueries": return new Query[] { query };
                    case "rowsLarge": return 1L;
                    case "rows": return 1;
                    case "recordLevel": case "resultLevel": return 0;
                    case "record": return record;
                    case "result": return result;
                    case "configuration": return configuration;
                    case "dsl": return DSL.using(configuration);
                    case "exception": return new IllegalStateException("private-exception-canary");
                    case "data":
                        if (args == null || args.length == 0) return data;
                        return args.length == 1 ? data.get(args[0]) : data.put(args[0], args[1]);
                    default: return null;
                    }
                });
    }
}
