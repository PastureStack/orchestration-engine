package io.cattle.platform.launcher.jetty;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class MainTest {

    @Test
    public void commandTokensMatchLegacyWhitespaceSplitting() {
        assertEquals(Arrays.asList("touch", "/tmp/rancher-notify"), Main.commandTokens("touch /tmp/rancher-notify"));
        assertEquals(Arrays.asList("cmd", "arg", "two"), Main.commandTokens("  cmd\targ\n two  "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void commandTokensRejectEmptyCommand() {
        Main.commandTokens("   ");
    }
}
