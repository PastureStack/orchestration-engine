package io.cattle.platform.eventing.model;

import java.util.Map;

/** Private launch transport data is usable on the event bus, never a diagnostic payload. */
public final class EventDiagnostics {
    private static final String OMITTED = "[private Catalog launch event omitted]";
    private EventDiagnostics() { }
    public static boolean privateCatalog(Event event) {
        return event != null && event.getData() instanceof Map<?, ?> data && data.containsKey("catalogLaunchDelegation");
    }
    public static boolean privateCatalog(String serialized) {
        return serialized != null && (serialized.contains("catalogLaunchDelegation") || serialized.contains("cld1."));
    }
    public static String serialized(String value) { return privateCatalog(value) ? OMITTED : value; }
    public static Object data(Object value) {
        return value instanceof Map<?, ?> data && data.containsKey("catalogLaunchDelegation") ? OMITTED : value;
    }
}
