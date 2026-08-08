package io.cattle.platform.core.util;

import java.util.Locale;

/**
 * Shared host port bind-address semantics used by API preflight and allocation.
 */
public final class PortBindingAddress {

    private static final String IPV4_ANY = "0.0.0.0";
    private static final String IPV6_ANY = "::";

    private PortBindingAddress() {
    }

    public static boolean overlaps(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);

        if (isAny(a) || isAny(b)) {
            return true;
        }

        return a.equals(b);
    }

    public static String normalize(String value) {
        if (value == null) {
            return IPV4_ANY;
        }

        String normalized = value.trim().toLowerCase(Locale.ENGLISH);
        if (normalized.length() == 0 || "*".equals(normalized)) {
            return IPV4_ANY;
        }
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if ("0:0:0:0:0:0:0:0".equals(normalized)) {
            return IPV6_ANY;
        }
        return normalized;
    }

    private static boolean isAny(String value) {
        return IPV4_ANY.equals(value) || IPV6_ANY.equals(value);
    }
}
