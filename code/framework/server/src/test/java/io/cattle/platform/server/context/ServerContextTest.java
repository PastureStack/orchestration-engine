package io.cattle.platform.server.context;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ServerContextTest {

    @Test
    public void expandsOneSafePlaceholder() {
        assertEquals("node-10.0.0.5-%", ServerContext.applyServerIdTemplate("node-%s-%%", "10.0.0.5"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAdditionalFormatDirectives() {
        ServerContext.applyServerIdTemplate("%s-%n", "10.0.0.5");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMultiplePlaceholders() {
        ServerContext.applyServerIdTemplate("%s-%s", "10.0.0.5");
    }
}
