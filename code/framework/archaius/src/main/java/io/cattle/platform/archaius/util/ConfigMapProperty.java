package io.cattle.platform.archaius.util;

import java.util.Map;

public interface ConfigMapProperty<K, V> {

    Map<K, V> get();

    default void addCallback(Runnable callback) {
        throw new UnsupportedOperationException("Callbacks are not supported by this ConfigMapProperty");
    }

}
