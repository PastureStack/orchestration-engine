package io.cattle.platform.archaius.util;

public interface ConfigProperty<T> {

    T get();

    default void addCallback(Runnable callback) {
        throw new UnsupportedOperationException("Callbacks are not supported by this ConfigProperty");
    }

}
