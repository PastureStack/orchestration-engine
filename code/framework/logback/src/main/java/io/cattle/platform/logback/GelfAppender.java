package io.cattle.platform.logback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

public class GelfAppender extends AppenderBase<ILoggingEvent> {

    private static final byte GELF_CHUNK_MAGIC_0 = 0x1e;
    private static final byte GELF_CHUNK_MAGIC_1 = 0x0f;
    private static final int GELF_MAX_CHUNKS = 128;
    private static final int GELF_MAX_CHUNK_SIZE = 8192;

    private final SecureRandom random = new SecureRandom();
    private final List<String> staticAdditionalFields = new ArrayList<String>();

    private String facility = "cattle";
    private String graylog2ServerHost = "localhost";
    private int graylog2ServerPort = 12201;
    private boolean useLoggerName = true;
    private boolean useThreadName = true;
    private int chunkThreshold = 8192;
    private String messagePattern = "%m %xEx";
    private String shortMessagePattern = "%m %xEx{2}";
    private boolean includeFullMDC = true;

    private DatagramSocket socket;
    private InetAddress graylog2Address;
    private String localHostName = "unknown";
    private PatternLayout messageLayout;
    private PatternLayout shortMessageLayout;

    @Override
    public void start() {
        try {
            graylog2Address = InetAddress.getByName(graylog2ServerHost);
            localHostName = InetAddress.getLocalHost().getHostName();
            socket = new DatagramSocket();
            messageLayout = createLayout(messagePattern);
            shortMessageLayout = createLayout(shortMessagePattern);
        } catch (UnknownHostException e) {
            addError("Failed to resolve GELF host [" + graylog2ServerHost + "]", e);
            return;
        } catch (SocketException e) {
            addError("Failed to create GELF UDP socket", e);
            return;
        }

        super.start();
    }

    @Override
    public void stop() {
        if (messageLayout != null) {
            messageLayout.stop();
        }
        if (shortMessageLayout != null) {
            shortMessageLayout.stop();
        }
        if (socket != null) {
            socket.close();
        }
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (socket == null || graylog2Address == null) {
            return;
        }

        eventObject.prepareForDeferredProcessing();

        try {
            byte[] payload = gzip(toJson(eventObject).getBytes(StandardCharsets.UTF_8));
            send(payload);
        } catch (IOException e) {
            addError("Failed to send GELF log event", e);
        } catch (RuntimeException e) {
            addError("Failed to format GELF log event", e);
        }
    }

    protected String toJson(ILoggingEvent event) {
        StringBuilder result = new StringBuilder(512);
        boolean first = true;

        result.append('{');
        first = addString(result, first, "version", "1.1");
        first = addString(result, first, "host", localHostName);
        first = addString(result, first, "short_message", format(shortMessageLayout, event));
        first = addString(result, first, "full_message", format(messageLayout, event));
        first = addNumber(result, first, "timestamp", String.format(Locale.ROOT, "%.3f", event.getTimeStamp() / 1000.0));
        first = addNumber(result, first, "level", Integer.toString(toSyslogLevel(event.getLevel())));
        first = addString(result, first, "facility", facility);

        if (useLoggerName) {
            first = addString(result, first, "_logger_name", event.getLoggerName());
        }
        if (useThreadName) {
            first = addString(result, first, "_thread_name", event.getThreadName());
        }
        if (includeFullMDC) {
            for (Map.Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
                first = addString(result, first, toAdditionalFieldName(entry.getKey()), entry.getValue());
            }
        }
        for (String field : staticAdditionalFields) {
            StaticField parsed = parseStaticField(field);
            if (parsed != null) {
                first = addString(result, first, parsed.name, parsed.value);
            }
        }

        result.append('}');
        return result.toString();
    }

    protected void send(byte[] payload) throws IOException {
        int chunkSize = Math.max(1, Math.min(chunkThreshold, GELF_MAX_CHUNK_SIZE));
        if (payload.length <= chunkSize) {
            socket.send(new DatagramPacket(payload, payload.length, graylog2Address, graylog2ServerPort));
            return;
        }

        int chunkCount = (payload.length + chunkSize - 1) / chunkSize;
        if (chunkCount > GELF_MAX_CHUNKS) {
            addError("GELF payload requires [" + chunkCount + "] UDP chunks; maximum is [" + GELF_MAX_CHUNKS + "]");
            return;
        }

        byte[] messageId = new byte[8];
        random.nextBytes(messageId);

        for (int i = 0; i < chunkCount; i++) {
            int offset = i * chunkSize;
            int length = Math.min(chunkSize, payload.length - offset);
            byte[] chunk = new byte[length + 12];

            chunk[0] = GELF_CHUNK_MAGIC_0;
            chunk[1] = GELF_CHUNK_MAGIC_1;
            System.arraycopy(messageId, 0, chunk, 2, messageId.length);
            chunk[10] = (byte) i;
            chunk[11] = (byte) chunkCount;
            System.arraycopy(payload, offset, chunk, 12, length);

            socket.send(new DatagramPacket(chunk, chunk.length, graylog2Address, graylog2ServerPort));
        }
    }

