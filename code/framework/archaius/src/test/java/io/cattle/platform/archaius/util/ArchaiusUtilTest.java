package io.cattle.platform.archaius.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.sources.ArchaiusConfigFactory;

import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.MapConfiguration;
import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;
import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicDoubleProperty;
import com.netflix.config.DynamicFloatProperty;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicLongProperty;
import com.netflix.config.DynamicStringListProperty;
import com.netflix.config.DynamicStringProperty;

public class ArchaiusUtilTest {

    @After
    public void resetProvider() {
        ArchaiusUtil.resetProviderForTesting();
    }

    @Test
    public void delegatesDynamicPropertyLookupsWithExistingDefaults() {
        RecordingProvider provider = new RecordingProvider();
        ArchaiusUtil.setProviderForTesting(provider);

        assertNull(ArchaiusUtil.getLong("long.key"));
        assertEquals("long.key", provider.longKey);
        assertEquals(0L, provider.longDefault);

        assertNull(ArchaiusUtil.getInt("int.key"));
        assertEquals("int.key", provider.intKey);
        assertEquals(0, provider.intDefault);

        assertNull(ArchaiusUtil.getBoolean("bool.key"));
        assertEquals("bool.key", provider.booleanKey);
        assertFalse(provider.booleanDefault);

        assertNull(ArchaiusUtil.getDouble("double.key"));
        assertEquals("double.key", provider.doubleKey);
        assertEquals(0.0, provider.doubleDefault, 0.0);

        assertNull(ArchaiusUtil.getFloat("float.key"));
        assertEquals("float.key", provider.floatKey);
        assertEquals(0.0f, provider.floatDefault, 0.0f);

        assertNull(ArchaiusUtil.getString("string.key"));
        assertEquals("string.key", provider.stringKey);
        assertNull(provider.stringDefault);

        assertNull(ArchaiusUtil.getList("list.key"));
        assertEquals("list.key", provider.listKey);
        assertNull(provider.listDefault);

        assertNull(ArchaiusUtil.getConfiguration());

        assertEquals(Long.valueOf(11L), ArchaiusUtil.getLongProperty("wrapped.long").get());
        assertEquals("wrapped.long", provider.longPropertyKey);
        assertEquals(0L, provider.longPropertyDefault);

        assertEquals(Integer.valueOf(12), ArchaiusUtil.getIntProperty("wrapped.int").get());
        assertEquals("wrapped.int", provider.intPropertyKey);
        assertEquals(0, provider.intPropertyDefault);

        assertEquals(Boolean.TRUE, ArchaiusUtil.getBooleanProperty("wrapped.boolean").get());
        assertEquals("wrapped.boolean", provider.booleanPropertyKey);
        assertFalse(provider.booleanPropertyDefault);

        assertEquals(Double.valueOf(13.0), ArchaiusUtil.getDoubleProperty("wrapped.double").get());
        assertEquals("wrapped.double", provider.doublePropertyKey);
        assertEquals(0.0, provider.doublePropertyDefault, 0.0);

        assertEquals(Float.valueOf(14.0f), ArchaiusUtil.getFloatProperty("wrapped.float").get());
        assertEquals("wrapped.float", provider.floatPropertyKey);
        assertEquals(0.0f, provider.floatPropertyDefault, 0.0f);

        assertEquals("wrapped", ArchaiusUtil.getStringProperty("wrapped.string").get());
        assertEquals("wrapped.string", provider.stringPropertyKey);
        assertNull(provider.stringPropertyDefault);
        assertEquals(Arrays.asList("one", "two"), ArchaiusUtil.getStringListProperty("wrapped.list").get());
        assertEquals("wrapped.list", provider.stringListPropertyKey);
        assertNull(provider.stringListPropertyDefault);

        assertEquals(mapOf("one", "two"), ArchaiusUtil.getStringMapProperty("wrapped.map").get());
        assertEquals("wrapped.map", provider.stringMapPropertyKey);
        assertNull(provider.stringMapPropertyDefault);

        assertEquals(1, provider.configurationCalls);
    }

