package io.cattle.platform.jmx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class JmxAttributeQuery {

    private final String name;
    private final List<String> keys;
    private final String resultAlias;

    JmxAttributeQuery(String name, List<String> keys) {
        this(name, keys, null);
    }

    JmxAttributeQuery(String name, List<String> keys, String resultAlias) {
        if (EmbeddedJmxTransPublisher.isBlank(name)) {
            throw new IllegalArgumentException("attribute name is required");
        }
        this.name = name;
        this.keys = keys == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(keys));
        this.resultAlias = resultAlias;
    }

    String getName() {
        return name;
    }

    List<String> getKeys() {
        return keys;
    }

    String getResultAlias() {
        return resultAlias;
    }
}