    protected byte[] gzip(byte[] payload) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(payload);
        }
        return baos.toByteArray();
    }

    protected PatternLayout createLayout(String pattern) {
        PatternLayout layout = new PatternLayout();
        layout.setContext(getContext());
        layout.setPattern(pattern);
        layout.start();
        return layout;
    }

    protected String format(PatternLayout layout, ILoggingEvent event) {
        if (layout == null) {
            return event.getFormattedMessage();
        }
        return trimTrailingLineBreaks(layout.doLayout(event));
    }

    protected String trimTrailingLineBreaks(String value) {
        if (value == null) {
            return "";
        }

        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c != '\n' && c != '\r') {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }

    protected int toSyslogLevel(Level level) {
        if (level == null) {
            return 6;
        }
        if (level.isGreaterOrEqual(Level.ERROR)) {
            return 3;
        }
        if (level.isGreaterOrEqual(Level.WARN)) {
            return 4;
        }
        if (level.isGreaterOrEqual(Level.INFO)) {
            return 6;
        }
        return 7;
    }

    protected StaticField parseStaticField(String field) {
        if (field == null) {
            return null;
        }

        int index = field.indexOf(':');
        if (index <= 0) {
            addWarn("Ignoring invalid GELF staticAdditionalField [" + field + "], expected name:value");
            return null;
        }

        String name = toAdditionalFieldName(field.substring(0, index));
        String value = field.substring(index + 1);
        if ("_".equals(name)) {
            return null;
        }

        return new StaticField(name, value);
    }

    protected String toAdditionalFieldName(String name) {
        if (name == null) {
            return "_";
        }

        String trimmed = name.trim();
        if (trimmed.startsWith("_")) {
            trimmed = trimmed.substring(1);
        }

        StringBuilder result = new StringBuilder("_");
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') {
                result.append(c);
            } else {
                result.append('_');
            }
        }
        return result.toString();
    }

    protected boolean addString(StringBuilder result, boolean first, String key, String value) {
        if (value == null) {
            return first;
        }
        addFieldPrefix(result, first, key);
        result.append('"');
        appendJsonString(result, value);
        result.append('"');
        return false;
    }

    protected boolean addNumber(StringBuilder result, boolean first, String key, String value) {
        addFieldPrefix(result, first, key);
        result.append(value);
        return false;
    }

    protected void addFieldPrefix(StringBuilder result, boolean first, String key) {
        if (!first) {
            result.append(',');
        }
        result.append('"');
        appendJsonString(result, key);
        result.append('"');
        result.append(':');
    }

    protected void appendJsonString(StringBuilder result, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
            case '"':
                result.append("\\\"");
                break;
            case '\\':
                result.append("\\\\");
                break;
            case '\b':
                result.append("\\b");
                break;
            case '\f':
                result.append("\\f");
                break;
            case '\n':
                result.append("\\n");
                break;
            case '\r':
                result.append("\\r");
                break;
            case '\t':
                result.append("\\t");
                break;
            default:
                if (c < 0x20) {
                    result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                } else {
                    result.append(c);
                }
                break;
            }
        }
    }

    public void setFacility(String facility) {
        this.facility = facility;
    }

    public void setGraylog2ServerHost(String graylog2ServerHost) {
        this.graylog2ServerHost = graylog2ServerHost;
    }

    public void setGraylog2ServerPort(int graylog2ServerPort) {
        this.graylog2ServerPort = graylog2ServerPort;
    }

    public void setHost(String host) {
        setGraylog2ServerHost(host);
    }

    public void setPort(int port) {
        setGraylog2ServerPort(port);
    }

    public void setUseLoggerName(boolean useLoggerName) {
        this.useLoggerName = useLoggerName;
    }

    public void setUseThreadName(boolean useThreadName) {
        this.useThreadName = useThreadName;
    }

    public void setChunkThreshold(int chunkThreshold) {
        this.chunkThreshold = chunkThreshold;
    }

    public void setMessagePattern(String messagePattern) {
        this.messagePattern = messagePattern;
    }

    public void setShortMessagePattern(String shortMessagePattern) {
        this.shortMessagePattern = shortMessagePattern;
    }

    public void setIncludeFullMDC(boolean includeFullMDC) {
        this.includeFullMDC = includeFullMDC;
    }

    public void setStaticAdditionalField(String staticAdditionalField) {
        addStaticAdditionalField(staticAdditionalField);
    }

    public void addStaticAdditionalField(String staticAdditionalField) {
        this.staticAdditionalFields.add(staticAdditionalField);
    }

    protected static class StaticField {
        final String name;
        final String value;

        StaticField(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
