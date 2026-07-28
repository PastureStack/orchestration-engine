package org.apache.commons.configuration;

import com.netflix.config.DynamicPropertyFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CompositeConfiguration extends AbstractConfiguration {

    private final List<Configuration> configurations = new ArrayList<Configuration>();

    public synchronized void addConfiguration(Configuration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration is required");
        }
        configurations.add(configuration);
    }

    public Configuration getSource(String key) {
        List<Configuration> sources;
        synchronized (this) {
            if (containsRawKey(key)) {
                return this;
            }
            sources = new ArrayList<Configuration>(configurations);
        }
        for (Configuration configuration : sources) {
            if (configuration.containsKey(key)) {
                return configuration;
            }
        }
        return null;
    }

    @Override
    public Iterator<String> getKeys() {
        Set<String> keys = new LinkedHashSet<String>();
        Iterator<String> raw = super.getKeys();
        while (raw.hasNext()) {
            keys.add(raw.next());
        }
        for (Configuration configuration : snapshotConfigurations()) {
            Iterator<String> sourceKeys = configuration.getKeys();
            while (sourceKeys.hasNext()) {
                keys.add(sourceKeys.next());
            }
        }
        return keys.iterator();
    }

    @Override
    public Object getProperty(String key) {
        Object rawValue = null;
        boolean hasRawValue = false;
        List<Configuration> sources = null;
        synchronized (this) {
            if (containsRawKey(key)) {
                rawValue = getRawProperty(key);
                hasRawValue = true;
            } else {
                sources = new ArrayList<Configuration>(configurations);
            }
        }
        if (hasRawValue) {
            return rawValue instanceof String ? interpolate((String) rawValue, this) : rawValue;
        }
        for (Configuration configuration : sources) {
            if (configuration.containsKey(key)) {
                Object value = configuration.getProperty(key);
                return value instanceof String ? interpolate((String) value, this) : value;
            }
        }
        return null;
    }

    @Override
    public boolean containsKey(String key) {
        List<Configuration> sources;
        synchronized (this) {
            if (containsRawKey(key)) {
                return true;
            }
            sources = new ArrayList<Configuration>(configurations);
        }
        for (Configuration configuration : sources) {
            if (configuration.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void clearProperty(String key) {
        super.clearProperty(key);
        for (Configuration configuration : snapshotConfigurations()) {
            if (configuration.containsKey(key)) {
                configuration.clearProperty(key);
            }
        }
        DynamicPropertyFactory.firePropertyChanged(key);
    }

    @Override
    public void clear() {
        Set<String> keys = new LinkedHashSet<String>();
        Iterator<String> currentKeys = getKeys();
        while (currentKeys.hasNext()) {
            keys.add(currentKeys.next());
        }
        synchronized (this) {
            configurations.clear();
        }
        super.clear();
        for (String key : keys) {
            DynamicPropertyFactory.firePropertyChanged(key);
        }
    }

    private synchronized List<Configuration> snapshotConfigurations() {
        return new ArrayList<Configuration>(configurations);
    }

}
