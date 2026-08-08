package io.cattle.platform.launcher.jetty;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class MainTest {

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
