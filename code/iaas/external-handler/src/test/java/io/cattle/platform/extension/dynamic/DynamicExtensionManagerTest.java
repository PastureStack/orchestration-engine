package io.cattle.platform.extension.dynamic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.util.type.Priority;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class DynamicExtensionManagerTest {

    @Test
    public void mergesDynamicExtensionsByPriorityWithoutChangingStaticOrder() {
        DynamicExtensionManager manager = new DynamicExtensionManager();
        RecordingDynamicHandler handler = new RecordingDynamicHandler(
                "test.processor",
                new NamedProcessor("dynamic-pre", Priority.PRE),
                new NamedProcessor("dynamic-override", Priority.DEFAULT_OVERRIDE));

        manager.addObject("dynamic.extension.handler", DynamicExtensionHandler.class, handler, "handler");
        manager.addObject("test.processor", Processor.class, new NamedProcessor("static-specific", Priority.SPECIFIC), "static-specific");
        manager.addObject("test.processor", Processor.class, new NamedProcessor("static-default", Priority.DEFAULT), "static-default");
        manager.start();

        List<Processor> values = manager.getExtensionList("test.processor", Processor.class);

        assertEquals(Arrays.asList("dynamic-pre", "static-specific", "dynamic-override", "static-default"), names(values));
        assertSame(Processor.class, handler.lastType);
    }

    @Test
    public void resolvesExpectedTypeWhenCallerUsesNullType() {
        DynamicExtensionManager manager = new DynamicExtensionManager();
        RecordingDynamicHandler handler = new RecordingDynamicHandler(
                "test.processor",
                new NamedProcessor("dynamic-pre", Priority.PRE));

        manager.addObject("dynamic.extension.handler", DynamicExtensionHandler.class, handler, "handler");
        manager.addObject("test.processor", Processor.class, new NamedProcessor("static-specific", Priority.SPECIFIC), "static-specific");
        manager.start();

        List<?> values = manager.getExtensionList("test.processor", null);

        assertEquals(Arrays.asList("dynamic-pre", "static-specific"), names(values));
        assertSame(Processor.class, handler.lastType);
    }

    @Test
    public void listingDynamicHandlersDoesNotReenterDynamicMerge() {
        DynamicExtensionManager manager = new DynamicExtensionManager();
        RecordingDynamicHandler handler = new RecordingDynamicHandler("test.processor");

        manager.addObject("dynamic.extension.handler", DynamicExtensionHandler.class, handler, "handler");
        manager.start();

        List<DynamicExtensionHandler> values = manager.getExtensionList(DynamicExtensionHandler.class);

        assertEquals(1, values.size());
        assertSame(handler, values.get(0));
        assertEquals(0, handler.calls);
    }

    private static List<String> names(List<?> values) {
        List<String> result = new java.util.ArrayList<String>(values.size());
        for (Object value : values) {
            result.add(Processor.class.cast(value).name());
        }
        return result;
    }

    private interface Processor {
        String name();
    }

    private static class NamedProcessor implements Processor, Priority {
        private final String name;
        private final int priority;

        NamedProcessor(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int getPriority() {
            return priority;
        }
    }

    private static class RecordingDynamicHandler implements DynamicExtensionHandler {
        private final String expectedKey;
        private final List<?> values;
        private Class<?> lastType;
        private int calls;

        RecordingDynamicHandler(String expectedKey, Object... values) {
            this.expectedKey = expectedKey;
            this.values = Arrays.asList(values);
        }

        @Override
        public List<?> getExtensionList(String key, Class<?> type) {
            calls++;
            lastType = type;
            if (expectedKey.equals(key)) {
                return values;
            }
            return Collections.emptyList();
        }
    }
}
