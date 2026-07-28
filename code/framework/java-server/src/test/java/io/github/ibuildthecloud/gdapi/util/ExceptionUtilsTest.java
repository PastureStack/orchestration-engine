package io.github.ibuildthecloud.gdapi.util;

import static org.junit.Assert.assertSame;

import java.io.IOException;

import org.junit.Test;

public class ExceptionUtilsTest {

    @Test
    public void rethrowsMatchingCheckedExceptionInstance() throws Exception {
        IOException expected = new IOException("expected");

        try {
            ExceptionUtils.rethrow(expected, IOException.class);
        } catch (IOException actual) {
            assertSame(expected, actual);
            return;
        }

        throw new AssertionError("Expected IOException to be rethrown");
    }

    @Test
    public void ignoresNonMatchingExceptionType() throws Exception {
        ExceptionUtils.rethrow(new IllegalArgumentException("ignored"), IOException.class);
    }

    @Test
    public void rethrowsMatchingRuntimeExceptionInstance() {
        RuntimeException expected = new RuntimeException("expected");

        try {
            ExceptionUtils.rethrowRuntime(expected);
        } catch (RuntimeException actual) {
            assertSame(expected, actual);
            return;
        }

        throw new AssertionError("Expected RuntimeException to be rethrown");
    }
}
