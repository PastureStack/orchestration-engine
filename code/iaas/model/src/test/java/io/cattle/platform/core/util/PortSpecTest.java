package io.cattle.platform.core.util;

import static org.junit.Assert.*;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import org.junit.Test;

public class PortSpecTest {

    @Test
    public void testValidFormats() {
        // format: ip:hostPort:containerPort | ip::containerPort | hostPort:containerPort | containerPort
        PortSpec spec = new PortSpec("80");

        assertEquals(80, spec.getPrivatePort());
        assertEquals("tcp", spec.getProtocol());
        assertNull(spec.getPublicPort());
        assertNull(spec.getIpAddress());

        spec = new PortSpec("81:80");
        assertEquals(80, spec.getPrivatePort());
        assertEquals("tcp", spec.getProtocol());
        assertEquals(Integer.valueOf(81), spec.getPublicPort());
        assertNull(spec.getIpAddress());

        spec = new PortSpec("81:80/tcp");
        assertEquals(80, spec.getPrivatePort());
        assertEquals("tcp", spec.getProtocol());
        assertEquals(Integer.valueOf(81), spec.getPublicPort());
        assertNull(spec.getIpAddress());

        spec = new PortSpec("81:80/udp");
        assertEquals(80, spec.getPrivatePort());
        assertEquals("udp", spec.getProtocol());
        assertEquals(Integer.valueOf(81), spec.getPublicPort());
        assertNull(spec.getIpAddress());

        spec = new PortSpec("80/udp");
        assertEquals(80, spec.getPrivatePort());
        assertEquals("udp", spec.getProtocol());
        assertNull(spec.getPublicPort());
        assertNull(spec.getIpAddress());

        spec = new PortSpec("127.0.0.1:81:80");
        assertEquals("127.0.0.1", spec.getIpAddress());
        assertEquals(Integer.valueOf(81), spec.getPublicPort());
        assertEquals("tcp", spec.getProtocol());
        assertEquals(80, spec.getPrivatePort());

        spec = new PortSpec("[::1]:81:80");
        assertEquals("::1", spec.getIpAddress());
        assertEquals(Integer.valueOf(81), spec.getPublicPort());
        assertEquals("tcp", spec.getProtocol());
        assertEquals(80, spec.getPrivatePort());

        spec = new PortSpec("127.0.0.1:81:80/udp");
        assertEquals("127.0.0.1", spec.getIpAddress());
        assertEquals(Integer.valueOf(81), spec.getPublicPort());
        assertEquals("udp", spec.getProtocol());
        assertEquals(80, spec.getPrivatePort());

        spec = new PortSpec("127.0.0.1::80");
        assertEquals("127.0.0.1", spec.getIpAddress());
        assertNull(spec.getPublicPort());
        assertEquals("tcp", spec.getProtocol());
        assertEquals(80, spec.getPrivatePort());
        assertNull(spec.getPublicPort());
    }

    @Test
    public void testBadFormats() {
        try {
            new PortSpec("a");
            fail();
        } catch (ClientVisibleException e) {
            assertEquals(PortSpec.WRONG_FORMAT, e.getCode());
        }

        try {
            new PortSpec("0");
            fail();
        } catch (ClientVisibleException e) {
            assertEquals(PortSpec.INVALID_PRIVATE_PORT, e.getCode());
        }

        try {
            new PortSpec("65536");
            fail();
        } catch (ClientVisibleException e) {
            assertEquals(PortSpec.INVALID_PRIVATE_PORT, e.getCode());
        }

        try {
            new PortSpec("0:65535");
            fail();
        } catch (ClientVisibleException e) {
            assertEquals(PortSpec.INVALID_PUBLIC_PORT, e.getCode());
        }

        try {
            new PortSpec("65536:65535");
            fail();
        } catch (ClientVisibleException e) {
            assertEquals(PortSpec.INVALID_PUBLIC_PORT, e.getCode());
        }

        try {
            new PortSpec("80/asdf");
            fail();
        } catch (ClientVisibleException e) {
            assertEquals(PortSpec.INVALID_PROTOCOL, e.getCode());
        }
    }

    @Test
    public void nativeFormattingKeepsExistingIpv4NoBindAndAllocatedPortOutputs() {
        assertEquals("80/tcp", new PortSpec("80").toSpec());
        assertEquals("81:80/tcp", new PortSpec("81:80").toSpec());
        assertEquals("127.0.0.1:81:80/udp", new PortSpec("127.0.0.1:81:80/udp").toSpec());
        PortSpec allocated = new PortSpec("127.0.0.1::80"); allocated.setPublicPort(31001);
        assertEquals("127.0.0.1:31001:80/tcp", allocated.toSpec());
        PortSpec unbound = new PortSpec("80/udp"); unbound.setPublicPort(31002);
        assertEquals("31002:80/udp", unbound.toSpec());
    }

    @Test
    public void ipv6FixedAndNativeAllocatedMappingsRoundTripThroughTheSameParser() {
        for (String input : new String[] { "[::1]:81:80", "[2001:db8::1]:53/udp", "[::]:65535:65535/tcp" }) {
            PortSpec expected = new PortSpec(input); PortSpec actual = new PortSpec(expected.toSpec());
            assertEquals(expected.getIpAddress(), actual.getIpAddress()); assertEquals(expected.getPublicPort(), actual.getPublicPort());
            assertEquals(expected.getPrivatePort(), actual.getPrivatePort()); assertEquals(expected.getProtocol(), actual.getProtocol());
        }
        PortSpec allocated = new PortSpec("[2001:db8::1]:53/udp"); allocated.setPublicPort(31001);
        assertEquals("[2001:db8::1]:31001:53/udp", allocated.toSpec());
        PortSpec read = new PortSpec(allocated.toSpec());
        assertEquals("2001:db8::1", read.getIpAddress()); assertEquals(Integer.valueOf(31001), read.getPublicPort());
        assertEquals(53, read.getPrivatePort()); assertEquals("udp", read.getProtocol());
    }
}
