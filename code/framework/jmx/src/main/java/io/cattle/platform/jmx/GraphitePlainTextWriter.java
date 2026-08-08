package io.cattle.platform.jmx;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class GraphitePlainTextWriter implements GraphiteMetricWriter {

    static final String OPTION_METRIC_PATH_PREFIX = "metricPathPrefix";
    static final String OPTION_NAME_PREFIX = "namePrefix";
    static final String OPTION_ROOT_PREFIX = "rootPrefix";

    private static final String OPTION_PROTOCOL = "protocol";
    private static final String OPTION_CONNECT_TIMEOUT_MILLIS = "connectTimeoutMillis";
    private static final String OPTION_SOCKET_TIMEOUT_MILLIS = "socketTimeoutMillis";
    private static final int DEFAULT_GRAPHITE_PORT = 2003;
    private static final int DEFAULT_TIMEOUT_MILLIS = 5000;

    private final String host;
    private final int port;
    private final String protocol;
    private final int connectTimeoutMillis;
    private final int socketTimeoutMillis;

    GraphitePlainTextWriter(String host, Integer port, Map<String, String> options) {
        if (EmbeddedJmxTransPublisher.isBlank(host)) {
            throw new IllegalArgumentException("graphite host is required");
        }
        this.host = host;
        this.port = port == null ? DEFAULT_GRAPHITE_PORT : port.intValue();
        this.protocol = option(options, OPTION_PROTOCOL, "tcp").toLowerCase(Locale.ENGLISH);
        this.connectTimeoutMillis = intOption(options, OPTION_CONNECT_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS);
        this.socketTimeoutMillis = intOption(options, OPTION_SOCKET_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS);
    }

    @Override
    public void write(List<GraphiteMetric> metrics) throws IOException {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }

        byte[] payload = payload(metrics);
        if ("udp".equals(protocol)) {
            writeUdp(payload);
        } else {
            writeTcp(payload);
        }
    }

    private void writeTcp(byte[] payload) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(socketTimeoutMillis);
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(payload);
            outputStream.flush();
        } finally {
            socket.close();
        }
    }

    private void writeUdp(byte[] payload) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        try {
            DatagramPacket packet = new DatagramPacket(payload, payload.length, new InetSocketAddress(host, port));
            socket.send(packet);
        } finally {
            socket.close();
        }
    }

    private byte[] payload(List<GraphiteMetric> metrics) {
        StringBuilder builder = new StringBuilder();
        for (GraphiteMetric metric : metrics) {
            builder.append(metric.toLine());
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String option(Map<String, String> options, String name, String defaultValue) {
        if (options == null) {
            return defaultValue;
        }
        String value = options.get(name);
        return EmbeddedJmxTransPublisher.isBlank(value) ? defaultValue : value;
    }

    private static int intOption(Map<String, String> options, String name, int defaultValue) {
        String value = option(options, name, null);
        if (EmbeddedJmxTransPublisher.isBlank(value)) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }
}
