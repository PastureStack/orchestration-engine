package io.cattle.platform.jmx;

import java.util.ArrayList;
import java.util.List;

class GraphiteMetric {

    private final String path;
    private final String value;
    private final long timestampSeconds;

    GraphiteMetric(String path, String value, long timestampSeconds) {
        this.path = sanitizePath(path);
        this.value = value;
        this.timestampSeconds = timestampSeconds;
    }

    String toLine() {
        return path + " " + value + " " + timestampSeconds + "\n";
    }

    static String path(String prefix, String... parts) {
        List<String> safeParts = new ArrayList<String>();
        addPathPart(safeParts, prefix);
        if (parts != null) {
            for (String part : parts) {
                addPathPart(safeParts, part);
            }
        }
        return join(safeParts);
    }

    static String sanitizePath(String value) {
        if (EmbeddedJmxTransPublisher.isBlank(value)) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        boolean lastWasDot = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            char safe = isGraphiteSafe(ch) ? ch : '_';
            if (safe == '.') {
                if (lastWasDot || result.length() == 0) {
                    continue;
                }
                lastWasDot = true;
            } else {
                lastWasDot = false;
            }
            result.append(safe);
        }
        int length = result.length();
        if (length > 0 && result.charAt(length - 1) == '.') {
            result.deleteCharAt(length - 1);
        }
        return result.toString();
    }

    private static void addPathPart(List<String> parts, String part) {
        String safe = sanitizePath(part);
        if (!safe.isEmpty()) {
            parts.add(safe);
        }
    }

    private static boolean isGraphiteSafe(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_'
                || ch == '-' || ch == '.';
    }

    private static String join(List<String> parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) {
                result.append('.');
            }
            result.append(part);
        }
        return result.toString();
    }
}
