package io.cattle.platform.archaius.util;

import java.util.Iterator;

public interface ConfigurationView {

    Iterator<String> getKeys();

    Object getProperty(String name);

    String getSourceName(String name);

}
