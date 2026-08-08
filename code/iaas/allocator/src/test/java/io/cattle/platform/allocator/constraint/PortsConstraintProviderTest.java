package io.cattle.platform.allocator.constraint;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PortsConstraintProviderTest {

    @Test
    public void hostPortConstraintRemainsEnabledForRequestedHostAllocations() {
        assertTrue(new PortsConstraintProvider().isCritical());
    }
}
