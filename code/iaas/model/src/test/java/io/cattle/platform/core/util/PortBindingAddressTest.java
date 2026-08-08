package io.cattle.platform.core.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PortBindingAddressTest {

    @Test
    public void wildcardAddressesOverlapSpecificAddresses() {
        assertTrue(PortBindingAddress.overlaps(null, "10.0.0.5"));
        assertTrue(PortBindingAddress.overlaps("0.0.0.0", "10.0.0.5"));
        assertTrue(PortBindingAddress.overlaps("::", "10.0.0.5"));
        assertTrue(PortBindingAddress.overlaps("[::]", "2001:db8::5"));
    }

    @Test
    public void distinctSpecificAddressesDoNotOverlap() {
        assertFalse(PortBindingAddress.overlaps("10.0.0.5", "10.0.0.6"));
        assertFalse(PortBindingAddress.overlaps("2001:db8::5", "2001:db8::6"));
    }

    @Test
    public void equalAddressesAreCaseAndBracketInsensitive() {
        assertTrue(PortBindingAddress.overlaps("[2001:DB8::5]", "2001:db8::5"));
    }
}
