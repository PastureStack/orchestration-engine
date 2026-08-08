package io.cattle.platform.servicediscovery.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class LoadBalancerServiceUpdatePostListenerTest {

    @Test
    public void portDefinitionsReturnsEmptyListWhenMissing() {
        assertTrue(LoadBalancerServiceUpdatePostListener.portDefinitions(null).isEmpty());
    }

    @Test
    public void portDefinitionsReadsStringPortsInOrder() {
        List<String> ports = new ArrayList<String>();
        ports.add("80:80/tcp");
        ports.add("443:443/tcp");

        List<String> result = LoadBalancerServiceUpdatePostListener.portDefinitions(ports);

        assertEquals("80:80/tcp", result.get(0));
        assertEquals("443:443/tcp", result.get(1));
    }

    @Test
    public void portDefinitionsKeepsLazyLiveListBehavior() {
        List<String> ports = new ArrayList<String>();
        List<String> result = LoadBalancerServiceUpdatePostListener.portDefinitions(ports);

        ports.add("8080:80/tcp");

        assertEquals(1, result.size());
        assertEquals("8080:80/tcp", result.get(0));
    }

    @Test(expected = ClassCastException.class)
    public void portDefinitionsRejectsNonListValues() {
        LoadBalancerServiceUpdatePostListener.portDefinitions("80:80/tcp");
    }

    @Test(expected = ClassCastException.class)
    public void portDefinitionsRejectsNonStringElementsLazily() {
        List<Object> ports = new ArrayList<Object>();
        ports.add(Integer.valueOf(80));

        LoadBalancerServiceUpdatePostListener.portDefinitions(ports).get(0);
    }
}
