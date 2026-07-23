package io.cattle.platform.servicediscovery.api.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.util.PortSpec;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilPortMapTest {

    @Test
    public void portSpecsPreservesStringValues() {
        assertEquals(Arrays.asList("80", "81:80/tcp"),
                ServiceDiscoveryUtil.portSpecs(Arrays.asList("80", "81:80/tcp")));
    }

    @Test(expected = ClassCastException.class)
    public void portSpecsRejectsNonListValues() {
        ServiceDiscoveryUtil.portSpecs("80");
    }

    @Test(expected = ClassCastException.class)
    public void portSpecsRejectsNonStringElements() {
        ServiceDiscoveryUtil.portSpecs(Arrays.asList("80", Integer.valueOf(81)));
    }

    @Test(expected = NullPointerException.class)
    public void portSpecsPreservesNullListFailureMode() {
        ServiceDiscoveryUtil.portSpecs(null);
    }

    @Test
    public void getServicePortsMapReturnsEmptyMapWhenPortsMissing() {
        assertTrue(ServiceDiscoveryUtil.getServicePortsMap(null, new HashMap<>()).isEmpty());
    }

    @Test
    public void getServicePortsMapIndexesByPrivatePort() {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_PORTS, Arrays.asList("81:80/tcp", "82:81/udp"));

        Map<Integer, PortSpec> ports = ServiceDiscoveryUtil.getServicePortsMap(null, launchConfig);

        assertEquals("81:80/tcp", ports.get(Integer.valueOf(80)).toSpec());
        assertEquals("82:81/udp", ports.get(Integer.valueOf(81)).toSpec());
    }
}
