package io.cattle.platform.archaius.startup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.archaius.sources.ArchaiusConfigFactory;
import io.cattle.platform.archaius.sources.ConfigurationSourceList;
import io.cattle.platform.archaius.sources.RegisteredConfigSource;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigurationStack;
import io.cattle.platform.extension.impl.ExtensionManagerImpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.MapConfiguration;
import org.junit.Test;

public class ArchaiusConfigRegistrationTest {

    @Test
    public void registersConfigUnderArchaiusConfigKey() {
        RecordingExtensionManager extensionManager = new RecordingExtensionManager();
        MapConfiguration config = new MapConfiguration(new Properties());

        assertSame(config, ArchaiusConfigRegistration.addConfig(extensionManager, config, "TestConfig"));
        assertEquals(ArchaiusStartup.CONFIG_KEY, extensionManager.key);
        assertEquals(AbstractConfiguration.class, extensionManager.type);
        assertSame(config, extensionManager.object);
        assertEquals("TestConfig", extensionManager.name);
    }

    @Test
    public void registersConfigSourceUnderArchaiusConfigKey() {
        RecordingExtensionManager extensionManager = new RecordingExtensionManager();
        MapConfiguration config = new MapConfiguration(new Properties());
        RegisteredConfigSource source = ArchaiusConfigFactory.source(config);

        assertSame(source, ArchaiusConfigRegistration.addConfig(extensionManager, source, "TestSource"));
        assertEquals(ArchaiusStartup.CONFIG_KEY, extensionManager.key);
        assertEquals(AbstractConfiguration.class, extensionManager.type);
        assertSame(config, extensionManager.object);
        assertEquals("TestSource", extensionManager.name);
    }

    @Test
    public void returnsConfigSourcesFromExtensionManager() {
        MapConfiguration first = mapConfig("shared", "first");
        MapConfiguration second = mapConfig("shared", "second");
        FixedExtensionManager extensionManager = new FixedExtensionManager(first, second);
        ConfigurationSourceList sources = ArchaiusConfigRegistration.getConfigSources(extensionManager,
                ArchaiusStartup.CONFIG_KEY);
        ConfigurationStack stack = ArchaiusUtil.newConfigurationStackAdapter();

        sources.replace(stack);

        assertEquals("first", stack.getString("shared"));
    }

    private static MapConfiguration mapConfig(String key, String value) {
        Properties props = new Properties();
        props.setProperty(key, value);
        return new MapConfiguration(props);
    }

    private static class RecordingExtensionManager extends ExtensionManagerImpl {

        String key;
        Class<?> type;
        Object object;
        String name;

        @Override
        public synchronized void addObject(String key, Class<?> type, Object object, String name) {
            this.key = key;
            this.type = type;
            this.object = object;
            this.name = name;
        }

    }

    private static class FixedExtensionManager extends ExtensionManagerImpl {

        private final List<AbstractConfiguration> configs;

        FixedExtensionManager(AbstractConfiguration... configs) {
            this.configs = Arrays.asList(configs);
        }

        @Override
        public <T> List<T> getExtensionList(String key, Class<T> type) {
            assertEquals(ArchaiusStartup.CONFIG_KEY, key);
            assertEquals(AbstractConfiguration.class, type);
            List<T> result = new ArrayList<T>(configs.size());
            for (AbstractConfiguration config : configs) {
                result.add(type.cast(config));
            }
            return result;
        }

    }

}
