package com.netflix.config;

import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.configuration.MapConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;

public final class ConfigurationManager {

    private static volatile AbstractConfiguration configInstance = newDefaultConfig();

    private ConfigurationManager() {
    }

    public static AbstractConfiguration getConfigInstance() {
        return configInstance;
    }

    static void setConfigInstance(AbstractConfiguration configInstance) {
        if (configInstance == null) {
            throw new IllegalArgumentException("configInstance is required");
        }
        ConfigurationManager.configInstance = configInstance;
    }

    private static AbstractConfiguration newDefaultConfig() {
        ConcurrentCompositeConfiguration config = new ConcurrentCompositeConfiguration();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ConfigurationManager.class.getClassLoader();
        }

        try {
            Enumeration<URL> resources = classLoader.getResources("config.properties");
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                Properties properties = load(resource);
                config.addConfiguration(new MapConfiguration(properties));
            }
        } catch (IOException ignored) {
        }

        return config;
    }

    private static Properties load(URL resource) throws IOException {
        Properties properties = new Properties();
        InputStream input = null;
        try {
            input = resource.openStream();
            properties.load(input);
        } finally {
            if (input != null) {
                input.close();
            }
        }
        return properties;
    }

}
