package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.core.util.PortBindingAddress;
import io.cattle.platform.core.util.PortSpec;
import java.util.ArrayList;
import java.util.List;

/** Shared typed-create PortSpec boundary. Callers retain their own native lifecycle and validation. */
final class AiCreatePorts {
    static final int MAX_PORTS = 64;

    private AiCreatePorts() { }

    static List<String> parameters(Object value) {
        if (!(value instanceof List<?> values) || values.size() > MAX_PORTS) throw invalid();
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isEmpty() || text.length() > 128 || text.indexOf('\0') >= 0) throw invalid();
            port(text); // Keep the normal native parser's specific safe validation code.
            result.add(text);
        }
        return List.copyOf(result);
    }

    static boolean assignedMatch(Object expected, Object actual) { return matches(expected, actual, true); }

    /** A stopped Container may retain an unallocated dynamic mapping; this is configuration evidence only. */
    static boolean configuredMatch(Object expected, Object actual) {
        try { return matches(expected, actual, false); }
        catch (IllegalArgumentException | io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalidReadback) { return false; }
    }

    private record BoundPort(String bind, Integer publicPort, int privatePort, String protocol) { }

    private static boolean matches(Object expected, Object actual, boolean assigned) {
        List<BoundPort> wanted = new ArrayList<>(canonical(expected));
        List<BoundPort> remaining = new ArrayList<>(canonical(actual));
        if (wanted.size() != remaining.size()) return false;
        for (int i = 0; i < remaining.size(); i++) for (int j = i + 1; j < remaining.size(); j++) {
            BoundPort left = remaining.get(i), right = remaining.get(j);
            if (left.publicPort() != null && left.publicPort().equals(right.publicPort())
                    && left.protocol().equals(right.protocol()) && PortBindingAddress.overlaps(left.bind(), right.bind())) return false;
        }
        // Fixed requests must match first, before a dynamic request can consume the same private port.
        wanted.sort(java.util.Comparator.comparing(port -> port.publicPort() == null));
        for (BoundPort port : wanted) {
            int match = -1;
            for (int i = 0; i < remaining.size(); i++) {
                BoundPort candidate = remaining.get(i);
                boolean allocated = candidate.publicPort() != null && candidate.publicPort() >= 1 && candidate.publicPort() <= 65535;
                if ((allocated || !assigned && candidate.publicPort() == null && port.publicPort() == null)
                        && (port.publicPort() == null || port.publicPort().equals(candidate.publicPort()))
                        && port.privatePort() == candidate.privatePort() && port.protocol().equals(candidate.protocol())
                        && port.bind().equals(candidate.bind())) { match = i; break; }
            }
            if (match < 0) return false;
            remaining.remove(match);
        }
        return remaining.isEmpty();
    }

    private static List<BoundPort> canonical(Object value) {
        if (value == null) return List.of();
        List<BoundPort> result = new ArrayList<>();
        for (String text : parameters(value)) {
            PortSpec spec = port(text);
            result.add(new BoundPort(PortBindingAddress.normalize(spec.getIpAddress()), spec.getPublicPort(),
                    spec.getPrivatePort(), spec.getProtocol()));
        }
        return result;
    }

    private static PortSpec port(String text) {
        try { return new PortSpec(text); }
        catch (NumberFormatException invalidNativeNumber) { throw AiResourceChangeDescriptor.error(PortSpec.WRONG_FORMAT); }
    }

    private static io.github.ibuildthecloud.gdapi.exception.ClientVisibleException invalid() {
        return AiResourceChangeDescriptor.error("ResourceChangeInputInvalid");
    }
}
