package io.cattle.platform.logback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.configuration.AbstractConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.netflix.config.ConfigurationManager;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.status.Status;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class LogbackConfigurationTest {

    private static final String[][] OUTPUTS = {
        {"logstashConsole", "logback.output.logstash.console", Logger.ROOT_LOGGER_NAME, "LOGSTASH_CONSOLE"},
        {"logstashFile", "logback.output.logstash.file", Logger.ROOT_LOGGER_NAME, "LOGSTASH_FILE"},
        {"logstashSyslog", "logback.output.logstash.syslog", Logger.ROOT_LOGGER_NAME, "LOGSTASH_SYSLOG"},
        {"file", "logback.output.file", Logger.ROOT_LOGGER_NAME, "FILE"},
        {"error", "logback.output.error", Logger.ROOT_LOGGER_NAME, "ERROR"},
        {"events", "logback.output.events", "EventLogIn", "FILE_EVENT_IN"},
        {"gelf", "logback.output.gelf", Logger.ROOT_LOGGER_NAME, "GELF"},
        {"console", "logback.output.console", "ConsoleStatus", "CONSOLE_STATUS"}
    };

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final Map<String, Object> previousProperties = new LinkedHashMap<String, Object>();
    private Properties defaults;
    private AbstractConfiguration configuration;

    @Before
    public void configureIsolatedDefaults() throws Exception {
        defaults = loadBootstrapDefaults();
        configuration = ConfigurationManager.getConfigInstance();
        for (String name : defaults.stringPropertyNames()) {
            setProperty(name, defaults.getProperty(name));
        }
        for (String[] output : OUTPUTS) {
            setProperty(output[1], defaults.getProperty(output[1], "false"));
        }
        String logDirectory = temporaryFolder.getRoot().getAbsolutePath().replace('\\', '/') + "/";
        setProperty("cattle.home", logDirectory);
        setProperty("logback.log.dir", logDirectory);
        setProperty("logback.output.gelf.host", "127.0.0.1");
        setProperty("logback.output.logstash.syslog.host", "127.0.0.1");
    }

    @After
    public void restoreProperties() {
        for (Map.Entry<String, Object> entry : previousProperties.entrySet()) {
            if (entry.getValue() == null) {
                configuration.clearProperty(entry.getKey());
            } else {
                configuration.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    public void defaultConfigurationRetainsConsoleStatusAndFileLogging() throws Exception {
        assertEquals("true", defaults.getProperty("logback.output.console"));
        assertEquals("false", defaults.getProperty("logback.output.logstash.syslog"));
        LoggerContext context = configure(null, null);
        try {
            assertStarted(context, "ConsoleStatus", "CONSOLE_STATUS", true);
            assertStarted(context, Logger.ROOT_LOGGER_NAME, "CONSOLE", true);
            assertStarted(context, Logger.ROOT_LOGGER_NAME, "FILE", true);
            assertStarted(context, Logger.ROOT_LOGGER_NAME, "ERROR", true);
            assertStarted(context, Logger.ROOT_LOGGER_NAME, "LOGSTASH_SYSLOG", false);
            assertConfigurationStatus(context);
        } finally {
            context.stop();
        }
    }

    @Test public void logstashConsoleHonorsTrueAndFalse() throws Exception { assertOutput(OUTPUTS[0]); }
    @Test public void logstashFileHonorsTrueAndFalse() throws Exception { assertOutput(OUTPUTS[1]); }
    @Test public void logstashSyslogHonorsTrueAndFalse() throws Exception { assertOutput(OUTPUTS[2]); }
    @Test public void fileHonorsTrueAndFalse() throws Exception { assertOutput(OUTPUTS[3]); }
    @Test public void errorHonorsTrueAndFalse() throws Exception { assertOutput(OUTPUTS[4]); }
    @Test public void eventsHonorTrueAndFalse() throws Exception { assertOutput(OUTPUTS[5]); }
    @Test public void gelfHonorsTrueAndFalse() throws Exception { assertOutput(OUTPUTS[6]); }
    @Test public void consoleHonorsTrueAndFalse() throws Exception { assertOutput(OUTPUTS[7]); }

    private void assertOutput(String[] output) throws Exception {
        for (boolean enabled : new boolean[] {false, true}) {
            LoggerContext context = configure(output, Boolean.toString(enabled));
            Throwable failure = null;
            try {
                assertConfigurationStatus(context);
                for (String[] candidate : OUTPUTS) {
                    assertStarted(context, candidate[2], candidate[3], enabled && candidate == output);
                }
                if ("events".equals(output[0])) {
                    assertStarted(context, "EventLogOut", "FILE_EVENT_OUT", enabled);
                }
                if ("console".equals(output[0])) {
                    assertStarted(context, Logger.ROOT_LOGGER_NAME, "CONSOLE", enabled);
                }
            } catch (Exception | AssertionError e) {
                failure = e;
                throw e;
            } finally {
                try {
                    context.stop();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        throw e;
                    }
                    failure.addSuppressed(e);
                }
            }
        }
    }

    @Test
    public void outputBranchesStillRequireExactLowercaseTrue() throws Exception {
        for (String[] output : OUTPUTS) {
            LoggerContext context = configure(output, "TRUE");
            try {
                assertStarted(context, output[2], output[3], false);
                assertConfigurationStatus(context);
            } finally {
                context.stop();
            }
        }
    }

    @Test
    public void syslogEmitsJsonAboveThresholdOnlyWhenEnabled() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))) {
            receiver.setSoTimeout(300);
            setProperty("logback.output.logstash.syslog.port", Integer.toString(receiver.getLocalPort()));
            setProperty("logback.aggregation.level", "INFO");
            LoggerContext context = configure(OUTPUTS[2], "true");
            try {
                Logger logger = context.getLogger("io.cattle.platform.logback.SyslogProbe");
                assertTrue("DEBUG must reach the appender to test its INFO threshold", logger.isDebugEnabled());
                logger.debug("below-threshold");
                assertNoDatagram(receiver);
                logger.info("syslog-json-marker");
                DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
                receiver.receive(packet);
                String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                JsonNode json = new ObjectMapper().readTree(payload);
                assertEquals("syslog-json-marker", json.get("message").asText());
                assertEquals("INFO", json.get("level").asText());
                assertEquals(logger.getName(), json.get("logger_name").asText());
                assertConfigurationStatus(context);
            } finally {
                context.stop();
            }
            context = configure(OUTPUTS[2], "false");
            try {
                context.getLogger("io.cattle.platform.logback.SyslogProbe").info("disabled-output");
                assertNoDatagram(receiver);
                assertConfigurationStatus(context);
            } finally {
                context.stop();
            }
        }
    }

    private void assertNoDatagram(DatagramSocket receiver) throws Exception {
        try {
            receiver.receive(new DatagramPacket(new byte[8192], 8192));
            fail("No UDP output was expected");
        } catch (SocketTimeoutException expected) {
        }
    }

    private LoggerContext configure(String[] selectedOutput, String value) throws Exception {
        if (selectedOutput != null) {
            for (String[] output : OUTPUTS) {
                setProperty(output[1], "false");
            }
            setProperty(selectedOutput[1], value);
        }
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        URL resource = getClass().getClassLoader().getResource("logback/logback.xml");
        assertNotNull(resource);
        try {
            configurator.doConfigure(resource);
            return context;
        } catch (Exception e) {
            context.stop();
            throw e;
        }
    }

    private void assertStarted(LoggerContext context, String loggerName, String appenderName, boolean expected) {
        Appender<ILoggingEvent> appender = context.getLogger(loggerName).getAppender(appenderName);
        assertEquals(loggerName + "/" + appenderName, expected, appender != null);
        if (expected) {
            assertTrue(appenderName + " must be started", appender.isStarted());
        }
    }

    private void assertConfigurationStatus(LoggerContext context) {
        for (Status status : context.getStatusManager().getCopyOfStatusList()) {
            assertTrue(status.toString(), status.getEffectiveLevel() < Status.ERROR);
            String message = status.getMessage().toLowerCase(Locale.ROOT);
            assertFalse(status.toString(), message.contains("janino") || message.contains("condition attribute")
                    || message.contains("'condition' attribute"));
        }
    }

    private void setProperty(String name, String value) {
        if (!previousProperties.containsKey(name)) {
            previousProperties.put(name, configuration.getProperty(name));
        }
        configuration.setProperty(name, value);
    }

    private Properties loadBootstrapDefaults() throws Exception {
        Properties result = new Properties();
        InputStream resource = getClass().getClassLoader().getResourceAsStream("META-INF/cattle/bootstrap/defaults.properties");
        if (resource == null) {
            // Also support the module-only Maven test classpath without adding an app-config dependency.
            Path directory = Paths.get(System.getProperty("basedir", ".")).toAbsolutePath();
            while (directory != null) {
                Path source = directory.resolve("code/packaging/app-config/src/main/resources/META-INF/cattle/bootstrap/defaults.properties");
                if (Files.isRegularFile(source)) {
                    resource = Files.newInputStream(source);
                    break;
                }
                directory = directory.getParent();
            }
        }
        assertNotNull("Actual bootstrap defaults must be available", resource);
        try (InputStream input = resource) {
            result.load(input);
        }
        return result;
    }
}