    @Test
    public void delegatesConfigPropertyLookupsWithSuppliedDefaults() {
        RecordingProvider provider = new RecordingProvider();
        ArchaiusUtil.setProviderForTesting(provider);

        assertEquals(Long.valueOf(11L), ArchaiusUtil.getLongProperty("wrapped.long", 21L).get());
        assertEquals("wrapped.long", provider.longPropertyKey);
        assertEquals(21L, provider.longPropertyDefault);

        assertEquals(Integer.valueOf(12), ArchaiusUtil.getIntProperty("wrapped.int", 22).get());
        assertEquals("wrapped.int", provider.intPropertyKey);
        assertEquals(22, provider.intPropertyDefault);

        assertEquals(Boolean.TRUE, ArchaiusUtil.getBooleanProperty("wrapped.boolean", true).get());
        assertEquals("wrapped.boolean", provider.booleanPropertyKey);
        assertTrue(provider.booleanPropertyDefault);

        assertEquals(Double.valueOf(13.0), ArchaiusUtil.getDoubleProperty("wrapped.double", 23.0).get());
        assertEquals("wrapped.double", provider.doublePropertyKey);
        assertEquals(23.0, provider.doublePropertyDefault, 0.0);

        assertEquals(Float.valueOf(14.0f), ArchaiusUtil.getFloatProperty("wrapped.float", 24.0f).get());
        assertEquals("wrapped.float", provider.floatPropertyKey);
        assertEquals(24.0f, provider.floatPropertyDefault, 0.0f);

        assertEquals("wrapped", ArchaiusUtil.getStringProperty("wrapped.string", "default").get());
        assertEquals("wrapped.string", provider.stringPropertyKey);
        assertEquals("default", provider.stringPropertyDefault);

        assertEquals(Arrays.asList("one", "two"), ArchaiusUtil.getStringListProperty("wrapped.list", "a,b").get());
        assertEquals("wrapped.list", provider.stringListPropertyKey);
        assertEquals("a,b", provider.stringListPropertyDefault);

        assertEquals(mapOf("one", "two"), ArchaiusUtil.getStringMapProperty("wrapped.map", "a=b").get());
        assertEquals("wrapped.map", provider.stringMapPropertyKey);
        assertEquals("a=b", provider.stringMapPropertyDefault);
    }


    @Test
    public void initializesProviderWithConfigurationStack() {
        RecordingProvider provider = new RecordingProvider();
        ArchaiusUtil.setProviderForTesting(provider);
        AbstractConfiguration config = ArchaiusUtil.newConfigurationStack();

        ArchaiusUtil.initialize(config);

        assertSame(config, provider.initializedConfiguration);
    }

    @Test
    public void initializesProviderWithConfigurationStackAdapter() {
        RecordingProvider provider = new RecordingProvider();
        ArchaiusUtil.setProviderForTesting(provider);
        ConfigurationStack config = ArchaiusUtil.newConfigurationStackAdapter();

        ArchaiusUtil.initialize(config);

        assertSame(config.asConfiguration(), provider.initializedConfiguration);
    }

    @Test
    public void bootstrapCreatesAndInitializesConfigurationStack() {
        RecordingProvider provider = new RecordingProvider();
        ArchaiusUtil.setProviderForTesting(provider);
        ConfigurationBootstrap bootstrap = ArchaiusConfigurationBootstrap.create();

        ConfigurationStack config = bootstrap.newStack();
        bootstrap.initialize(config);

        assertSame(config.asConfiguration(), provider.initializedConfiguration);
    }

    @Test
    public void configurationStackPreservesFirstConfigPriority() {
        AbstractConfiguration config = ArchaiusUtil.newConfigurationStack();
        ArchaiusUtil.addConfiguration(config, mapConfig("shared", "first"));
        ArchaiusUtil.addConfiguration(config, mapConfig("shared", "second"));

        assertEquals("first", config.getString("shared"));
    }

