package io.github.ibuildthecloud.gdapi.util;

public class ExceptionUtils {

    public static <T extends Throwable> void rethrow(Throwable t, Class<T> clz) throws T {
        if (clz.isAssignableFrom(t.getClass()))
            throw clz.cast(t);
    }

    public static <T extends Throwable> void rethrowRuntime(Throwable t) {
        rethrow(t, RuntimeException.class);
        rethrow(t, Error.class);
    }

}
