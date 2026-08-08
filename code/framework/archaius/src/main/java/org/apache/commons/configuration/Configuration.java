package org.apache.commons.configuration;

import java.util.Iterator;

public interface Configuration {

    Iterator<String> getKeys();

    Object getProperty(String key);

    String getString(String key);

    boolean containsKey(String key);

    void setProperty(String key, Object value);

    void clearProperty(String key);

    void clear();

}
