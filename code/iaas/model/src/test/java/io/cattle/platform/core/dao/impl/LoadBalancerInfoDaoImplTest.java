package io.cattle.platform.core.dao.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.dao.impl.LoadBalancerInfoDaoImpl.LoadBalancerListenerInfo;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.object.util.DataAccessor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class LoadBalancerInfoDaoImplTest {

    @Test
    public void parsesLaunchConfigPortsThroughCheckedViews() {
        TestLoadBalancerInfoDao dao = new TestLoadBalancerInfoDao();
        List<LoadBalancerListenerInfo> listeners = dao.listeners(serviceWithLaunchConfig(launchConfig(
                Arrays.asList("81:80/tcp", "444:443/tcp"),
                Arrays.asList("8080"),
                "444",
                "81")));

        assertEquals(3, listeners.size());

        LoadBalancerListenerInfo port81 = listenerBySourcePort(listeners, 81);
        assertEquals(Integer.valueOf(80), port81.getTargetPort());
        assertEquals("tcp", port81.getSourceProtocol());
        assertTrue(port81.isProxyPort());

        LoadBalancerListenerInfo port444 = listenerBySourcePort(listeners, 444);
        assertEquals(Integer.valueOf(443), port444.getTargetPort());
        assertEquals("tls", port444.getSourceProtocol());

        LoadBalancerListenerInfo port8080 = listenerBySourcePort(listeners, 8080);
        assertEquals(Integer.valueOf(8080), port8080.getTargetPort());
        assertEquals("http", port8080.getSourceProtocol());
    }

    @Test
    public void rejectsNonStringPortEntriesAtCheckedBoundary() {
        TestLoadBalancerInfoDao dao = new TestLoadBalancerInfoDao();
        Service service = serviceWithLaunchConfig(launchConfig(Arrays.asList(Integer.valueOf(80)), null, null, null));

        try {
            dao.listeners(service);
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.lang.String"));
            return;
        }

        throw new AssertionError("Expected non-string port entry to fail at checked boundary");
    }

    @Test
    public void missingLaunchConfigUsesEmptyDefaults() {
        TestLoadBalancerInfoDao dao = new TestLoadBalancerInfoDao();
        ServiceRecord service = new ServiceRecord();

        assertTrue(dao.listeners(service).isEmpty());
    }

    @Test
    public void preservesLabelValueToStringBehavior() {
        TestLoadBalancerInfoDao dao = new TestLoadBalancerInfoDao();
        Map<String, Object> launchConfig = launchConfig(null, null, null, null);
        Map<String, Object> labels = new HashMap<String, Object>();
        labels.put(ServiceConstants.LABEL_LB_SSL_PORTS, new StringBuilder("443, 8443"));
        launchConfig.put(InstanceConstants.FIELD_LABELS, labels);

        assertEquals(Arrays.asList("443", "8443"), dao.labeledPorts(launchConfig, ServiceConstants.LABEL_LB_SSL_PORTS));
    }

    private static LoadBalancerListenerInfo listenerBySourcePort(List<LoadBalancerListenerInfo> listeners, int sourcePort) {
        for (LoadBalancerListenerInfo listener : listeners) {
            if (Integer.valueOf(sourcePort).equals(listener.getSourcePort())) {
                return listener;
            }
        }
        throw new AssertionError("Missing listener for source port " + sourcePort);
    }

    private static Service serviceWithLaunchConfig(Map<String, Object> launchConfig) {
        ServiceRecord service = new ServiceRecord();
        DataAccessor.setField(service, ServiceConstants.FIELD_LAUNCH_CONFIG, launchConfig);
        return service;
    }

    private static Map<String, Object> launchConfig(List<?> ports, List<?> expose, Object sslPorts, Object proxyPorts) {
        Map<String, Object> launchConfig = new HashMap<String, Object>();
        if (ports != null) {
            launchConfig.put(InstanceConstants.FIELD_PORTS, ports);
        }
        if (expose != null) {
            launchConfig.put(InstanceConstants.FIELD_EXPOSE, expose);
        }
        Map<String, Object> labels = new HashMap<String, Object>();
        if (sslPorts != null) {
            labels.put(ServiceConstants.LABEL_LB_SSL_PORTS, sslPorts);
        }
        if (proxyPorts != null) {
            labels.put(ServiceConstants.LABEL_LB_PROXY_PORTS, proxyPorts);
        }
        if (!labels.isEmpty()) {
            launchConfig.put(InstanceConstants.FIELD_LABELS, labels);
        }
        return launchConfig;
    }

    private static class TestLoadBalancerInfoDao extends LoadBalancerInfoDaoImpl {
        List<LoadBalancerListenerInfo> listeners(Service service) {
            return getListeners(service);
        }

        List<String> labeledPorts(Map<String, Object> launchConfig, String labelName) {
            return getLabeledPorts(launchConfig, labelName);
        }
    }
}
