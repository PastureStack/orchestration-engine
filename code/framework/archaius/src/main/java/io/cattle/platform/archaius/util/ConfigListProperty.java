package io.cattle.platform.archaius.util;

import java.util.List;

public interface ConfigListProperty<T> {

    List<T> get();

    default void addCallback(Runnable callback) {
        throw new UnsupportedOperationException("Callbacks are not supported by this ConfigListProperty");
    }

}
