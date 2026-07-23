package io.cattle.platform.allocator.service.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

import io.cattle.platform.eventing.model.EventVO;

public class AllocatorServiceImplTest {

    @Test
    public void portSchedulerVersionReadsDynamicConfigThroughWrapper() {
        final String key = "port.scheduler.image.version";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "v0.7.5");

            AllocatorServiceImpl allocator = new AllocatorServiceImpl();
            assertTrue(allocator.useLegacyPortAllocation("v0.6.9"));
            assertFalse(allocator.useLegacyPortAllocation("v0.7.5"));
        } finally {
            if (ConfigurationManager.getConfigInstance().containsKey(key)) {
                ConfigurationManager.getConfigInstance().clearProperty(key);
            }
        }
    }

    @Test
    public void recognizesPastureStackAndPersistedSchedulerImageNames() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();

        assertTrue(allocator.isSchedulerImage("ghcr.io/pasturestack/resource-scheduler"));
        assertTrue(allocator.isSchedulerImage("rancher/scheduler"));
        assertFalse(allocator.isSchedulerImage("example.invalid/custom-scheduler"));
    }

    @Test
    public void extractsResourceRequestsThroughCheckedBoundary() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();
        PortBindingResourceRequest request = new PortBindingResourceRequest();

        List<ResourceRequest> requests = allocator.extractResourceRequests(schedulerEventWithRequests(request));

        assertSame(request, requests.get(0));
    }

    @Test
    public void returnsNullForMissingResourceRequests() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();

        assertNull(allocator.extractResourceRequests(schedulerEventWithRequests()));
    }

    @Test(expected = ClassCastException.class)
    public void rejectsNonResourceRequestEntries() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();
        Map<String, Object> schedulerRequest = new HashMap<String, Object>();
        schedulerRequest.put("resourceRequests", Arrays.<Object>asList("not-a-resource-request"));

        allocator.extractResourceRequests(schedulerEventWithSchedulerRequest(schedulerRequest));
    }

    @Test
    public void convertsPortReservationsThroughCheckedBoundary() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();
        Map<String, Object> reservation = new LinkedHashMap<String, Object>();
        reservation.put("instanceID", "1i1");
        reservation.put("allocatedIPs", Arrays.asList("10.42.0.10"));

        List<Map<String, Object>> result = allocator.portReservationList(Arrays.<Object>asList(reservation));

        assertEquals(1, result.size());
        assertEquals("1i1", result.get(0).get("instanceID"));
        assertEquals(Arrays.asList("10.42.0.10"), result.get(0).get("allocatedIPs"));
    }

    @Test
    public void returnsNullForMissingPortReservations() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();

        assertNull(allocator.portReservationList(null));
    }

    @Test(expected = ClassCastException.class)
    public void rejectsNonMapPortReservationEntries() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();

        allocator.portReservationList(Arrays.<Object>asList("not-a-map"));
    }

    @Test(expected = ClassCastException.class)
    public void rejectsNonStringPortReservationKeys() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();
        Map<Object, Object> reservation = new LinkedHashMap<Object, Object>();
        reservation.put(Integer.valueOf(1), "bad-key");

        allocator.portReservationList(Arrays.<Object>asList(reservation));
    }

    @Test
    public void convertsSchedulerHostsThroughCheckedBoundary() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();

        List<String> hosts = allocator.stringList(Arrays.<Object>asList("host-a", "host-b"));

        assertEquals(Arrays.asList("host-a", "host-b"), hosts);
    }

    @Test
    public void returnsNullForMissingSchedulerHosts() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();

        assertNull(allocator.stringList(null));
    }

    @Test(expected = ClassCastException.class)
    public void rejectsNonStringSchedulerHosts() {
        AllocatorServiceImpl allocator = new AllocatorServiceImpl();

        allocator.stringList(Arrays.<Object>asList("host-a", Long.valueOf(1)));
    }

    private EventVO<Map<String, Object>> schedulerEventWithRequests(ResourceRequest... requests) {
        Map<String, Object> schedulerRequest = new HashMap<String, Object>();
        if (requests.length > 0) {
            schedulerRequest.put("resourceRequests", Arrays.asList(requests));
        }
        return schedulerEventWithSchedulerRequest(schedulerRequest);
    }

    private EventVO<Map<String, Object>> schedulerEventWithSchedulerRequest(Map<String, Object> schedulerRequest) {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("schedulerRequest", schedulerRequest);
        return EventVO.<Map<String, Object>>newEvent("scheduler.prioritize").withData(data);
    }
}
