package io.cattle.platform.logback;

import org.slf4j.Marker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;

public class MariaDbOptimisticConflictTurboFilter extends TurboFilter {

    private static final String ERROR_PACKET_LOGGER = "org.mariadb.jdbc.message.server.ErrorPacket";
    private static final String RECORD_CHANGED_MESSAGE = "Record has changed since last read";
    private static final String OPTIMISTIC_CONFLICT_CODE = "1020";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if (logger == null || level == null || !level.isGreaterOrEqual(Level.WARN)) {
            return FilterReply.NEUTRAL;
        }

        if (!ERROR_PACKET_LOGGER.equals(logger.getName())) {
            return FilterReply.NEUTRAL;
        }

        return isOptimisticConflict(format, params, t) ? FilterReply.DENY : FilterReply.NEUTRAL;
    }

    protected boolean isOptimisticConflict(String format, Object[] params, Throwable t) {
        StringBuilder message = new StringBuilder();
        append(message, format);
        if (params != null) {
            for (Object param : params) {
                append(message, param);
            }
        }

        for (Throwable current = t; current != null; current = current.getCause()) {
            append(message, current.getMessage());
        }

        String combined = message.toString();
        return combined.contains(OPTIMISTIC_CONFLICT_CODE) && combined.contains(RECORD_CHANGED_MESSAGE);
    }

    private void append(StringBuilder message, Object value) {
        if (value != null) {
            message.append(' ').append(value);
        }
    }
}
