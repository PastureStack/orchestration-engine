package io.cattle.platform.configitem.freemarker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.Test;

public class ConfigurationTest {

    @Test
    public void usesLegacyCompatibleFreemarkerVersionExplicitly() {
        Configuration config = new Configuration();

        assertEquals(freemarker.template.Configuration.VERSION_2_3_0, config.getIncompatibleImprovements());
    }

    @Test
    public void narrowsSettingsReturnTypeForSpringBeanCreation() {
        Configuration config = new Configuration();

        Properties settings = config.getSettings();

        assertEquals(Properties.class, settings.getClass());
    }

    @Test
    public void settingsApiBoundaryKeepsNarrowedPublicReturnType() throws Exception {
        Method getSettings = Configuration.class.getDeclaredMethod("getSettings");

        assertTrue(Modifier.isPublic(getSettings.getModifiers()));
        assertEquals(Properties.class, getSettings.getReturnType());
        assertFalse(getSettings.isAnnotationPresent(Deprecated.class));
    }

    @Test
    public void returnsExplicitSettingsWithoutDeprecatedFreemarkerMap() throws Exception {
        Configuration config = new Configuration();
        config.setSetting("default_encoding", "UTF-8");

        Properties settings = config.getSettings();

        assertEquals("UTF-8", settings.getProperty("default_encoding"));
    }

    @Test
    public void returnsSettingsLoadedFromProperties() throws Exception {
        Configuration config = new Configuration();
        Properties input = new Properties();
        input.setProperty("default_encoding", "UTF-8");

        config.setSettings(input);

        assertEquals("UTF-8", config.getSettings().getProperty("default_encoding"));
    }

    @Test
    public void returnsSettingsLoadedFromInputStream() throws Exception {
        Configuration config = new Configuration();
        ByteArrayInputStream input = new ByteArrayInputStream("default_encoding=UTF-8\n".getBytes(StandardCharsets.UTF_8));

        config.setSettings(input);

        assertEquals("UTF-8", config.getSettings().getProperty("default_encoding"));
    }
}
