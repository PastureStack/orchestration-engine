package io.cattle.platform.db.jooq.logging;

import java.io.Serializable;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.tools.JooqLogger;

public class LoggerListener implements ExecuteListener, Serializable {

    private static final long serialVersionUID = 1991892878733157263L;

    private static final JooqLogger log = JooqLogger.getLogger(org.jooq.tools.LoggerListener.class);
    private static final String START_NANOS = LoggerListener.class.getName() + ".startNanos";

    long maxLength = 1000;

    @Override
    public void start(ExecuteContext ctx) {
        ctx.data(START_NANOS, System.nanoTime());
    }

    @Override
    public void renderEnd(ExecuteContext ctx) {
        if (!log.isDebugEnabled()) return;
        int batchSize = ctx.batchSQL() == null ? 0 : ctx.batchSQL().length;
        log.debug("Executing query", "type=" + ctx.type() + ", batchSize=" + batchSize);
    }

    @Override
    public void executeEnd(ExecuteContext ctx) {
        if (log.isDebugEnabled()) log.debug("Query completed", "type=" + ctx.type() + ", rows=" + ctx.rowsLarge());
    }

    @Override
    public void exception(ExecuteContext ctx) {
        // Exceptions can embed SQL, bind values or result data. Keep only the
        // operation type; never pass the original throwable to a logger.
        if (log.isDebugEnabled()) log.debug("Query failed", "type=" + ctx.type());
    }

    @Override
    public void end(ExecuteContext ctx) {
        Object start = ctx.data(START_NANOS);
        if (log.isDebugEnabled() && start instanceof Long nanos)
            log.debug("Query finished", "type=" + ctx.type() + ", elapsedMs=" + Math.max(0, System.nanoTime() - nanos) / 1_000_000);
    }

    /** Retained for bean compatibility; this property never enables SQL or value logging. */
    public long getMaxLength() {
        return maxLength;
    }

    /** Retained for bean compatibility; this property never enables SQL or value logging. */
    public void setMaxLength(long maxLength) {
        this.maxLength = maxLength;
    }

}