    @Test
    public void configurationStackAdapterPreservesFirstConfigPriority() {
        ConfigurationStack config = ArchaiusUtil.newConfigurationStackAdapter();
        config.add(mapConfig("shared", "first"));
        config.add(mapConfig("shared", "second"));

        assertEquals("first", config.getString("shared"));
    }

    @Test
    public void configurationStackAdapterAcceptsRegisteredConfigSources() {
        ConfigurationStack config = ArchaiusUtil.newConfigurationStackAdapter();
        config.add(ArchaiusConfigFactory.source(mapConfig("shared", "first")));
        config.add(ArchaiusConfigFactory.source(mapConfig("shared", "second")));

        assertEquals("first", config.getString("shared"));
    }


    @Test
    public void configPropertyCallbacksDelegateToArchaiusProperties() {
        final String key = "rc16.test.callback.string";
        clearProperty(key);

        try {
            ConfigProperty<String> property = new ArchaiusDynamicPropertyProvider().getStringProperty(key, "before");
            final AtomicInteger callbacks = new AtomicInteger();
            property.addCallback(new Runnable() {
                @Override
                public void run() {
                    callbacks.incrementAndGet();
                }
            });

            ConfigurationManager.getConfigInstance().setProperty(key, "after");

            assertEquals("after", property.get());
            assertTrue(callbacks.get() > 0);
        } finally {
            clearProperty(key);
        }
    }


    @Test
    public void configListPropertyCallbacksDelegateToArchaiusProperties() {
        final String key = "rc16.test.callback.list";
        clearProperty(key);

        try {
            ConfigListProperty<String> property = new ArchaiusDynamicPropertyProvider().getStringListProperty(key, "before");
            final AtomicInteger callbacks = new AtomicInteger();
            property.addCallback(new Runnable() {
                @Override
                public void run() {
                    callbacks.incrementAndGet();
                }
            });

            ConfigurationManager.getConfigInstance().setProperty(key, "after,next");

            assertEquals(Arrays.asList("after", "next"), property.get());
            assertTrue(callbacks.get() > 0);
        } finally {
            clearProperty(key);
        }
    }

    @Test
    public void configMapPropertyCallbacksDelegateToArchaiusProperties() {
        final String key = "rc16.test.callback.map";
        clearProperty(key);

        try {
            ConfigMapProperty<String, String> property = new ArchaiusDynamicPropertyProvider().getStringMapProperty(key, "before=one");
            final AtomicInteger callbacks = new AtomicInteger();
            property.addCallback(new Runnable() {
                @Override
                public void run() {
                    callbacks.incrementAndGet();
                }
            });

            ConfigurationManager.getConfigInstance().setProperty(key, "after=two,next=three");

            assertEquals(mapOf("after", "two", "next", "three"), property.get());
            assertTrue(callbacks.get() > 0);
        } finally {
            clearProperty(key);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullProviderForTesting() {
        ArchaiusUtil.setProviderForTesting(null);
    }


    private static void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static Map<String, String> mapOf(String... keyValues) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(keyValues[i], keyValues[i + 1]);
        }

        return result;
    }

