package io.cattle.platform.util.net;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class HttpResponseUtilsTest {

    @Test
    public void readsUtf8ResponseWithinLimit() throws Exception {
        String response = HttpResponseUtils.readUtf8String(input("ok"), 2);

        assertEquals("ok", response);
    }

    @Test(expected = IOException.class)
    public void rejectsResponseExceedingLimit() throws Exception {
        HttpResponseUtils.readUtf8String(input("too-large"), 3);
    }

    @Test
    public void fallsBackToDefaultForInvalidProperty() {
        String property = "rc16.test.maxResponseBytes";
        System.setProperty(property, "invalid");
        try {
            assertEquals(7, HttpResponseUtils.maxResponseBytes(property, 7));
        } finally {
            System.clearProperty(property);
        }
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
