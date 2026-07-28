package io.cattle.platform.util.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class HttpResponseUtils {

    public static final int DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private static final int BUFFER_SIZE = 8192;

    private HttpResponseUtils() {
    }

    public static String readUtf8String(InputStream input, String maxBytesProperty) throws IOException {
        return readUtf8String(input, maxResponseBytes(maxBytesProperty, DEFAULT_MAX_RESPONSE_BYTES));
    }

    public static String readUtf8String(InputStream input, int maxBytes) throws IOException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be greater than zero");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxBytes - total) {
                throw new IOException("HTTP response exceeded maximum size of " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    public static int maxResponseBytes(String propertyName, int defaultMaxBytes) {
        String configured = System.getProperty(propertyName);
        if (configured == null || configured.isBlank()) {
            return defaultMaxBytes;
        }
        try {
            int value = Integer.parseInt(configured.trim());
            return value > 0 ? value : defaultMaxBytes;
        } catch (NumberFormatException e) {
            return defaultMaxBytes;
        }
    }
}