    private static AbstractConfiguration mapConfig(String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);
        return new MapConfiguration(props);
    }

    private static class RecordingProvider implements DynamicPropertyProvider, LegacyDynamicPropertyProvider {

        String longKey;
        long longDefault;
        String intKey;
        int intDefault;
        String booleanKey;
        boolean booleanDefault;
        String doubleKey;
        double doubleDefault;
        String floatKey;
        float floatDefault;
        String stringKey;
        String stringDefault;
        String listKey;
        String listDefault;
        int configurationCalls;
        String longPropertyKey;
        long longPropertyDefault;
        String intPropertyKey;
        int intPropertyDefault;
        String booleanPropertyKey;
        boolean booleanPropertyDefault;
        String doublePropertyKey;
        double doublePropertyDefault;
        String floatPropertyKey;
        float floatPropertyDefault;
        String stringPropertyKey;
        String stringPropertyDefault;
        String stringListPropertyKey;
        String stringListPropertyDefault;
        String stringMapPropertyKey;
        String stringMapPropertyDefault;
        AbstractConfiguration initializedConfiguration;

        @Override
        public void initialize(AbstractConfiguration configuration) {
            initializedConfiguration = configuration;
        }


        @Override
        public DynamicLongProperty getLong(String key, long defaultValue) {
            longKey = key;
            longDefault = defaultValue;
            return null;
        }

        @Override
        public DynamicIntProperty getInt(String key, int defaultValue) {
            intKey = key;
            intDefault = defaultValue;
            return null;
        }

        @Override
        public DynamicBooleanProperty getBoolean(String key, boolean defaultValue) {
            booleanKey = key;
            booleanDefault = defaultValue;
            return null;
        }

        @Override
        public DynamicDoubleProperty getDouble(String key, double defaultValue) {
            doubleKey = key;
            doubleDefault = defaultValue;
            return null;
        }

        @Override
        public DynamicFloatProperty getFloat(String key, float defaultValue) {
            floatKey = key;
            floatDefault = defaultValue;
            return null;
        }

        @Override
        public DynamicStringProperty getString(String key, String defaultValue) {
            stringKey = key;
            stringDefault = defaultValue;
            return null;
        }

        @Override
        public DynamicStringListProperty getList(String key, String defaultValue) {
            listKey = key;
            listDefault = defaultValue;
            return null;
        }

        @Override
        public ConfigListProperty<String> getStringListProperty(String key, String defaultValue) {
            stringListPropertyKey = key;
            stringListPropertyDefault = defaultValue;
            return new FixedConfigListProperty<String>(Arrays.asList("one", "two"));
        }

        @Override
        public ConfigMapProperty<String, String> getStringMapProperty(String key, String defaultValue) {
            stringMapPropertyKey = key;
            stringMapPropertyDefault = defaultValue;
            return new FixedConfigMapProperty<String, String>(mapOf("one", "two"));
        }

        @Override
        public ConfigProperty<Long> getLongProperty(String key, long defaultValue) {
            longPropertyKey = key;
            longPropertyDefault = defaultValue;
            return new FixedConfigProperty<Long>(11L);
        }

        @Override
        public ConfigProperty<Integer> getIntProperty(String key, int defaultValue) {
            intPropertyKey = key;
            intPropertyDefault = defaultValue;
            return new FixedConfigProperty<Integer>(12);
        }

        @Override
        public ConfigProperty<Boolean> getBooleanProperty(String key, boolean defaultValue) {
            booleanPropertyKey = key;
            booleanPropertyDefault = defaultValue;
            return new FixedConfigProperty<Boolean>(Boolean.TRUE);
        }

        @Override
        public ConfigProperty<Double> getDoubleProperty(String key, double defaultValue) {
            doublePropertyKey = key;
            doublePropertyDefault = defaultValue;
            return new FixedConfigProperty<Double>(13.0);
        }

        @Override
        public ConfigProperty<Float> getFloatProperty(String key, float defaultValue) {
            floatPropertyKey = key;
            floatPropertyDefault = defaultValue;
            return new FixedConfigProperty<Float>(14.0f);
        }

        @Override
        public ConfigProperty<String> getStringProperty(String key, String defaultValue) {
            stringPropertyKey = key;
            stringPropertyDefault = defaultValue;

            return new FixedConfigProperty<String>("wrapped");
        }
        @Override
        public Configuration getConfiguration() {
            configurationCalls++;
            return null;
        }

    }


    private static class FixedConfigListProperty<T> implements ConfigListProperty<T> {

        private final List<T> value;

        FixedConfigListProperty(List<T> value) {
            this.value = value;
        }

        @Override
        public List<T> get() {
            return value;
        }

    }

    private static class FixedConfigMapProperty<K, V> implements ConfigMapProperty<K, V> {

        private final Map<K, V> value;

        FixedConfigMapProperty(Map<K, V> value) {
            this.value = value;
        }

        @Override
        public Map<K, V> get() {
            return value;
        }

    }

    private static class FixedConfigProperty<T> implements ConfigProperty<T> {

        private final T value;

        FixedConfigProperty(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

    }

}
