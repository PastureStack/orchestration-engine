package io.cattle.platform.launcher.jetty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.Test;

public class MainTest {

    @Test
    public void httpBindAddressIsOptionalAndDoesNotChangeThePort() {
        String previous = System.getProperty("cattle.http.bind.address");
        try {
            System.clearProperty("cattle.http.bind.address");
            ServerConnector connector = new ServerConnector(new Server());
            connector.setPort(0);
            Main.configureHttpBind(connector);
            assertNull(connector.getHost());
            assertEquals(0, connector.getPort());
        } finally {
            restoreBindAddress(previous);
        }
    }

    @Test
    public void httpBindAddressCanRestrictAnEphemeralListenerToLoopback() {
        String previous = System.getProperty("cattle.http.bind.address");
        try {
            System.setProperty("cattle.http.bind.address", "127.0.0.1");
            ServerConnector connector = new ServerConnector(new Server());
            connector.setPort(0);
            Main.configureHttpBind(connector);
            assertEquals("127.0.0.1", connector.getHost());
            assertEquals(0, connector.getPort());
        } finally {
            restoreBindAddress(previous);
        }
    }

    private static void restoreBindAddress(String previous) {
        if (previous == null) {
            System.clearProperty("cattle.http.bind.address");
        } else {
            System.setProperty("cattle.http.bind.address", previous);
        }
    }

    @Test
    public void notificationCommandsAreFixedDevelopmentHelpers() {
        assertEquals(Arrays.asList("../../../tools/development/run-success.sh"),
                Main.notificationCommand(Main.Notification.SUCCESS, "../../../tools/development/run-success.sh"));
        assertEquals(Arrays.asList("../../../tools/development/run-error.sh"),
                Main.notificationCommand(Main.Notification.ERROR, "../../../tools/development/run-error.sh"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void notificationCommandRejectsArbitraryExecutableAndArguments() {
        Main.notificationCommand(Main.Notification.SUCCESS, "/usr/bin/touch /tmp/owned");
    }

    @Test(expected = IllegalArgumentException.class)
    public void notificationCommandRejectsWrongHelperForLifecycleEvent() {
        Main.notificationCommand(Main.Notification.SUCCESS, "../../../tools/development/run-error.sh");
    }

    @Test(expected = IllegalArgumentException.class)
    public void notificationArgumentRejectsMissingValue() {
        Main.notificationArgument(new String[] { "--notify" }, 0);
    }
}
