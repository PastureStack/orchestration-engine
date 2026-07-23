package io.cattle.platform.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public class MainTest {

    private final String original = System.getProperty(Main.JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS);

    @After
    public void restoreProperty() {
        if (original == null) {
            System.clearProperty(Main.JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS);
        } else {
            System.setProperty(Main.JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS, original);
        }
    }

    @Test
    public void enablesHostHeaderBeforeApplicationHttpClientsStart() {
        System.clearProperty(Main.JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS);

        Main.allowJdkHttpClientRestrictedHeader("host");

        assertEquals("host", System.getProperty(Main.JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS));
    }

    @Test
    public void preservesExistingRestrictedHeaderAllowList() {
        System.setProperty(Main.JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS, "connection, content-length");

        Main.allowJdkHttpClientRestrictedHeader("host");

        assertEquals("connection, content-length,host",
                System.getProperty(Main.JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS));
    }

    @Test
    public void doesNotDuplicateExistingHostAllowListEntry() {
        System.setProperty(Main.JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS, "connection, Host");

        Main.allowJdkHttpClientRestrictedHeader("host");

        assertEquals("connection, Host", System.getProperty(Main.JDK_HTTP_CLIENT_ALLOW_RESTRICTED_HEADERS));
    }
}
