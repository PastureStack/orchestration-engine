package io.cattle.platform.servicediscovery.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.addon.PublicEndpoint;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryServiceImplTest {

    @Test
    public void replacementInstanceAtSameAddressIsPersistedAndReadBack() {
        EndpointFixture fixture = new EndpointFixture(endpoint(2L, 3L, 4L));

        fixture.update(Collections.singletonList(endpoint(2L, 5L, 4L)));

        assertEquals(1, fixture.writes);
        assertEquals(1, fixture.events);
        assertEquals("5", fixture.readBack().get(0).getInstanceId());
        fixture.update(Collections.singletonList(endpoint(2L, 5L, 4L)));
        assertEquals("persisted replacement must not be written again", 1, fixture.writes);
        assertEquals(1, fixture.events);
    }

    @Test
    public void changedHostAtSameAddressIsPersisted() {
        EndpointFixture fixture = new EndpointFixture(endpoint(2L, 3L, 4L));

        fixture.update(Collections.singletonList(endpoint(6L, 3L, 4L)));

        assertEquals(1, fixture.writes);
        assertEquals("6", fixture.readBack().get(0).getHostId());
    }

    @Test
    public void changedServiceAtSameAddressIsPersisted() {
        EndpointFixture fixture = new EndpointFixture(endpoint(2L, 3L, 4L));

        fixture.update(Collections.singletonList(endpoint(2L, 3L, 7L)));

        assertEquals(1, fixture.writes);
        assertEquals("7", fixture.readBack().get(0).getServiceId());
    }

    @Test
    public void changedAddressOrPortStillPersistsEndpoints() {
        EndpointFixture addressFixture = new EndpointFixture(endpoint(2L, 3L, 4L));
        PublicEndpoint changedAddress = endpoint(2L, 3L, 4L);
        changedAddress.setIpAddress("192.0.2.11");
        addressFixture.update(Collections.singletonList(changedAddress));
        assertEquals(1, addressFixture.writes);
        assertEquals("192.0.2.11", addressFixture.readBack().get(0).getIpAddress());

        EndpointFixture portFixture = new EndpointFixture(endpoint(2L, 3L, 4L));
        PublicEndpoint changedPort = endpoint(2L, 3L, 4L);
        changedPort.setPort(9090);
        portFixture.update(Collections.singletonList(changedPort));
        assertEquals(1, portFixture.writes);
        assertEquals(Integer.valueOf(9090), portFixture.readBack().get(0).getPort());
    }

    @Test
    public void sameEndpointSetInDifferentOrderDoesNotWriteOrPublish() {
        EndpointFixture fixture = new EndpointFixture(endpoint(2L, 3L, 4L), endpoint(6L, 7L, 8L));

        fixture.update(Arrays.asList(endpoint(6L, 7L, 8L), endpoint(2L, 3L, 4L)));

        assertEquals(0, fixture.writes);
        assertEquals(0, fixture.events);
    }

    @Test
    public void endpointsAtSameAddressKeepDistinctInstanceIdentities() {
        EndpointFixture fixture = new EndpointFixture(endpoint(2L, 3L, 4L));

        fixture.update(Arrays.asList(endpoint(2L, 3L, 4L), endpoint(2L, 5L, 4L)));

        assertEquals(1, fixture.writes);
        assertEquals(2, fixture.readBack().size());
        assertEquals("5", fixture.readBack().get(1).getInstanceId());
    }

    @Test
    public void identicalDuplicateEndpointsDoNotChangeTheSet() {
        EndpointFixture fixture = new EndpointFixture(endpoint(2L, 3L, 4L));

        fixture.update(Arrays.asList(endpoint(2L, 3L, 4L), endpoint(2L, 3L, 4L)));

        assertEquals(0, fixture.writes);
        assertEquals(0, fixture.events);
    }

    @Test
    public void missingNullAndEmptyEndpointListsAreEquivalent() {
        EndpointFixture fixture = new EndpointFixture((PublicEndpoint[]) null);

        fixture.update(Collections.emptyList());
        fixture.update(null);
        fixture.storeEndpoints(null);
        fixture.update(Collections.emptyList());
        fixture.storeEndpoints(Collections.emptyList());
        fixture.update(null);

        assertEquals(0, fixture.writes);
        assertEquals(0, fixture.events);
    }

    @Test
    public void nullEndpointListClearsPersistedEndpointsOnce() {
        EndpointFixture fixture = new EndpointFixture(endpoint(2L, 3L, 4L));

        fixture.update(null);
        fixture.update(null);

        assertEquals(1, fixture.writes);
        assertEquals(1, fixture.events);
        assertTrue(fixture.readBack().isEmpty());
    }

    @Test
    public void nullableEndpointFieldsAreStableAndChangesArePersisted() {
        EndpointFixture fixture = new EndpointFixture(new PublicEndpoint());

        fixture.update(Collections.singletonList(new PublicEndpoint()));
        assertEquals(0, fixture.writes);
        fixture.update(Collections.singletonList(endpoint(null, 3L, null)));

        assertEquals(1, fixture.writes);
        assertEquals("3", fixture.readBack().get(0).getInstanceId());
    }

    private static PublicEndpoint endpoint(Long hostId, Long instanceId, Long serviceId) {
        return new PublicEndpoint("192.0.2.10", 8080, hostId, instanceId, serviceId);
    }

    /** Persist JSON separately so readback cannot accidentally reuse an updated DTO. */
    private static final class EndpointFixture extends ServiceDiscoveryServiceImpl {
        private final JacksonJsonMapper mapper = new JacksonJsonMapper();
        private String persistedData;
        private int writes;
        private int events;

        private EndpointFixture(PublicEndpoint... endpoints) {
            jsonMapper = mapper;
            persistedData = "{}";
            if (endpoints != null) {
                storeEndpoints(Arrays.asList(endpoints));
            }
            objectManager = (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                    new Class<?>[] { ObjectManager.class }, (proxy, method, args) -> {
                        if (method.getName().equals("reload")) {
                            return loadService();
                        }
                        if (method.getName().equals("setFields") && args.length == 3) {
                            assertEquals(ServiceConstants.FIELD_PUBLIC_ENDPOINTS, args[1]);
                            ServiceRecord record = (ServiceRecord) args[0];
                            DataAccessor.setField(record, (String) args[1], ((Object[]) args[2])[0]);
                            persistedData = mapper.writeValueAsString(record.getData());
                            writes++;
                            return record;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }

        private ServiceRecord loadService() {
            ServiceRecord record = new ServiceRecord();
            record.setId(4L);
            record.setAccountId(1L);
            try {
                record.setData(mapper.readValue(persistedData));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return record;
        }

        private void storeEndpoints(List<PublicEndpoint> endpoints) {
            ServiceRecord record = loadService();
            DataAccessor.setField(record, ServiceConstants.FIELD_PUBLIC_ENDPOINTS, endpoints);
            try {
                persistedData = mapper.writeValueAsString(record.getData());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        private void update(List<PublicEndpoint> endpoints) {
            updateObjectEndPoints(loadService(), "service", 4L, 1L, endpoints);
        }

        private List<? extends PublicEndpoint> readBack() {
            return DataAccessor.fields(objectManager.reload(loadService()))
                    .withKey(ServiceConstants.FIELD_PUBLIC_ENDPOINTS).withDefault(Collections.emptyList())
                    .asList(mapper, PublicEndpoint.class);
        }

        @Override
        protected void publishEvent(Object object) {
            events++;
        }
    }

    @Test
    public void stringListReturnsEmptyListWhenMissing() {
        assertTrue(ServiceDiscoveryServiceImpl.stringList(null).isEmpty());
    }

    @Test
    public void stringListCopiesStringValuesInOrder() {
        List<String> ports = new ArrayList<String>();
        ports.add("80/tcp");
        ports.add("443:443/tcp");

        assertEquals(ports, ServiceDiscoveryServiceImpl.stringList(ports));
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonListValues() {
        ServiceDiscoveryServiceImpl.stringList("80/tcp");
    }

    @Test(expected = ClassCastException.class)
    public void stringListRejectsNonStringValues() {
        List<Object> ports = new ArrayList<Object>();
        ports.add(Integer.valueOf(80));

        ServiceDiscoveryServiceImpl.stringList(ports);
    }

    @Test
    public void stringMapReturnsEmptyMapWhenMissing() {
        assertTrue(ServiceDiscoveryServiceImpl.stringMap(null).isEmpty());
    }

    @Test
    public void stringMapCopiesStringEntriesInOrder() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("app", "web");
        labels.put("tier", "frontend");

        Map<String, String> result = ServiceDiscoveryServiceImpl.stringMap(labels);

        assertEquals(labels, result);
        assertEquals(new ArrayList<String>(labels.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void stringMapRejectsNonMapValues() {
        ServiceDiscoveryServiceImpl.stringMap("app=web");
    }

    @Test(expected = ClassCastException.class)
    public void stringMapRejectsNonStringKeys() {
        Map<Object, String> labels = new LinkedHashMap<Object, String>();
        labels.put(Integer.valueOf(1), "web");

        ServiceDiscoveryServiceImpl.stringMap(labels);
    }

    @Test(expected = ClassCastException.class)
    public void stringMapRejectsNonStringValues() {
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("app", Boolean.TRUE);

        ServiceDiscoveryServiceImpl.stringMap(labels);
    }

    @Test
    public void objectListReturnsEmptyListWhenMissing() {
        assertTrue(ServiceDiscoveryServiceImpl.objectList(null).isEmpty());
    }

    @Test
    public void objectListKeepsExistingValues() {
        List<Object> values = new ArrayList<Object>();
        values.add("primary");
        values.add(Integer.valueOf(1));

        assertEquals(values, ServiceDiscoveryServiceImpl.objectList(values));
    }

    @Test(expected = ClassCastException.class)
    public void objectListRejectsNonListValues() {
        ServiceDiscoveryServiceImpl.objectList("secondary");
    }

    @Test
    public void stringObjectMapReturnsEmptyMapWhenMissing() {
        assertTrue(ServiceDiscoveryServiceImpl.stringObjectMap(null).isEmpty());
    }

    @Test
    public void stringObjectMapCopiesStringKeysAndObjectValuesInOrder() {
        Map<String, Object> launchConfig = new LinkedHashMap<String, Object>();
        launchConfig.put("imageUuid", "docker:busybox");
        launchConfig.put("ports", Integer.valueOf(80));

        Map<String, Object> result = ServiceDiscoveryServiceImpl.stringObjectMap(launchConfig);

        assertEquals(launchConfig, result);
        assertEquals(new ArrayList<String>(launchConfig.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void stringObjectMapRejectsNonMapValues() {
        ServiceDiscoveryServiceImpl.stringObjectMap("image=busybox");
    }

    @Test(expected = ClassCastException.class)
    public void stringObjectMapRejectsNonStringKeys() {
        Map<Object, Object> launchConfig = new LinkedHashMap<Object, Object>();
        launchConfig.put(Integer.valueOf(1), "docker:busybox");

        ServiceDiscoveryServiceImpl.stringObjectMap(launchConfig);
    }
}
