package org.apache.commons.configuration;

import com.netflix.config.DynamicPropertyFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public abstract class AbstractConfiguration implements Configuration {

    private final Map<String, Object> values = new LinkedHashMap<String, Object>();
    private boolean delimiterParsingDisabled;

    @Override
    public synchronized Iterator<String> getKeys() {
        return new LinkedHashSet<String>(values.keySet()).iterator();
    }

    @Override
    public synchronized Object getProperty(String key) {
        Object value = values.get(key);
        if (value instanceof String) {
            return interpolate((String) value, this);
        }

        return value;
    }

    @Override
    public String getString(String key) {
        Object value = getProperty(key);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public synchronized boolean containsKey(String key) {
        return values.containsKey(key);
    }

    @Override
    public void setProperty(String key, Object value) {
        synchronized (this) {
            values.put(key, value);
        }
        DynamicPropertyFactory.firePropertyChanged(key);
    }

    @Override
    public void clearProperty(String key) {
        boolean removed;
        synchronized (this) {
            removed = values.remove(key) != null;
        }
        if (removed) {
            DynamicPropertyFactory.firePropertyChanged(key);
        }
    }

    @Override
    public void clear() {
        Set<String> keys;
        synchronized (this) {
            keys = new LinkedHashSet<String>(values.keySet());
            values.clear();
        }
        for (String key : keys) {
            DynamicPropertyFactory.firePropertyChanged(key);
        }
    }

    public synchronized void setDelimiterParsingDisabled(boolean delimiterParsingDisabled) {
        this.delimiterParsingDisabled = delimiterParsingDisabled;
    }

    public synchronized boolean isDelimiterParsingDisabled() {
        return delimiterParsingDisabled;
    }

    protected synchronized void putAll(Map<?, ?> source) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            values.put(String.valueOf(entry.getKey()), entry.getValue());
        }
    }

    protected void replaceAll(Map<String, ?> source) {
        Set<String> changed;
        synchronized (this) {
            changed = new LinkedHashSet<String>(values.keySet());
            values.clear();
            for (Map.Entry<String, ?> entry : source.entrySet()) {
                String key = String.valueOf(entry.getKey());
                values.put(key, entry.getValue());
                changed.add(key);
            }
        }

        for (String key : changed) {
            DynamicPropertyFactory.firePropertyChanged(key);
        }
    }

    protected synchronized Object getRawProperty(String key) {
        return values.get(key);
    }

    protected synchronized boolean containsRawKey(String key) {
        return values.containsKey(key);
    }

    protected static String interpolate(String value, Configuration resolver) {
        if (value == null) {
            return null;
        }

        String result = value;
        for (int i = 0; i < 20; i++) {
            int start = result.indexOf("${");
            if (start < 0) {
                return result;
            }
            int end = result.indexOf('}', start + 2);
            if (end < 0) {
                return result;
            }

            String key = result.substring(start + 2, end);
            String replacement = resolver.getString(key);
            if (replacement == null) {
                replacement = "";
            }
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }

        return result;
    }

}
