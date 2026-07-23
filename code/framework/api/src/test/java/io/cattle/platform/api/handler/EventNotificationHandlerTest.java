package io.cattle.platform.api.handler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class EventNotificationHandlerTest {

    @Test
    public void eventNotificationSettingsReadDynamicValuesThroughWrapper() {
        final String key = "api.event.change.exclude.types";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "auditLog,setting");

            EventNotificationSettings settings = ArchaiusEventNotificationSettings.create();

            assertTrue(settings.excludeTypes().contains("auditLog"));
            assertTrue(settings.excludeTypes().contains("setting"));
        } finally {
            clearProperty(key);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingEventNotificationSettings() {
        new EventNotificationHandler(null);
    }

    @Test
    public void loadsInjectedExcludeTypesOnInit() {
        MutableEventNotificationSettings settings = new MutableEventNotificationSettings("auditLog", "setting");
        EventNotificationHandler handler = new EventNotificationHandler(settings);

        handler.init();

        assertTrue(handler.isExcludedType("auditLog"));
        assertTrue(handler.isExcludedType("setting"));
        assertFalse(handler.isExcludedType("host"));
    }

    @Test
    public void reloadsInjectedExcludeTypesFromCallback() {
        MutableEventNotificationSettings settings = new MutableEventNotificationSettings("auditLog");
        EventNotificationHandler handler = new EventNotificationHandler(settings);

        handler.init();
        assertTrue(handler.isExcludedType("auditLog"));
        assertFalse(handler.isExcludedType("host"));

        settings.setExcludeTypes("host");
        settings.runCallback();

        assertFalse(handler.isExcludedType("auditLog"));
        assertTrue(handler.isExcludedType("host"));
    }

    private static void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static final class MutableEventNotificationSettings implements EventNotificationSettings {
        private List<String> excludeTypes;
        private Runnable callback;

        MutableEventNotificationSettings(String... excludeTypes) {
            setExcludeTypes(excludeTypes);
        }

        @Override
        public List<String> excludeTypes() {
            return excludeTypes;
        }

        @Override
        public void addExcludeTypesCallback(Runnable callback) {
            this.callback = callback;
        }

        void setExcludeTypes(String... excludeTypes) {
            this.excludeTypes = Arrays.asList(excludeTypes);
        }

        void runCallback() {
            callback.run();
        }
    }
}
