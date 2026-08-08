package io.cattle.platform.logback;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;

import com.netflix.config.ConfigurationManager;

public class MariaDbOptimisticConflictTurboFilterTest {

    private static final String ERROR_PACKET_LOGGER = "org.mariadb.jdbc.message.server.ErrorPacket";

    private final MariaDbOptimisticConflictTurboFilter filter = new MariaDbOptimisticConflictTurboFilter();
    private final LoggerContext context = new LoggerContext();

    @Test
    public void deniesHandledOptimisticConflictWarning() {
        FilterReply result = filter.decide(null, logger(ERROR_PACKET_LOGGER), Level.WARN,
                "Error: 1020-HY000: Record has changed since last read in table 'instance'", null, null);

        assertEquals(FilterReply.DENY, result);
    }

    @Test
    public void preservesOtherMariaDbWarnings() {
        FilterReply result = filter.decide(null, logger(ERROR_PACKET_LOGGER), Level.WARN,
                "Error: 1451-23000: Cannot delete or update a parent row: a foreign key constraint fails",
                null, null);

        assertEquals(FilterReply.NEUTRAL, result);
    }

    @Test
    public void preservesMatchingMessagesFromOtherLoggers() {
        FilterReply result = filter.decide(null, logger("org.jooq.tools.LoggerListener"), Level.WARN,
                "Error: 1020-HY000: Record has changed since last read in table 'instance'", null, null);

        assertEquals(FilterReply.NEUTRAL, result);
    }

    @Test
    public void preservesInfoMessages() {
        FilterReply result = filter.decide(null, logger(ERROR_PACKET_LOGGER), Level.INFO,
                "Error: 1020-HY000: Record has changed since last read in table 'instance'", null, null);

        assertEquals(FilterReply.NEUTRAL, result);
    }

    @Test
    public void archaiusPropertyReadsConfiguredStringThroughWrapper() {
        final String key = "rc16.logback.property";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "configured-value");
            ArchaiusProperty property = new ArchaiusProperty();
            property.setName(key);

            assertEquals("configured-value", property.getPropertyValue());
        } finally {
            clearProperty(key);
        }
    }

    private Logger logger(String name) {
        return context.getLogger(name);
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }
}
